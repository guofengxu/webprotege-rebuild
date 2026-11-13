---
id: P6-14
title: MCP PoC：选定 MCP Java SDK，在 /mcp 提供 API Key 认证的只读工具 search_entities
phase: P6
module: wp-app
type: impl
size: M
work_type: app-security
ecc_extra: []
status: todo
depends_on: [P4-17]
---

# P6-14 MCP 工具 PoC

## 目标
验证在现有 Spring Boot 4.1 应用中提供 MCP（Model Context Protocol）服务端是否可行：AI Agent 用 API Key 连接 `/mcp`（Streamable HTTP），列出工具并调用一个只读工具 `search_entities`；权限与 REST 接口完全一致，都经过命令总线（ADR-007）。下面的通过标准全部满足后，才继续 P6-15、P6-16。

## 参考
- ADR-007：“命令和查询的 `record` 定义同时作为将来 MCP 工具的参数结构”；ADR-010（认证）
- `tasks/P6/EPIC.md` 中的“MCP 工具”一行（只读查询优先，写操作需要人工确认）
- `search/SearchEntities.java`（`record SearchEntities(ProjectId projectId, String query, Set<EntityType<?>> types, int limit)`，`MAX_LIMIT = 200`）、`search/SearchEntitiesHandler.java`（需要 `VIEW_PROJECT`）
- `bus/CommandBus.java#execute(UserId, Command)`；`shared/CodedException.java`
- P3-05：`web/security/ApiKeyAuthenticationFilter.java`（请求头 `X-API-Key`）、`web/security/SecurityConfig.java`、`web/security/CorsProperties.java`
- 根 `pom.xml` 与 `backend/wp-app/pom.xml` 中的 Enforcer 规则（`dependencyConvergence`、`bannedDependencies`）；P0-02 / P3-23 处理新依赖的方式（版本写成属性，执行记录中写明选择理由）
- MCP 规范（2025-06-18 或更新版本）：Streamable HTTP 传输、`initialize` / `tools/list` / `tools/call`、工具结果的 `isError`；Streamable HTTP 服务端应校验 `Origin` 请求头

## 产出
- 依赖（本卡唯一允许新增的第三方依赖）：`io.modelcontextprotocol.sdk:mcp-core` 及其 JSON 绑定模块。优先选与 Spring Boot 4.1 相同的 Jackson 3 绑定；SDK 只提供 Jackson 2 绑定时，用 `mcp-json-jackson2`，并确认 Enforcer 的依赖收敛能通过。取当时最新的稳定版，版本写成 `backend/wp-app/pom.xml` 中的属性 `mcp-sdk.version`。**不引入** Spring AI。
- `web/mcp/McpServerConfiguration.java`：用 SDK 的 Servlet 版 Streamable HTTP 传输（类名以所选版本为准，例如 `HttpServletStreamableServerTransportProvider`；SDK 支持无状态模式时优先使用）创建同步 MCP 服务端，以 `ServletRegistrationBean` 挂在 `/mcp`。`serverInfo` 的名称为 `wp-ontology`，版本取 `AppInfo`。
- `web/mcp/McpUserContext.java`：在传输层的上下文提取器中（该代码运行在请求线程上），从 `SecurityContextHolder` 取出已认证的 `UserId`，放进 SDK 的传输上下文。工具处理器只从传输上下文读取用户，**不要**在工具处理器里读 `SecurityContextHolder`：SDK 可能在其它线程上调用处理器。用户**每个请求都重新读取**，不按 MCP 会话缓存。若所选模式是有状态的（有 `Mcp-Session-Id`），在 `initialize` 时记下创建会话的用户；之后的请求，API Key 对应的用户与会话的创建者不同时拒绝（返回 403，或 SDK 给出的等价错误）。
- `web/mcp/McpToolResult.java`：全部工具的统一结果外层结构 `{"result": <与对应 REST 接口相同的响应体>, "truncated": false, "hint": null}`，序列化成一个 `text` 内容。REST 返回裸数组的接口（例如 `listProjects`、层级接口）也放在 `result` 中，因此 `truncated` 与 `hint` 永远在外层。截断规则由 P6-15 实现，本卡的 `search_entities` 只输出 `truncated: false`。
- `web/mcp/SearchEntitiesTool.java`：工具 `search_entities`。输入的 JSON Schema 与 `SearchEntities` 的字段一一对应：`projectId`（必填）、`query`（必填）、`types`（可选，取值同 REST 接口）、`limit`（可选，1 ~ 200，默认 20）。调用 `bus.execute(user, new SearchEntities(...))`，结果用应用的 `JsonMapper` 序列化为 `McpToolResult`，其中 `result` 与 REST 接口 `searchEntities` 的响应相同。
- `web/mcp/McpToolErrors.java`：`CodedException`（如 `PermissionDeniedException`、`NotFoundException`、`ConflictException`）转换为 `isError: true` 的工具结果，内容为 `{code, message}`；其它异常记 error 日志，返回不含堆栈的通用消息。
- `web/security/SecurityConfig.java`：为 `/mcp` 与 `/mcp/**` 增加一条独立的 `SecurityFilterChain`。它的 `@Order` 必须小于 `staticContent`（当前为 `@Order(1)`，匹配 `/**` 并放行全部请求，现在也匹配 `/mcp`）：把 MCP 链设为 `@Order(1)`，`staticContent` 改为 `@Order(2)`；MCP 链与 `apiSecurity`（`@Order(0)`，`/api/**`）的路径不重叠。这条链：无会话、关闭 CSRF、只用 `ApiKeyAuthenticationFilter` 认证、所有请求都必须已认证；会话 Cookie 与 Bearer 令牌在这条链上都不被接受（返回 401）；请求带 `Origin` 头且不在 `CorsProperties` 允许列表中时返回 403。
- 测试 `web/McpEndpointTest.java`（随机端口 + Testcontainers；客户端用 SDK 自带的 MCP 客户端与 Streamable HTTP 客户端传输）。断言见下面的通过标准。

## 通过标准（全部满足才算 PoC 通过）
1. `./mvnw -q verify` 通过（`task.py test` 的全量检查），包括 Enforcer 的 `dependencyConvergence` 与 `bannedDependencies`；为了收敛而加的 `exclusion` 不超过 2 处，并写入执行记录。加依赖之前和之后各运行一次 `./mvnw -q -pl :wp-app dependency:tree -DoutputFile=<文件>`，把两者的差异（全部新增的传递依赖）写入执行记录。
2. 客户端完成 `initialize` → `tools/list` → `tools/call`：`tools/list` 恰好包含 `search_entities` 且带输入 schema；在导入 `testdata/ontologies/warehouse-sample.ttl` 的项目中调用 `search_entities`，结果包含“托盘”。
3. 没有 `X-API-Key` 时返回 401；只带会话 Cookie（先经 `/api/auth/login` 登录）时返回 401；带不在允许列表中的 `Origin` 时返回 403；对没有 `VIEW_PROJECT` 的项目调用工具时，返回 `isError: true` 且 `code = PERMISSION_DENIED`，结果中没有任何实体数据。
4. `McpEndpointTest` 中的延迟测试方法（`@EnabledIfSystemProperty(named = "bench", matches = "true")`，默认不运行，避免在 CI 上时好时坏）顺序调用 100 次 `search_entities`，断言单次 p95 ≤ 100 毫秒，并把结果打印到测试日志、写入执行记录。
5. 加依赖之前和之后各运行一次 `./mvnw -q -pl :wp-app -am package -DskipTests`，比较可执行 jar 中 `BOOT-INF/lib/` 的内容：新增 jar 的总大小不超过 5 MB。基线与结果都写入执行记录。
6. 有状态模式下，用 API Key A 完成 `initialize`，再用 API Key B 带同一个 `Mcp-Session-Id` 调用工具，请求被拒绝。选择无状态模式时，在执行记录中说明，并测试 B 的请求只按 B 的权限执行。

任一项不满足时：不要绕过（例如关闭 Enforcer 规则、复制 SDK 源码）。运行 `task.py block`，写明卡在哪一项和看到的现象，由人决定是换 SDK 版本、改用其它方案，还是放弃 MCP 条目。

## 实现要点
- MCP 端点只是另一个适配器，和 REST 控制器一样放在 `web` 模块的子包 `web.mcp` 中，因此不新增 Modulith 模块，也不改依赖矩阵。
- 权限只在命令总线上校验一次，工具层不做额外判断，也不缓存结果。
- 日志中不记录 API Key 或工具参数中的查询词。

## ECC 技能
工作项类型：`app-security` —— 认证、授权、令牌与其它安全相关接口。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-14` 查看。

- 开工前加载的 skill：`springboot-security`、`security-review`、`springboot-tdd`、`java-coding-standards`
- 提交前调用的审查 agent：`security-reviewer`、`java-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='McpEndpointTest,ApiKeyTest,CorsTest,AuthFlowTest,SpaFallbackTest,ModularityTest,OpenApiContractTest'
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=McpEndpointTest -Dbench=true
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 通过标准 1 ~ 6 的结果已写入执行记录
- [ ] 执行记录已填写

## 不在本卡范围
- 除 `search_entities` 之外的工具（P6-15、P6-16）
- 写操作工具以及“人工确认”机制
- MCP 的 SSE 旧式传输、stdio 传输、OAuth 授权
- MCP 客户端配置文档（P6-16）
