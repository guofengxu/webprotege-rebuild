---
id: P6-16
title: MCP 只读工具（二）：个体列表、修订历史、修订差异、实体历史，以及 MCP 使用说明
phase: P6
module: wp-app
type: impl
size: M
work_type: app-service
ecc_extra: []
status: done
depends_on: [P6-15]
---

# P6-16 MCP 个体与历史工具、使用说明

## 目标
AI Agent 可以通过 MCP 按类分页列出个体，查看项目的修订历史、某个修订改了什么、某个实体被谁在什么时候修改过；使用者按一份文档就能把 MCP 客户端接到本服务。完成后，EPIC 中“MCP 工具”一项的只读部分全部可用。

## 参考
- P6-14 / P6-15：`web/mcp/` 下的工具写法、`McpToolResult`、`McpResultLimits`（带分页字段的对象只截断条目数组）、`McpToolErrors`
- 查询命令：`ontology/ListIndividualsByType(projectId, cls, page, size)`（需要 `VIEW_PROJECT`）；`ontology/ListRevisions(projectId, page, size)`、`ontology/GetRevisionDiff(projectId, revision, limit)`、`ontology/ListEntityRevisions(projectId, iri, page, size)`（三者都要求 `VIEW_CHANGES`）
- 对应的 REST 响应：`listIndividualsByType`、`listRevisions`、`getRevisionDiff`、`listEntityRevisions`（`docs/api/openapi.json`）
- P3-05：API Key 的签发（Web 端“个人设置”，P4-20）；`web/security/SecurityConfig.java`：同一个 API Key 也能访问全部 `/api/**`，包括写接口（ADR-010 没有按范围划分的密钥）
- `docs/api/README.md`（`docs/api/` 下文档的组织方式）

## 产出
- `web/mcp/HistoryTools.java`：
  | 工具 | 命令 | 参数 |
  |---|---|---|
  | `list_individuals` | `ListIndividualsByType` | `projectId`、`cls`、`page`（默认 0）、`size`（默认 50，最大与 REST 接口相同） |
  | `list_revisions` | `ListRevisions` | `projectId`、`page`（默认 0）、`size`（默认 20，最大与 REST 接口相同） |
  | `get_revision_diff` | `GetRevisionDiff` | `projectId`、`revision`、`limit`（默认与 REST 接口相同） |
  | `list_entity_revisions` | `ListEntityRevisions` | `projectId`、`iri`、`page`、`size` |
  结果都是 `McpToolResult`，`result` 与对应 REST 响应相同，并使用 `McpResultLimits` 截断。
- `docs/api/mcp.md`：
  - 端点 `/mcp`（Streamable HTTP），认证方式为请求头 `X-API-Key`（只接受 API Key），`Origin` 校验规则，结果外层结构 `{result, truncated, hint}`；
  - 如何签发 API Key（Web 端“个人设置”），以及一个通用的 MCP 客户端配置示例（JSON，URL 与请求头；密钥写成占位符）；
  - 全部 10 个工具的用途、参数、对应的 REST 接口与所需权限；
  - 限制：工具只读；结果大小上限 `wp.mcp.max-result-bytes`；分页与逐级展开的用法；
  - 安全提示：**API Key 带有该用户的全部权限**，同一个密钥也能调用全部 REST 接口，包括写操作；MCP 只是不提供写工具。给 AI Agent 用时，建议建一个专用账号，只在需要的项目上授予 `CAN_VIEW`；密钥泄露后在“个人设置”中吊销。只读或按范围划分的密钥需要另开卡。
- `docs/api/README.md`：增加一行，指向 `mcp.md`。
- 测试 `web/McpHistoryToolsTest.java`：
  - `list_individuals` 分页正确，`result` 与 REST `listIndividualsByType` 相同；
  - 编辑两次后，`list_revisions` 的前两条与 REST `listRevisions` 相同；`get_revision_diff` 的 `result` 与 REST `getRevisionDiff` 相同；`list_entity_revisions` 只包含该实体的修订；
  - 非成员调用本卡的四个工具：返回 `isError: true` 与 `PERMISSION_DENIED`（当前四种角色都包含 `VIEW_CHANGES`，所以用非成员测试拒绝分支）；
  - `wp.mcp.max-result-bytes` 设得很小时，`list_revisions` 只截断条目数组，分页字段保留，`truncated: true`；
  - `tools/list` 一共 10 个工具，名称与 `docs/api/mcp.md` 中列出的一致（测试读取该文件并比较工具名集合，防止文档过期）。

## 实现要点
- 与 P6-15 相同：工具层只做“参数 → 命令 → 序列化”。
- 文档里的示例不写真实的主机名、密钥或用户名。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-16` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='McpHistoryToolsTest,McpBrowseToolsTest,McpEndpointTest'
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写

## 不在本卡范围
- 回滚等写操作工具（需要“人工确认”机制，另开卡）
- 只读或按范围划分的 API Key
- 下载导出工具（结果可能很大，另行评估）
- MCP 的 `resources`、`prompts`、OAuth 授权

## 执行记录
- 日期：2026-11-14
- 分支：feature/p6-16-mcp-history-tools
- 测试：2026-11-14T20:39:17+08:00 通过（3 项）
- 实际改动：注册 list_individuals / list_revisions / get_revision_diff / list_entity_revisions 四个只读 MCP 工具；新增 docs/api/mcp.md 使用说明并在 README 中链接；测试对照 REST 与文档工具名集合。
- 偏差：无
- 审查：java-reviewer, code-reviewer
