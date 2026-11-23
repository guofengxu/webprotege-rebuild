---
id: P6-88
title: 关注通知 outbox 消费者：按修订匹配关注并登记邮件投递
phase: P6
module: wp-app/watch
type: impl
size: M
work_type: app-service
ecc_extra: [concurrency]
status: todo
depends_on: [P6-45, P6-84, P6-83, P6-21]
---

# P6-88 关注变更邮件登记

## 目标
每个新修订提交后，匹配到的关注者（排除作者）在邮件队列中各有一条投递；匹配需读内存层级，模型未就绪时稍后重试。

## 参考
- BS-21、BS-22；`watches.md` §3；P6-12 `OutboxRetryLater`；P6-21 登记模式；P6-83 `MailEnqueue`

## 产出
- `WatchMatcher`（或等价）：给定项目 + 受影响实体集合 + 关注记录 → 按用户聚合命中实体（ENTITY/BRANCH/个体祖先规则）。
- 若尚无按修订读主语：在 `revision/RevisionHistoryQueries`（及 JDBC 实现）**最小增补** `subjectsInRevision(projectId, revNo)`（读 `revision_subjects`）。
- `WatchNotificationOutboxConsumer`（`OutboxConsumer`，建议 `name()`=`"watch-mail"`，`startFrom()`=`LATEST`）：对 `REVISION_COMMITTED`，若项目 ACTIVE：确认运行时 head ≥ 该修订否则 `OutboxRetryLater`；求受影响实体；匹配；过滤无 `VIEW_PROJECT`/无邮箱/等于作者；每人登记一封（主题/正文含项目名、作者、实体列表与链接）；幂等键含 outbox_id+userId。
- 消费者内**不**调用 SMTP；只 `MailEnqueue`（参数含 to 地址或 userId+由 integration/access 解析——本卡用 access 解析后传地址）。
- 认领/重试细节复用 P6-83 / P6-21 的租约与 `claim_token`，不另设计。
- 测试：BRANCH 命中子孙；ENTITY 不因祖先误命中；**命名个体变更命中其命名类型类上的 BRANCH 关注（含该类自身）**；仅改实体注解（label）也触发；作者被排除；同修订多实体一封；模型滞后抛 `OutboxRetryLater` 且进度不前进；无关注时快速确认。

## 实现要点
- **锁顺序**：在 `accept` 中继事务内，对项目**只取读锁**：确认 `head >= revNo`，抽取受影响实体并完成匹配，把“用户 → 命中实体列表”复制到本地结构后**释放读锁**；然后再 `MailEnqueue`（加入**当前**中继事务，禁止 `REQUIRES_NEW`）。禁止生成锁、写锁与 `ChangePipeline`。
- 先幂等登记邮件，再前进该消费者进度；模型未到该修订只抛 `OutboxRetryLater`，不得先前进。幂等冲突视为成功。
- `wp.mail.enabled=false` / 未配置 SMTP：按 P6-83 记 `SKIPPED`，outbox **照常确认**，不得 `OutboxRetryLater`。
- 受影响实体：见 BS-22；**优先读 `revision_subjects`**（与 `AffectedSubjects` 同语义）；不依赖 `projection`；不信 outbox JSON 实体列表。
- 匹配：见 BS-21；祖先用现有层级 `parents` / `pathsToRoot` 与类断言在 `watch` 内完成，**不**改 `wp-kernel`。
- 邮箱：`access.UserService.findById` → `UserAccount.email()`；无邮箱跳过。
- 正文实体列表设上限（例如 50，其余只计条数）；显示名写入 HTML/主题前转义并去掉 CR/LF。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口；附加：concurrency。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-88` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：审查重点：锁顺序（ADR-006）、WP-1 ~ WP-8（docs/01-rea/write-path.md）。

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='WatchMatcherTest,WatchNotificationOutboxConsumerTest,OutboxRelayTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- SMTP 配置细节（P6-83）；Web；讨论邮件
