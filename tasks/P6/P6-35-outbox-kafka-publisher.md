---
id: P6-35
title: outbox 发布器 → Kafka：把每个修订事件按项目分区、按提交顺序发布到 Kafka 主题（默认关闭）
phase: P6
module: wp-app
type: impl
size: M
work_type: app-service
ecc_extra: [concurrency]
status: todo
depends_on: [P6-21, P6-51]
---

# P6-35 outbox 发布器 → Kafka

## 目标
开启后，outbox 中的每一行都被发布到一个 Kafka 主题：同一项目的消息进入同一分区、按提交顺序排列；至少一次投递，消息带 outbox ID 供下游去重。其它系统（实例库、数据平台、通知服务）因此可以订阅本体修订，而不必访问编辑器的数据库。

## 参考
- `tasks/P6/EPIC.md` 候选项“outbox 发布器 → Kafka”
- P6-08：`revision/OutboxConsumer`（`accept` 在中继事务内执行；抛异常则回滚并按消费者指数退避；`OutboxConsumer` Javadoc 中“有外部副作用的消费者必须自己保证幂等”）、`revision/OutboxMessage`
- P6-20 / P6-21：`integration` 模块与 `WebhookOutboxConsumer`（同一模块中消费者的写法）
- ADR-013（实例库通过 outbox 消费编辑器的修订）

## 产出
- 依赖：`backend/wp-app/pom.xml` 增加 Spring Boot 4 的 Kafka 支持（`spring-boot-starter-kafka`；以 `spring-boot-dependencies` 4.1 BOM 中的实际名称为准，BOM 中没有 starter 时直接使用它管理的 `org.springframework.kafka:spring-kafka`），测试依赖 `org.testcontainers:testcontainers-kafka`（版本由现有的 Testcontainers 版本管理决定）。不引入其它第三方库。
- `integration/KafkaPublisherProperties.java`：`wp.kafka.enabled`（默认 `false`）、`wp.kafka.topic`（默认 `wp.revisions`）、`wp.kafka.start-from`（`LATEST` / `EARLIEST`，默认 `LATEST`）、`wp.kafka.send-timeout`（默认 10 秒）；连接参数使用标准的 `spring.kafka.*`。
- `integration/KafkaOutboxConsumer.java`（只在 `wp.kafka.enabled=true` 时注册；`name()` 为 `"kafka"`，`startFrom()` 取配置）：
  - 本批每一行发布一条消息：key 为 `projectId`；value 为 JSON `{ outboxId, type, projectId, revNo, payload, createdAt }`（`payload` 原样嵌入 outbox 的 JSON）；消息头 `wp-outbox-id`、`wp-event-type`；
  - 依次发送整批，`flush` 后等待全部结果（`send-timeout`）；任一失败则抛出异常，由中继回滚并退避，下一次重发整批；全部成功后返回本批最后一条的 `id`。
- 生产者配置（`integration/KafkaPublisherConfig.java`）：`enable.idempotence=true`、`acks=all`，键与值都用字符串序列化；`max.block.ms` 与 `delivery.timeout.ms` 都不超过 `send-timeout`（`request.timeout.ms` 相应调小），保证一次 `accept` 最多阻塞约 `send-timeout`；不使用事务型生产者。
- `docs/api/kafka.md`：主题与消息格式、分区与顺序保证（同一项目有序，跨项目无序）、至少一次语义与按 `outboxId` 去重、开启方式与 `start-from` 的含义、broker 不可用时的行为（中继对该消费者退避；中继是单线程依次处理各消费者，所以每个退避周期里其它消费者最多被推迟约 `send-timeout`）。
- 测试 `integration/KafkaPublisherTest.java`（Testcontainers：PostgreSQL + Kafka，`apache/kafka` 官方镜像固定到具体版本号，不用 `latest`）：
  - 同一项目连续 3 个修订：主题中按顺序有 3 条消息，key 相同，`outboxId` 与 `revNo` 递增，value 字段齐全；两个项目的消息 key 不同；
  - broker 暂停期间编辑：`outbox_consumers` 中 `kafka` 的 `last_id` 不前进；webhook 消费者仍然前进，每条修订被登记的延迟不超过 `send-timeout` 加两个轮询间隔（测试中把 `send-timeout` 设为 2 秒）；broker 恢复后消息全部到达，按 `outboxId` 去重后没有缺口；
  - `start-from=LATEST`：开启前的修订不发布；`EARLIEST`：全部历史行都发布；
  - 未开启时没有 `KafkaOutboxConsumer` bean，`outbox_consumers` 中没有 `kafka` 行。

## 实现要点
- 失败时整批重发会产生重复消息，这是至少一次语义的正常结果；不要为了“恰好一次”引入 Kafka 事务（outbox 与 Kafka 无法放在同一个事务中，真正的去重只能在下游按 `outboxId` 做）。
- 中继事务在等待 Kafka 结果期间保持打开，`send-timeout` 不宜过长；在 Javadoc 中说明这一取舍。
- 主题不由应用创建（生产环境由运维建好分区数与保留策略）；测试中由测试代码创建主题。
- 依赖 P6-51 只是为了串行修改 `backend/wp-app/pom.xml` 的依赖列表；依赖 P6-21 是因为两者都在 `integration` 模块中添加消费者与配置类。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口；附加：`concurrency`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-35` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：审查重点：锁顺序（ADR-006）、WP-1 ~ WP-8（docs/01-rea/write-path.md）。

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='KafkaPublisherTest,OutboxRelayTest,ModularityTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- Kafka 消费端（实例库或其它系统订阅，见 P6-34）
- 修订内容（公理变更）随消息发布：消息只携带 outbox 载荷，下游需要变更明细时调用修订差异接口
- 图数据库发布器（P6-36）、Schema Registry / Avro
- 中继按消费者分线程（属于 `revision` 模块，需要时另开卡）
