---
id: P6-88
title: 关注通知 outbox 消费者：按修订匹配关注并登记邮件投递
phase: P6
module: wp-app/watch
type: impl
size: M
work_type: app-service
ecc_extra: [concurrency, security]
status: todo
depends_on: [P6-45, P6-84, P6-83, P6-21]
---

# P6-88 关注变更邮件登记

## 目标
每个新修订提交后，匹配到的关注者（排除作者）在邮件队列中各有一条登记；匹配需读内存层级，模型未就绪时稍后重试；大修订不拖住其它 outbox 消费者。

## 参考
- BS-21、BS-22（受影响实体、大修订上限、邮件内容、收件人）；`watches.md` §3、§7、§8
- 主语规则的现有实现：`events/EventTranslator.frameSubjects`（`AxiomSubjectProviderEx` + IRI 解析）
- P6-12 `projection/ProjectionOutboxConsumer`（`RevisionLog.read`、`gate().read`、`OutboxRetryLater` 的用法）；P6-21 登记模式；P6-83 `MailEnqueue` / `MailProperties.linkBaseUrl()`

## 产出
- 受影响实体：调用 P6-84 的 `WatchSubjects`，传入**匹配时的内存本体**（head ≥ 该修订，见 BS-21 的近似说明）；本卡不重写主语规则。
- `WatchMatcher`：给定受影响实体、项目全部关注与项目运行时 → 按用户聚合命中实体（ENTITY 只匹配自身；BRANCH 匹配自身与相关祖先；个体的相关集合含类型类自身及其祖先，见 BS-21）。求祖先用层级视图 `parents`，带缓存；只与“已被关注的实体”求交。
- `WatchProperties`（`wp.watch`）：`max-subjects-per-revision`（默认 5000）。超过时只做直接匹配并记 WARN（BS-22）。
- `WatchNotificationOutboxConsumer`（`OutboxConsumer`，`name()`=`"watch-mail"`，`startFrom()`=`LATEST`）：批内按 outbox id 顺序逐条处理 `REVISION_COMMITTED`；`bus.ProjectExistenceCheck.state()` 为 `MISSING` / `TRASHED` 时跳过并确认（同 `ProjectionOutboxConsumer`；`ProjectRepository` 只用于取显示名）；每修订、每收件人登记一封；幂等键 = `watch-mail:<outboxId>:<userId>`。
- 邮件内容：主题与正文含项目显示名（`ProjectRepository.find`）、作者显示名、命中实体显示名列表（上限 50，其余只计条数）；`linkBaseUrl` 已配置时附项目与实体链接（`/projects/<id>` 与 Web 工作区的 `?entity=<IRI>` 参数；IRI 按查询参数 URL 编码，`href` 属性值再做 HTML 属性转义），未配置时不含链接。
- 测试 `WatchMatcherTest`：BRANCH 命中子孙；ENTITY 不因祖先误命中；**类 C 的直接实例变更命中 C 上的 BRANCH**；已删除实体命中其 ENTITY 关注；超过主语阈值时只做直接匹配。（主语规则本身由 P6-84 的 `WatchSubjectsTest` 覆盖。）
- 测试 `WatchNotificationOutboxConsumerTest`：作者被排除；无 `VIEW_PROJECT` / 无邮箱跳过；同修订多实体一封；模型滞后：滞后修订是本批第一条时抛 `OutboxRetryLater` 且进度不前进；不是第一条时返回其前一条的 id（部分确认），之前的修订已登记并确认、滞后修订不前进；项目无关注时不读修订内容即确认；邮件关闭时登记 `SKIPPED` 且照常确认；重放同一批不重复登记；显示名中的 CR/LF 不进入主题、HTML 已转义；IRI 含 `#`、`&`、`"` 时链接经 URL 编码与属性转义；只改 `rdfs:label` 的修订也登记邮件。

## 实现要点
- **处理顺序与锁**（避免在项目读锁内做 DB I/O）：
  1. 读本项目全部关注（`WatchRepository`）；为空则确认该条，结束。
  2. 用 `RevisionLog.read(projectId, rev, rev)` 读出变更与作者（锁外）。
  3. `handle.gate().read(...)`：运行时损坏抛 `IllegalStateException`；`head < revNo` 时按下文「部分确认」处理（只有本批第一条才抛 `OutboxRetryLater`）；在锁内求主语、祖先与显示名，把“用户 → 命中实体（IRI、类型、显示名）”复制到本地结构后释放。
  4. 锁外过滤收件人（`AccessService` 校验 `VIEW_PROJECT`；`UserService.findById` → `UserAccount.email()` 为空则跳过；等于作者则跳过），再 `MailEnqueue`（加入**当前**中继事务，禁止 `REQUIRES_NEW`）。
- 禁止取生成锁、写锁，禁止调用 `ChangePipeline`。
- **部分确认**（`OutboxRelay` 已支持返回值小于批末 id）：遇到 `head < revNo` 的消息时，若本批已处理过消息，返回该消息前一条的 id；只有它是本批第一条时才抛 `OutboxRetryLater`（整批回滚、200 ms 后重试）。这样滞后修订之前的消息不会每次被重新匹配。幂等键保证重放不重复登记，幂等冲突视为成功（P6-83 的 `on conflict do nothing`）。
- 邮件关闭或无 SMTP 主机：由 `MailEnqueue` 登记 `SKIPPED`，本消费者照常确认，不得 `OutboxRetryLater`。
- **不用** `revision_subjects`，不修改 `revision`、`ontology`、`events` 模块的公共接口，不改 `wp-kernel`。
- 主语规则（`WatchSubjects`）已移到 P6-84，本卡只含匹配、消费者与邮件拼装。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口；附加：`concurrency`、`security`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-88` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`、`security-review`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`、`silent-failure-hunter`、`security-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：审查重点：锁顺序（ADR-006）、WP-1 ~ WP-8（docs/01-rea/write-path.md）。

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='WatchMatcherTest,WatchNotificationOutboxConsumerTest,OutboxRelayTest,ModularityTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- SMTP 发送与重试（P6-87）；邮件队列表结构（P6-83）；Web；讨论邮件；关注变更历史 API
