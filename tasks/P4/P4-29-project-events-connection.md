---
id: P4-29
title: 实时事件（二）：订阅 SSE、断线重连带 Last-Event-ID、连接状态、接入项目外壳
phase: P4
module: wp-web
type: impl
size: M
work_type: web
ecc_extra: []
status: done
depends_on: [P4-05, P4-14]
---

# P4-29 实时事件：连接与重连

## 目标
打开项目后，Web 端订阅该项目的服务端事件，并交给 P4-14 的 `applyProjectEvent` 处理；网络断开后自动重连，并通过 `Last-Event-ID` 补齐断线期间的事件；工具栏显示连接状态。本卡由 EPIC 第 14 项拆出（解析与缓存失效见 P4-14）。

## 参考
- `tasks/P4/EPIC.md` 第 14 项
- P3-21：带 `Last-Event-ID` 时先补发之后的事件，超出缓冲范围（默认最近 200 个修订）时发送 `event: resync`；`resync` 不带 `id`；每 25 秒一行心跳注释；`backend/wp-app/src/main/java/wprebuild/app/events/ProjectEventHub.java`：不带游标的连接只订阅之后的新事件
- `backend/wp-app/src/main/java/wprebuild/app/web/ProjectEventsController.java`：只在建立连接时校验一次权限，之后的事件推送给全部订阅者（后端缺口：被移出项目的用户在重连前仍能收到事件，记入 P4-17 的差距清单）
- P3-24 执行记录：后端验收用长轮询订阅事件，SSE 在会话 Cookie 下还没有被真实客户端验证过
- P4-14 的 `parseSse`、`parseProjectEvent`、`applyProjectEvent`；P4-02 的 `useApiClient`、`onUnauthorized`（P4-25 已把它接到登录跳转）；P4-05 的 `ProjectShell.vue` 与注册表（`workspaceToolbar`）
- `docs/api/openapi.json` 的 operationId：`streamProjectEvents`（`Last-Event-ID` 是契约中声明的请求头参数）

## 产出
- `src/features/events/useProjectEvents.ts`：`useProjectEvents(projectId: Ref<string>, enabled: Ref<boolean>)`：
  - `enabled` 为真时用 `useApiClient().GET("/api/projects/{id}/events", { params: { path: { id }, header: { "Last-Event-ID": lastId } }, parseAs: "stream", signal })` 建立连接（原生 `EventSource` 不能在手动重连时设置 `Last-Event-ID`，所以不用它）；
  - `lastId` 只用带 `id` 的事件更新；收到 `resync` 后清空 `lastId`（之后的重连按首次连接处理），同一次连接中重复的 `resync` 只失效一次；
  - 首次连接（没有 `lastId`）进入 `open` 后，失效一次 `queryKeys.project(projectId)`，补上界面加载与订阅之间可能漏掉的事件；
  - `applyProjectEvent` 返回 `reconnect: true`（`PermissionsChanged`）时中止当前连接并立即带 `Last-Event-ID` 重连，让服务端重新校验权限；
  - 连接结束或出错后按 1s、2s、4s … 最长 30s 退避重连，并带上 `Last-Event-ID`；超过 60 秒没有收到任何数据或心跳时主动断开重连；
  - 401 交给客户端的 `onUnauthorized` 并停止；403 / 404 / 409 停止重连并在界面上提示；
  - 页面隐藏（`visibilitychange`）超过 5 分钟后断开，重新可见时带 `Last-Event-ID` 重连；
  - 组件卸载、`enabled` 变为假或 `projectId` 变化时中止当前连接并清除定时器；
  - 返回 `status: Ref<"idle" | "connecting" | "open" | "reconnecting" | "stopped">`、`stopReason: Ref<string | null>` 与 `retry(): void`。
- 修改 `src/features/workspace/ProjectShell.vue`（P4-05）：`getProject` 成功后才调用 `useProjectEvents`（`enabled` 绑定到加载成功；403 / 404 / 409 时不订阅）；用 `provide` 把 `status`、`stopReason` 与 `retry` 提供给子组件。
- 修改 `src/features/workspace/ProjectShell.spec.ts`（P4-05）：为事件接口补一个返回空流的处理器；断言 `getProject` 返回 403 / 409 时不发出事件请求、成功时发出一次。其它经过 `ProjectShell` 挂载的测试（如 `WorkspacePage.spec.ts`）如果因为未匹配的事件请求失败，同样补处理器，只加桩、不改断言。
- `src/features/events/ConnectionStatus.vue`：通过 `src/features/events/register.ts` 注册到 `workspaceToolbar`，`inject` 上面的状态；重连中显示提示，停止时显示原因与“重试”；状态变化用 `aria-live="polite"` 播报。
- 文案：`src/features/events/locales/`（顶层键 `events`）。
- 测试：
  - `src/features/events/useProjectEvents.spec.ts`：用可控的假流与假定时器驱动：收到事件后调用 `applyProjectEvent`；流结束后按退避时间重连，第二次请求带上最后一个有 `id` 的事件的 `Last-Event-ID`；收到 `resync` 后再次重连不带 `Last-Event-ID`；首次 `open` 后项目查询被失效一次；`PermissionsChanged` 触发立即重连；403 后停止重连；`enabled` 为假时不发请求；卸载后中止请求且没有残留定时器。
  - `src/features/workspace/ProjectShell.spec.ts`（见上）。

## 实现要点
- Vite 开发服务器的代理可以透传 SSE；如果发现被缓冲，在 `vite.config.ts` 的代理中关闭压缩或缓冲，并在执行记录中写明。
- SSE 在会话 Cookie 下还没有被真实客户端验证过：实现后用本地后端（`docker compose up -d postgres` 并启动 wp-app）加 `pnpm -C frontend --filter wp-web run dev`，在两个浏览器窗口中手工验证一次“一边修改、另一边自动刷新”，结果写进执行记录；发现后端问题时按 `AGENTS.md` 第 6 节停下。
- 预计改动接近 400 行；若明显超出，把页面可见性处理与心跳超时拆成另一张卡，按 `AGENTS.md` 第 6 节停下说明。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P4-29` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend --filter wp-web exec vitest run src/features/events/useProjectEvents.spec.ts src/features/workspace/ProjectShell.spec.ts src/features/workspace/WorkspacePage.spec.ts
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 重连请求带 `Last-Event-ID`；`getProject` 失败时不订阅
- [x] 执行记录中写明了手工验证 SSE 的结果
- [x] 执行记录已填写

## 不在本卡范围
- 事件解析与缓存失效规则（P4-14）
- 长轮询客户端（P5）
- 后端在权限变化时清理订阅（后端缺口，另开卡）

## 执行记录
- 日期：2026-11-13
- 分支：feature/p4-29-project-events-connection
- 手工验证 SSE：启动过一次本地 `wp-app`（端口 18081，bootstrap 管理员）并连上当时仍健康的 Postgres；随后 agent 环境里 docker.sock / `127.0.0.1:5432` 不可达，未能完成「两窗口一边改、一边自动刷新」的浏览器验证。`useProjectEvents.spec.ts` 已覆盖带 `Last-Event-ID` 重连、`resync` 清游标、`PermissionsChanged` 立即重连、403 停订等行为。请人在确认提交后用 `docker compose up -d postgres`、启动 wp-app 与 `pnpm -C frontend --filter wp-web run dev` 补做一次双窗口验证。
- Vite 代理：未改 `vite.config.ts`（为 SSE 加 `configure` 会导致 Vitest 下 WorkspacePage 用例异常）。
- 日期：2026-11-13
- 分支：feature/p4-29-project-events-connection
- 测试：2026-11-13T14:26:06+08:00 通过（3 项）
- 实际改动：Added useProjectEvents (stream GET, Last-Event-ID reconnect/backoff/heartbeat/visibility), ProjectShell provide, and ConnectionStatus toolbar with i18n. Vite proxy unchanged. Manual two-browser UI SSE refresh blocked after docker/postgres became unreachable in the agent environment; vitest covers Last-Event-ID/resync/PermissionsChanged/403.
- 偏差：Did not change vite.config.ts for SSE buffering (proxy configure broke Vitest WorkspacePage.spec). ConnectionStatus stays in workspaceToolbar; aria-live node is persistent with Retry outside it. Two-browser live refresh still needs human confirmation.
- 审查：vue-reviewer, typescript-reviewer, a11y-architect
