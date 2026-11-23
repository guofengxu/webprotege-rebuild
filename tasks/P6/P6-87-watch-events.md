---
id: P6-87
title: 关注高层事件：WatchAdded/WatchRemoved 进入 ProjectEventHub 与 api-types
phase: P6
module: wp-app/events
type: impl
size: S
work_type: app-service
ecc_extra: []
status: todo
depends_on: [P6-45, P6-77, P6-86]
---

# P6-87 关注项目事件

## 目标
关注增删后，已连接的客户端能通过现有 SSE / 长轮询收到 `WatchAdded` / `WatchRemoved`（`revNo` = 当前 head）。

## 参考
- BS-11、BS-21；EPIC「关注（P6-45）」；对齐 P6-77 讨论事件做法

## 产出
- 扩展密封 `ProjectEvent`：`WatchAdded`、`WatchRemoved`（载荷：userId、实体、watchType）。
- `EventTranslator`（或等价）监听 watch 域事件并 `ProjectEventHub.publish`。
- 扩展 `ProjectEventHub.takeWaiters`：对 `WatchAdded` / `WatchRemoved` 在 `revNo >= after` 时唤醒（与 `PermissionsChanged` 同模式；若 P6-77 已做讨论事件同 head 唤醒，本卡并入同一逻辑）。硬依赖 P6-77 对事件基础设施的改动，**无**“改挂 OpenAPI 链尾”回退。
- OpenAPI / `@wp/api-types` 事件联合类型更新。
- 测试：设置关注后订阅方收到事件；`revNo` 等于当时 head。

## 实现要点
- 关注事件**不**进项目 webhook。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-87` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='WatchEventsTest,OpenApiResponseSchemasTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- Web `applyProjectEvent` 穷尽分支（P6-89）；邮件
