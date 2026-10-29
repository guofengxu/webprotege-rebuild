---
id: P3-21
title: 高层事件推送：SSE 与长轮询（BS-11）
phase: P3
module: wp-app/events, web
type: impl
size: M
work_type: app-service
ecc_extra: [concurrency]
status: todo
depends_on: [P3-03, P3-06]
---

# P3-21 高层事件推送：SSE 与长轮询（BS-11）

## 目标
多人同时编辑时，各自的界面能实时看到别人的修改。

## 参考
- BS-11
- ADR-007
- 旧事件类型：`../webprotege/webprotege-shared/src/main/java/edu/stanford/bmir/protege/web/shared/event/`

## 产出
- `wprebuild.app.events.ProjectEvent`：`sealed interface`，实现为 BS-11 表中的 5 种事件（record），每个事件带 `revNo`。
- `wprebuild.app.events.EventTranslator`：监听 `RevisionCommittedEvent`，读取各层级视图的 `lastDelta()` 与显示名称视图的 `lastChanged()`，再根据变更的主语计算 `EntityFrameChanged`；静默修订只产生 `ProjectChanged`。`PermissionsChanged` 来自 `access` 模块在角色分配变化时发布的 Spring 事件 `RoleAssignmentsChanged`（由 P3-22 发布），`events` 模块监听后转换。
- `wprebuild.app.events.ProjectEventHub`：每个项目维护订阅者列表，以及最近 N 个修订的事件环形缓冲（默认 N = 200）。
- `GET /api/projects/{id}/events`（`text/event-stream`，需要 `VIEW_PROJECT`）：事件 `id` 为修订号，`event` 为事件类型名，`data` 为 JSON；带 `Last-Event-ID` 时先补发之后的事件，超出缓冲范围时发送一个 `resync` 事件，提示客户端整页刷新；每 25 秒发送一次心跳注释行。
- `GET /api/projects/{id}/events/poll?after=<修订号>&timeout=25`（`application/json`，需要 `VIEW_PROJECT`，供 uni-app App 端使用，ADR-007）：有修订号大于 `after` 的事件时立即返回事件数组；否则最多等待 `timeout` 秒（上限 30）后返回空数组；`after` 超出缓冲范围时返回一个 `resync` 事件。等待使用异步请求处理（`DeferredResult`），不得占用请求线程睡眠。
- 测试 `EventsTest`：订阅后创建类，依次收到 `ProjectChanged`、`EntityHierarchyChanged`、`BrowserTextChanged`；带 `Last-Event-ID` 重连后能补齐；超出缓冲范围时收到 `resync`；无权限用户得到 403；长轮询在无事件时于超时后返回空数组，在等待期间提交新修订时立即返回该事件。

## 实现要点
- `EventTranslator` 必须在 `RevisionCommittedEvent` 的同步监听中读取视图增量（此时仍持有生成锁，见 P1-11）；推送给订阅者可以异步进行。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口；附加：`concurrency`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P3-21` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：审查重点：锁顺序（ADR-006）、WP-1 ~ WP-8（docs/01-rea/write-path.md）。

## 验收
```bash
./mvnw -q -pl wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=EventsTest
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 评论、关注等 P6 事件
- WebSocket
