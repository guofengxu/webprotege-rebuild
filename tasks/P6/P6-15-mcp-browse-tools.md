---
id: P6-15
title: MCP 只读工具（一）：项目列表、层级浏览、实体 Frame，以及结果大小限制
phase: P6
module: wp-app
type: impl
size: M
work_type: app-service
ecc_extra: []
status: todo
depends_on: [P6-14]
---

# P6-15 MCP 浏览工具

## 目标
AI Agent 通过 MCP 可以像 Web 端一样浏览本体：列出自己能访问的项目，逐级查看四种层级，定位实体在层级中的路径，读取五种实体的 Frame。每个工具对应一个现有的查询命令，权限与 REST 接口一致；结果过大时按统一规则截断。

## 参考
- P6-14：`web/mcp/McpServerConfiguration`、`McpUserContext`（工具处理器从传输上下文取用户）、`McpToolErrors`、`McpToolResult`（`{result, truncated, hint}`）、`SearchEntitiesTool`（工具的写法）
- ADR-007（命令 `record` 即工具参数结构）
- 查询命令（`backend/wp-app/src/main/java/wprebuild/app/`）：`project/ListMyProjects()`；`ontology/GetHierarchyRoots(projectId, kind)`、`GetHierarchyChildren(projectId, kind, parent)`、`GetHierarchyPaths(projectId, kind, entity)`，其中 `HierarchyKind` 为 `class` / `object-property` / `data-property` / `annotation-property`；`ontology/GetClassFrame`、`GetObjectPropertyFrame`、`GetDataPropertyFrame`、`GetAnnotationPropertyFrame`、`GetNamedIndividualFrame`（都是 `(projectId, iri)`）
- 对应的 REST 响应（`docs/api/openapi.json`）：`listProjects`、`getHierarchyRoots`、`getHierarchyChildren`、`getHierarchyPaths`（`web/ProjectController.java`），以及 `getClassFrame` 等

## 产出
- `web/mcp/BrowseTools.java`：注册以下工具。参数名与命令 `record` 的字段名一致；IRI 一律是完整 IRI 字符串；`kind` 取 `HierarchyKind` 的小写取值。
  | 工具 | 命令 | 参数 |
  |---|---|---|
  | `list_projects` | `ListMyProjects` | 无 |
  | `get_hierarchy_roots` | `GetHierarchyRoots` | `projectId`、`kind` |
  | `get_hierarchy_children` | `GetHierarchyChildren` | `projectId`、`kind`、`parent` |
  | `get_hierarchy_paths` | `GetHierarchyPaths` | `projectId`、`kind`、`entity` |
  | `get_entity_frame` | 按 `entityType` 选择五个 `Get*Frame` 命令之一 | `projectId`、`entityType`（`class` / `object-property` / `data-property` / `annotation-property` / `named-individual`）、`iri` |
- 每个工具的结果都是 P6-14 的 `McpToolResult`，`result` 与对应 REST 响应相同（复用 `web` 中已有的响应映射，不另写 DTO）。
- `web/mcp/McpResultLimits.java`：`McpToolResult` 序列化后超过 `wp.mcp.max-result-bytes`（默认 256 KB）时，按以下规则截断：
  - `result` 是数组（项目列表、层级节点、路径列表）：保留前面尽可能多的完整元素，使整体不超过上限；`truncated` 为 `true`，`hint` 给出下一步做法（例如“请用 `get_hierarchy_children` 逐级展开”）；
  - `result` 是带分页字段的对象（P6-16 的个体列表、修订列表）：只截断其中的条目数组，规则同上，`hint` 提示减小 `size` 或翻页；
  - `result` 是 Frame 等其它对象：不截断。超过上限时返回 `isError: true` 与 `code = RESULT_TOO_LARGE`，消息中给出实际大小与上限。
- 每个工具的 `description` 用英文写一两句话，说明用途和参数之间的关系（例如 `get_entity_frame` 的 `entityType` 要与实体的实际类型一致，否则返回 404）。
- 测试 `web/McpBrowseToolsTest.java`（沿用 P6-14 的测试客户端；导入 `testdata/ontologies/warehouse-sample.ttl`）：
  - `tools/list` 包含 P6-14 的 `search_entities` 与本卡的 5 个工具，每个都带输入 schema；
  - 从 `get_hierarchy_roots` 到 `get_hierarchy_children` 逐级找到“托盘”；`get_hierarchy_paths` 返回“托盘”到根的路径；
  - `get_entity_frame` 读取“托盘”的类 Frame，`result` 与 REST `getClassFrame` 的响应逐字段相同；
  - 把 `wp.mcp.max-result-bytes` 设得很小：数组结果带 `truncated: true` 且仍是合法 JSON，元素都完整；Frame 结果返回 `RESULT_TOO_LARGE`；
  - 不能查看项目的用户：`list_projects` 中没有该项目，其余工具返回 `isError: true` 与 `PERMISSION_DENIED`；参数缺失或格式错误时返回 `isError: true` 与 `VALIDATION_FAILED`。

## 实现要点
- 工具层只做“参数 → 命令 → 序列化”，不写任何业务逻辑，也不绕过命令总线读取内存模型。
- 与 REST 的响应结构保持一致（都在 `result` 中），Agent 拿到的数据与 Web 端看到的相同。
- 参数解析失败（例如 IRI 非法、`kind` 不认识）统一转换为 `VALIDATION_FAILED`，复用 `web/ProblemDetailsAdvice.VALIDATION_FAILED` 这个错误码。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-15` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='McpBrowseToolsTest,McpEndpointTest,ModularityTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 个体列表、修订历史与差异工具、MCP 使用说明（P6-16）
- 写操作工具
- MCP 的 `resources` 与 `prompts` 能力
