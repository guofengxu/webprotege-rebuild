---
id: P6-96
title: 标签高层事件：ProjectTagsChanged / EntityTagsChanged 进入 ProjectEventHub 与 api-types
phase: P6
module: wp-app/events
type: impl
size: S
work_type: app-service
ecc_extra: []
status: todo
depends_on: [P6-46, P6-77, P6-94, P6-95]
---

# P6-96 标签项目事件

## 目标
项目标签目录或实体显式标签变更后，已连接客户端能通过 SSE / 长轮询收到 `ProjectTagsChanged` / `EntityTagsChanged`（`revNo` = 当前 head）。

## 参考
- BS-11、BS-24；EPIC「标签（P6-46）」；对齐 P6-77（关注审查修订后关注不发项目事件，原 P6-87 已改为邮件投递线程）

## 产出
- 扩展密封 `ProjectEvent`：`ProjectTagsChanged`、`EntityTagsChanged`（载荷见 BS-24）。
- `EventTranslator` 监听 tag 域事件并 `ProjectEventHub.publish`。
- `ProjectEventHub.takeWaiters`：对上述事件在 `revNo >= after` 时唤醒（与 PermissionsChanged / 讨论同模式）。硬依赖 P6-77 对同 head 非修订事件唤醒的改动。
- OpenAPI / `@wp/api-types` 事件联合类型更新。
- 测试：改目录 / 改实体标签后订阅方收到事件；`revNo` 等于当时 head；整表替换删除仍被实体使用的标签 → 同时收到 `ProjectTagsChanged` 与受影响实体的 `EntityTagsChanged`（剥离后显式集合）。

## 实现要点
- 标签事件**不**进项目 webhook。
- 首期不发「条件命中变化」事件。
- 依赖 P6-95 **只为** OpenAPI 串行；扩展 `ProjectEvent` 后应与 P6-97 紧邻合并，以免长时间打断 Web 穷尽检查。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-96` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='TagEventsTest,OpenApiResponseSchemasTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- Web `applyProjectEvent` 穷尽分支（P6-97）
