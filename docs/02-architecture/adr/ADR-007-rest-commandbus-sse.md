# ADR-007 对外 REST，对内命令总线，事件用 SSE 与长轮询

- 状态：已接受
- 日期：2026-10-28
- 修订：2026-10-29 —— 增加长轮询接口，供不支持 SSE 的 uni-app App 端使用。

## 决策
- **内部**：保留旧系统的命令总线思想。每个操作是一个 `record` 命令或查询，由一个 Handler 处理；总线在调用 Handler 之前统一执行校验链（已登录 → 项目存在 → 权限，BS-10）。
- 例外：只要求登录、并且只操作当前用户本人数据的接口（`/api/auth/*`、`/api/me/*`）可以不经过命令总线，由控制器直接调用服务。
- **对外**：REST + JSON，路径前缀 `/api`，OpenAPI 文档由 springdoc 生成。控制器只做参数绑定，然后调用总线。错误统一为 RFC 9457 `ProblemDetail`。
- **事件（Web 端）**：`GET /api/projects/{id}/events`，SSE。事件 ID 为修订号；客户端用 `Last-Event-ID` 断线续传；服务端从修订日志补发。
- **事件（移动端）**：`GET /api/projects/{id}/events/poll?after=<修订号>&timeout=25`，长轮询，返回 JSON 数组；没有新事件时在超时后返回空数组。两种接口使用同一个事件缓冲和同样的事件格式。
- 命令和查询的 `record` 定义同时作为将来 MCP 工具的参数结构（P6）。

## 理由
- 命令总线让权限校验集中在一个地方，Agent 写新 Handler 时不会漏掉。
- SSE 比轮询省资源，比 WebSocket 简单，单向推送正好够用。uni-app 的 App 端和小程序端没有原生 `EventSource`，长轮询是兼容面最广的补充。

## 被否决的选项
- GraphQL：查询模式固定，引入成本不值得。
- WebSocket：需要双向通信时再考虑。
