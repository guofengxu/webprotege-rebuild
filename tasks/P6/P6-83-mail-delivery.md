---
id: P6-83
title: 邮件投递基础：spring-boot-starter-mail、mail_deliveries（V12）与认领/重试投递线程
phase: P6
module: wp-app/integration
type: impl
size: M
work_type: app-service
ecc_extra: [security]
status: todo
depends_on: [P6-45, P6-74]
---

# P6-83 邮件投递基础

## 目标
应用具备可配置的 SMTP 发送能力与至少一次语义的邮件投递队列；关注（及日后讨论）只需登记投递，不在业务事务里直连 SMTP。

## 参考
- BS-22；EPIC「关注（P6-45）」决定第 2 条；投递模式对齐 P6-21（登记 + 认领 + 租期 + 指数退避）
- 旧出处：`LEG/mail/SendMailImpl.java`（行为参照，不复制）
- **新依赖**：`spring-boot-starter-mail`（Spring Boot 4.1.1 管理版本）。理由：JDK 无内置 SMTP 客户端；与现有 Boot 栈对齐；讨论邮件可复用。

## 产出
- `backend/wp-app/pom.xml`：增加 `spring-boot-starter-mail`（不升 Spring Boot / OWL API）。
- `backend/wp-app/src/main/resources/db/migration/V12__mail_deliveries.sql`：`mail_deliveries` 表（至少含 delivery_id、idempotency 键、to_address、subject、body_html、status、attempts、next_attempt_at、claim_token、last_error、created_at、finished_at；唯一约束防止同一业务键重复登记）。
- `integration`：`MailProperties`（`wp.mail.enabled` 默认 false、host/port/auth/from、poll/lease/max-attempts/backoff）、`MailDeliveryRepository`（JDBC 登记/认领/写回）、`MailDispatcher`（守护轮询 + 虚拟线程发送；发送时不持有 DB 事务）、`MailEnqueue`（供其它模块登记 PENDING）。
- 配置项与运维说明写入 `docs/api/README.md` 邮件小节（无 SMTP 时的行为、至少一次语义、日志不打印密码）。
- `wp.mail.enabled=false` 或未配置 SMTP：登记为 **`SKIPPED`**（不阻塞调用方 / outbox）；投递线程可不启动或空转。
- 测试：优先注入假 `JavaMailSender`（默认）；登记后投递成功；失败重试至放弃；关闭时为 `SKIPPED`；双实例认领不重复成功发送。口令只来自环境配置；日志不打印密码。不另钉邮件库版本（跟 Boot BOM）。

## 实现要点
- 依赖 P6-74 **只为** Flyway V11→V12 串行。
- 本卡不实现关注匹配、不发关注业务邮件模板。
- 收件人字段按地址存储；业务侧保证一人一封（BS-22）。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口；附加：security。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-83` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`、`security-review`、`database-migrations`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`、`security-reviewer`、`database-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='MailDeliveryTest,ModularityTest,CoreSchemaTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 关注表、匹配、REST、Web、讨论邮件正文
