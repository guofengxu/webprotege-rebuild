---
id: P6-87
title: 邮件投递线程：spring-boot-starter-mail、SMTP 配置与认领/重试发送
phase: P6
module: wp-app/integration
type: impl
size: M
work_type: app-service
ecc_extra: [security]
status: todo
depends_on: [P6-45, P6-83]
---

# P6-87 邮件投递线程

## 目标
应用按 `wp.mail.*` 连接 SMTP，把 `mail_deliveries` 中的 `PENDING` 投递认领后发送，失败按指数退避重试至放弃；多个线程或实例并发时同一投递只成功发送一次。

## 参考
- BS-22（投递）；EPIC「关注（P6-45）」决定第 2 条与「审查修订（2026-11-24）」
- 投递模式对齐 P6-21：`integration/WebhookDispatcher.java`（守护轮询 + 认领 + 租期 + `claim_token` + 指数退避，发送时不持有 DB 事务）
- 旧出处：`LEG/mail/SendMailImpl.java`（行为参照，不复制）
- **新依赖**：`spring-boot-starter-mail`（版本由 Spring Boot 4.1.1 BOM 管理，不另钉）。理由：JDK 没有内置 SMTP 客户端；这是与现有 Boot 栈对齐的官方 starter；讨论邮件日后复用同一依赖。

## 产出
- `backend/wp-app/pom.xml`：增加 `spring-boot-starter-mail`（不升 Spring Boot / OWL API）。
- `MailProperties` 增补：`port`、`username`、`password`、`starttls`、`poll-interval`、`lease`、`max-attempts`、`backoff`（初值与上限）；`password` 不出现在 `toString` 与日志中。
- `JavaMailSender` 由 `IntegrationConfig` 按 `wp.mail.*` 构造（不使用 `spring.mail.*` 自动配置，保持一套配置）；`enabled=false` 或主机为空时不创建发送 bean，投递线程不启动。
- `MailDeliveryRepository` 增补认领（`for update skip locked` + 租期 + `claim_token`）与写回（成功、失败重排、放弃为 `FAILED`），写回校验 `claim_token`。
- `MailDispatcher`：守护轮询 + 虚拟线程发送；单 `To`、`From` 取 `wp.mail.from`；发送期间不持有 DB 事务；`SKIPPED` 行永不发送。
- `docs/api/README.md` 增加“邮件”小节：配置项、无 SMTP 时登记为 `SKIPPED`、至少一次语义（可能重复发送）、日志不打印口令。
- 测试 `MailDispatcherTest`（注入假 `JavaMailSender`）：发送成功 → `SUCCEEDED`；连续失败按退避重试，达到上限 → `FAILED` 并记 `last_error`；两个 dispatcher 并发认领时每条只发送一次；`SKIPPED` 不发送；租期过期的认领可被重新认领；日志与 `toString` 不含口令。

## 实现要点
- 口令只来自环境配置；不写入仓库中的配置文件。
- 不改 `MailEnqueue` 的签名与语义（P6-83）。
- 不实现任何业务邮件模板。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口；附加：security。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-87` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`、`security-review`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`、`security-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='MailDispatcherTest,MailEnqueueTest,ModularityTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- `mail_deliveries` 表结构与 `MailEnqueue`（P6-83）；关注匹配与邮件正文（P6-88）；讨论邮件；历史行清理
