---
id: P6-03
title: 后端：成员失去查看权限或项目移入回收站时，关闭对应的事件订阅
phase: P6
module: wp-app
type: impl
size: M
work_type: app-service
ecc_extra: [concurrency]
status: todo
depends_on: [P4-17]
---

# P6-03 事件订阅随权限撤销与回收站失效

## 目标
成员被移出项目（或改成不能查看的角色）后，他已经打开的 SSE 连接和挂起的长轮询会被服务端关闭；项目移入回收站后，所有订阅者先收到 `resync`，然后连接被关闭。重连时会按当前权限得到 403 或 409，不会再收到该项目的事件。对应 M4 差距清单第 5 项。

## 参考
- `docs/03-plan/m4-acceptance.md`「差距清单」第 5 项（以及第 11 步必须 `reload` 的原因）
- BS-11；ADR-007；P3-21 卡片与执行记录
- `events/ProjectEventHub.java`（类注释中的锁顺序：生成锁 → hub 锁；`publish` / `subscribe` / `park` / `resync`；单线程投递 `wp-events-delivery`）
- `events/EventTranslator.java`：`onRoleAssignments` 在生成锁内发布 `PermissionsChanged`
- `web/ProjectEventsController.java`：`open` / `poll`、`SseSession.onShutdown()`
- `access/AccessService.java`：`can(UserId, ProjectId, Action)`；`publish()` 中的“事务提交后发布”写法
- `project/MoveProjectToTrashHandler.java`、`project/RestoreProjectFromTrashHandler.java`、`project/JdbcProjectRepository.java#setTrash`
- `docs/02-architecture/overview.md` 第 2 节（模块依赖矩阵）
- Web 端：`frontend/wp-web/src/features/events/useProjectEvents.ts` 在重连得到 401 / 403 / 404 / 409 时停止重连并显示原因（P4-29），所以本卡不需要改 Web 端

## 产出
- `events/ProjectEventHub.java`：
  - `subscribe(ProjectId, UserId, OptionalLong, LiveSink)`、`park(ProjectId, UserId, long, Consumer<Delivery>)`：增加 `UserId` 参数，并记在 `Live` / `Waiter` 中。`ProjectEventsController` 传入当前用户。
  - 新增 `public Set<UserId> subscribers(ProjectId projectId)`：返回该项目当前 SSE 订阅与挂起长轮询的用户集合（快照）。
  - 新增 `public void disconnect(ProjectId projectId, Set<UserId> users)` 与 `public void disconnectAll(ProjectId projectId)`：在 hub 锁内摘除匹配的订阅与轮询，然后把关闭动作**提交到同一个投递线程**，排在此前已经提交的投递（包括 `PermissionsChanged` 与 `resync`）之后执行。SSE 调用 `LiveSink.onShutdown()` 结束流；挂起的长轮询以空数组完成。
- `events/SubscriptionRevoker.java`（`@Component`，本身不监听事件）：`void revoke(ProjectId projectId)` 读取 `hub.subscribers(projectId)`，在**不持有 hub 锁、也不持有生成锁**的情况下，对每个用户调用 `AccessService.can(user, projectId, Action.VIEW_PROJECT)`，然后对失去权限的用户调用 `hub.disconnect(projectId, revoked)`。
- `events/EventTranslator.java`：`onRoleAssignments` 在 `gate().generate(...)` 返回之后（此时不持有任何锁）直接调用 `SubscriptionRevoker.revoke(projectId)`。这样 `PermissionsChanged` 一定先进入投递队列，不依赖监听器的执行顺序。
- `web/ProjectEventsController.java`：`open` 在 `hub.subscribe` 返回之后、`poll` 在 `hub.park` 返回之后，再经命令总线执行一次 `GetProjectEvents`；失败时关闭刚建立的订阅（或取消挂起的轮询），并按异常返回 403 / 409。原因：第一次校验与登记订阅不是原子的；撤销如果恰好发生在两者之间，撤销方取快照时还看不到这个订阅。
- `project/ProjectTrashStateChanged.java`：`public record ProjectTrashStateChanged(ProjectId projectId, boolean trashed)`。在 `JdbcProjectRepository.setTrash` 中，于事务提交后发布；没有事务同步时直接发布。写法与 `AccessService.publish` 相同，状态没有变化时不发布。
- `events/ProjectTrashListener.java`（`@Component`）：收到 `trashed=true` 时先调用 `hub.resync(projectId)`，再调用 `hub.disconnectAll(projectId)`；`trashed=false` 时不做任何事。
- `events/package-info.java`：`allowedDependencies` 增加 `"project"`。同步修改 `docs/02-architecture/overview.md` 第 2 节表格中 `events` 行的“允许依赖”。`project` 不依赖 `events`，不会成环。
- 测试 `web/EventSubscriptionRevocationTest.java`（Testcontainers；读取 SSE 的方式沿用 `EventsTest`）：
  - A（`CAN_MANAGE`）与 B（`CAN_VIEW`）都打开 SSE。A 移除 B 后：A 收到 `PermissionsChanged`，连接保持；B 先收到 `PermissionsChanged`，随后流在 5 秒内结束；B 重连得到 403。
  - 把 B 改为 `CAN_EDIT`（仍能查看）：B 的连接保持。
  - B 挂起一次长轮询后被移除：该轮询在 5 秒内返回（`PermissionsChanged` 或空数组），下一次轮询得到 403。
  - B 同时订阅另一个项目 P2：在项目 P1 中被移除后，P2 上的连接不受影响。
  - A 把项目移入回收站：A、B 的 SSE 都先收到 `resync`，然后流结束；之后连接或轮询返回 409 `PROJECT_TRASHED`。项目恢复后可以重新订阅。
  - 交错：B 通过第一次校验之后、登记订阅之前，B 被移除（测试中直接调用 `AccessService.removeRole`，再调用控制器的登记路径，或用可注入的测试钩子制造这个顺序）：第二次校验失败，B 得到 403，hub 中不留下 B 的订阅。
- 回归：`EventsTest`、`SharingTest`、`ProjectLifecycleTest`、`BackendAcceptanceTest` 仍然通过（更新调用 `subscribe` / `park` 的地方）。

## 实现要点
- 锁顺序沿用 P3-21：生成锁 → hub 锁。`AccessService` 会查数据库，不得在持有任何一把锁时调用它。关闭 SSE 的写操作也不得在 hub 锁内执行。
- 不新增事件类型。`frontend/wp-web/src/features/events/applyProjectEvent.ts` 对事件联合类型做了穷尽检查，新增类型会让 wp-web 的类型检查失败。所以回收站沿用 `resync`，被移出沿用 `PermissionsChanged` 加断开连接，`docs/api/openapi.json` 不变。
- 竞态：`RoleAssignmentsChanged` 与 `ProjectTrashStateChanged` 都在事务提交后才发布。订阅登记之后的第二次校验要么看到新状态（自己关闭），要么撤销方取快照时已经能看到这个订阅（由撤销方关闭），两者必居其一，所以不会漏掉。
- 从撤销提交到断开连接之间，已经进入该订阅者投递队列的事件仍会发出。这个窗口很短，可以接受，在 `SubscriptionRevoker` 的 Javadoc 中写明。
- 系统管理员不受 `can()` 之外的特殊处理：他们按 `CAN_MANAGE` 判定，不会被断开。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口；附加：`concurrency`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-03` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：审查重点：锁顺序（ADR-006）、WP-1 ~ WP-8（docs/01-rea/write-path.md）。

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='EventSubscriptionRevocationTest,EventsTest,SharingTest,ProjectLifecycleTest,BackendAcceptanceTest,ModularityTest,OpenApiContractTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 新的事件类型；项目从回收站恢复时的通知
- Web 端改动（重连得到 403 / 409 时的处理已在 P4-29 完成）
- 项目详情接口的缓存时间
- 移动端（P5 已暂停）
