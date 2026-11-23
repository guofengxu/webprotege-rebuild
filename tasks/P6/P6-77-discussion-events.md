---
id: P6-77
title: 事件：讨论域事件接入 ProjectEvent / SSE，并扩展 api-types 联合类型
phase: P6
module: wp-app/events
type: impl
size: M
work_type: app-service
ecc_extra: []
status: todo
depends_on: [P6-76, P6-03]
---

# P6-77 讨论事件接入 SSE

## 目标
讨论写操作产生的域事件进入 `ProjectEventHub`（SSE / 长轮询）；OpenAPI 与 `@wp/api-types` 的 `ProjectEvent` 联合类型包含 BS-20 五类讨论事件。

## 参考
- BS-11、BS-20；ADR-007；`PermissionsChanged` / `RoleAssignmentsChanged` 模式
- EPIC「讨论（P6-44）」决定第 5 条
- `ProjectEvent.java`、`EventTranslator.java`、`ResponseSchemasCustomizer.java`、`frontend/packages/api-types`

## 产出
- 扩展密封 `ProjectEvent`：`DiscussionThreadCreated`、`CommentPosted`、`CommentUpdated`、`CommentDeleted`、`DiscussionThreadStatusChanged`（字段对齐 BS-20；均含 `type`、`revNo`）。
- `EventTranslator`（或专用监听器）：监听 P6-76 域事件，在生成锁内取 `head` 为 `revNo`，`hub.publish`。
- OpenAPI 事件 schema + `OpenApiResponseSchemasTest`；再生 `@wp/api-types` 的 `ProjectEvent` 联合（更新注释“五种”→含讨论）。
- 集成测试：发表评论后 SSE / poll 收到对应 `type`，且 `revNo` 等于当前 head。

## 实现要点
- 不产生修订；不投递 webhook。
- OpenAPI 串行接在 P6-76 之后。
- **同 head 唤醒**：`ProjectEventHub.takeWaiters` 今日只对 `PermissionsChanged` 在 `revNo >= waiter.after` 时唤醒长轮询。讨论事件同样使用当前 head，必须把该特例**推广**为“非修订、共享 head 的事件”（或显式列出全部讨论 `ProjectEvent` 类型），否则 poll 停在 head 时收不到评论。测试须覆盖：waiter `after == head` 时发布讨论事件仍能返回。
- Web `applyProjectEvent` 穷尽分支由 P6-78 补齐；本卡合并进 main 前须与 P6-78 同批或紧随其后，避免 wp-web `never` 穷尽检查失败。建议合并顺序 P6-77 → P6-78。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-77` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='DiscussionEventsTest,OpenApiResponseSchemasTest,EventsTest'
pnpm -C frontend --filter @wp/api-types run check
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- Web UI 面板、讨论 REST 业务本身、邮件
