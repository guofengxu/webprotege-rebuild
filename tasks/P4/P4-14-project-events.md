---
id: P4-14
title: 实时事件（一）：SSE 解析、事件解析、按事件让 Vue Query 缓存失效
phase: P4
module: wp-web
type: impl
size: M
work_type: web
ecc_extra: []
status: todo
depends_on: [P4-02, P4-18]
---

# P4-14 实时事件：解析与缓存失效

## 目标
把服务端事件流解析成类型安全的项目事件，并按事件让相关的 Vue Query 缓存失效：其他人的修改会让层级、个体列表、Frame、历史、搜索结果自动刷新；收到 `resync` 时重新加载该项目的全部数据。本卡只有纯函数，连接、重连与界面接入见 P4-29（EPIC 第 14 项拆成两张）。

## 参考
- `tasks/P4/EPIC.md` 第 14 项
- BS-11（高层事件）、ADR-007（REST + 命令总线 + SSE）
- P3-21：
  - `GET /api/projects/{id}/events`（`text/event-stream`）：每个事件 `id` 为修订号、`event` 为事件类型名、`data` 为 JSON；每 25 秒一行心跳注释；`resync` 事件**不带** `id`（`web/ProjectEventsController.java`）；
  - 事件类型与 `data` 字段：`ProjectChanged{type, revNo, user, timestamp, description, changeCount}`、`EntityHierarchyChanged{type, revNo, hierarchy, added: {parent, child}[], removed: {parent, child}[]}`（`hierarchy` 是路径写法，如 `class`、`object-property`）、`EntityFrameChanged{type, revNo, entity: {iri, entityType}}`、`BrowserTextChanged{type, revNo, entity, displayName}`、`PermissionsChanged{type, revNo, projectId}`、`resync{type: "resync"}`；
  - 显示语言设置变化时后端也会发送 `resync`（P3-22）
- `backend/wp-app/src/main/java/wprebuild/app/events/EventTranslator.java`：只有类与三种属性层级的增量会产生 `EntityHierarchyChanged`；个体的类型（`ClassAssertion`）变化只产生主语为该个体的 `EntityFrameChanged`
- P4-18：事件 schema 的 `type` 是单值枚举，`@wp/api-types` 导出可按 `type` 收窄的 `ProjectEvent` 与 `ProjectStreamMessage`（`ProjectEvent | ResyncSignal`）
- P4-02 的 `queryKeys`（失效完全依赖它的前缀键）；P4-06 的“子节点查询失效 → 树更新”；P4-22 的 `serverChanged` 提示
- `docs/api/openapi.json` 的 operationId：`streamProjectEvents`（本卡只描述其数据格式，不发请求）

## 产出
- `src/features/events/sseParser.ts`：`parseSse(stream: ReadableStream<Uint8Array>): AsyncGenerator<SseMessage>`，按 SSE 规范解析 `id` / `event` / `data`（多行 `data` 拼接）/ 注释行（注释行产出 `{ event: "comment" }`，供 P4-29 判断心跳）；`SseMessage = { id?: string; event: string; data: string }`。
- `src/features/events/projectEvents.ts`：`parseProjectEvent(message: SseMessage): ProjectStreamMessage | null`（类型来自 `@wp/api-types`；`data` 不是 JSON 或 `type` 未知时返回 `null`，开发模式下 `console.warn`）。
- `src/features/events/applyProjectEvent.ts`：纯函数 `applyProjectEvent(queryClient, projectId, event): { reconnect: boolean }`，按 `event.type` 收窄后处理（不强转）：
  - `ProjectChanged` → 失效 `revisionsAll`、`projectDetails`；
  - `EntityHierarchyChanged` → 失效该层级的整个前缀 `queryKeys.hierarchy(projectId, hierarchy)`（父节点原来是叶子时，它的 `hasChildren` 存在上一级的子节点查询里，只失效父节点自己的子节点查询不会让展开箭头出现；只有处于活动状态的查询会立即重新请求）以及 `frames(projectId)`（Frame 中引用节点的 `hasChildren` 也变了，P4-22 的 `from` 需要最新值）；`hierarchy = class` 时同时失效 `individualsAll`；
  - `EntityFrameChanged` → 失效 `frame(projectId, entity.entityType, entity.iri)` 与 `entityHistoryAll(projectId, entity.iri)`；`entity.entityType = NAMED_INDIVIDUAL` 时同时失效 `individualsAll`（个体类型变化只产生这个事件）；
  - `BrowserTextChanged` → 失效四种层级的 `queryKeys.hierarchy(projectId, kind)` 前缀、`individualsAll`、`searchAll`、`frames`（显示名称变化会影响这些列表，以及 Frame 中引用的其它实体的名称）；
  - `PermissionsChanged` → 失效 `queryKeys.project(projectId)` 全部与 `projects()` 列表，返回 `{ reconnect: true }`（让 P4-29 断开后带 `Last-Event-ID` 重连，由服务端重新校验权限）；
  - `resync` → 失效 `queryKeys.project(projectId)` 全部；
  - 其余情况返回 `{ reconnect: false }`；所有前缀键来自 P4-02 的 `queryKeys`，不在本卡拼数组。
- 测试：
  - `src/features/events/sseParser.spec.ts`：多行 `data`、注释心跳、`id` 与 `event` 字段、跨数据块切分的消息、没有 `id` 的 `resync` 都能正确解析。
  - `src/features/events/projectEvents.spec.ts`：六种事件各解析出对应类型；未知 `type` 与非法 JSON 返回 `null`。
  - `src/features/events/applyProjectEvent.spec.ts`：用真实 `QueryClient` 预置若干查询，检查 `isInvalidated`：每种事件只让预期的查询失效；`EntityHierarchyChanged` 让该层级中“父节点的父节点”的子节点查询也失效；`NAMED_INDIVIDUAL` 的 `EntityFrameChanged` 让个体列表失效；`PermissionsChanged` 返回 `reconnect: true`；`resync` 让项目下全部查询失效，但不影响其它项目的查询。

## 实现要点
- 失效而不是就地修改缓存：MVP 以正确为先，由 Vue Query 重新请求；正在编辑的 Frame 不会被覆盖（P4-22 的 `serverChanged` 处理）。
- 同一用户自己的修改也会收到事件，重复失效是预期行为。
- 本卡只用 SSE 的数据格式；长轮询（`pollProjectEvents`）给移动端使用（P5），Web 端不实现。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P4-14` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend --filter wp-web exec vitest run src/features/events/sseParser.spec.ts src/features/events/projectEvents.spec.ts src/features/events/applyProjectEvent.spec.ts
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 收到 `resync` 时项目数据全部失效；层级变化能让原来的叶子节点出现展开箭头
- [ ] 执行记录已填写

## 不在本卡范围
- 建立连接、重连、`Last-Event-ID`、连接状态界面、接入 `ProjectShell`（P4-29）
- 长轮询客户端（P5）
- 推送通知、桌面通知；在界面上显示“谁正在编辑”（后端不提供）
