---
id: P6-31
title: 实例库 PoC（一）：新建 wp-instances 模块，instances / links 表结构、带有效期的写入与追溯查询
phase: P6
module: wp-instances
type: impl
size: M
work_type: db
ecc_extra: []
status: todo
depends_on: [P6-11]
---

# P6-31 实例库 PoC（一）：wp-instances 模块、表结构与写入

## 目标
仓库里多一个独立的 Maven 模块 `backend/wp-instances`：一个纯 Java + JDBC 的实例库内核，使用自己的数据库与 Flyway 迁移，按 ADR-013 的 `instances` + `links` 模型保存运行期实例，支持按时间点查询（有效期）和多跳追溯查询。它是实例库 PoC 的地基，P6-32 用它做规模基准，P6-33 在它上面加本体校验。

## 参考
- ADR-013 第 4 节：独立数据库（例如 `wp_instances`）；`instances(iri, class_iri, attributes jsonb, valid_from, valid_to, source, ontology_rev)` + `links(subject_iri, property_iri, object_iri, valid_from, valid_to)`；1 ~ 3 跳用递归 CTE
- `docs/02-architecture/overview.md` 第 1 节（仓库结构）；ADR-003（迁移工具独立模块的先例：模块之间不共享 jar）
- P6-09 / P6-11：投影表 PoC 的拆法与 Testcontainers 用法（`postgres:18`）
- `backend/wp-app/pom.xml`：`spring-boot-dependencies` BOM 的导入方式与 `dependencyConvergence` 规则

## 产出
- 根 `pom.xml`：`<modules>` 增加 `backend/wp-instances`。
- `backend/wp-instances/pom.xml`（artifactId `wp-instances`）：
  - 运行时依赖只有 `org.postgresql:postgresql`、`org.flywaydb:flyway-core`、`org.flywaydb:flyway-database-postgresql`、`org.slf4j:slf4j-api`；测试依赖 `junit-jupiter`、`assertj-core`、`org.testcontainers:testcontainers-postgresql`、`org.testcontainers:testcontainers-junit-jupiter`、`slf4j-simple`。版本通过导入 `spring-boot-dependencies` BOM 与 wp-app 保持一致（只借用版本，不引入任何 Spring 依赖）。
  - `maven-enforcer-plugin`：`dependencyConvergence`；`bannedDependencies` 禁止 `org.springframework*`、`wprebuild:wp-app`、`wprebuild:wp-kernel`、`net.sourceforge.owlapi*`。实例库只用 IRI 引用本体，不依赖编辑器的任何代码。
- `backend/wp-instances/src/main/resources/db/instances/V1__instances_schema.sql`：
  - `instances(iri text, class_iri text, attributes jsonb not null default '{}', valid_from timestamptz not null, valid_to timestamptz, source text not null, ontology_rev bigint not null, primary key (iri, valid_from))`，`check (valid_to is null or valid_to > valid_from)`；
  - `links(subject_iri text, property_iri text, object_iri text, valid_from timestamptz not null, valid_to timestamptz, primary key (subject_iri, property_iri, object_iri, valid_from))`，同样的有效期检查；
  - 当前版本的部分索引：`instances (iri) where valid_to is null`（唯一，保证每个实例至多一个当前版本）、`instances (class_iri) where valid_to is null`、`links (subject_iri, property_iri, object_iri) where valid_to is null`（唯一，保证同一关系至多一个当前版本）、`links (subject_iri, property_iri) where valid_to is null`、`links (object_iri, property_iri) where valid_to is null`（反向追溯）；历史查询用的 `links (subject_iri, property_iri, valid_from)` 与 `links (object_iri, property_iri, valid_from)`。
- 包 `wprebuild.instances`：
  - `InstanceSchema.migrate(DataSource)`：Flyway，`locations = classpath:db/instances`；
  - `record InstanceRecord(String iri, String classIri, String attributesJson, String source, long ontologyRev)`、`record LinkRecord(String subjectIri, String propertyIri, String objectIri)`；
  - `InstanceStore`（构造参数 `DataSource`）：`void upsert(InstanceRecord record, Instant at)`（类或属性值变化时关闭当前版本、插入新版本；没有变化时什么都不做，保证幂等）、`void retire(String iri, Instant at)`（关闭当前版本与它作为主语或宾语的当前关系）、`void link(LinkRecord link, Instant at)`（已存在当前关系时什么都不做）、`void unlink(LinkRecord link, Instant at)`、`Optional<InstanceRecord> find(String iri, Instant asOf)`；
  - `TraceQueries`（构造参数 `DataSource`）：`List<String> reachable(String startIri, Set<String> propertyIris, Direction direction, int maxHops, Instant asOf)`（递归 CTE，带深度上限与环检测）、`List<List<String>> follow(String startIri, List<PathStep> path, Instant asOf)`（按给定的属性序列逐跳追溯，`PathStep(String propertyIri, Direction direction)`），返回完整路径。
- 测试 `wprebuild/instances/InstanceStoreTest.java`（Testcontainers `postgres:18`）：空库迁移成功；`upsert` 相同内容两次只有一个版本，内容变化后旧版本被关闭；`find(asOf)` 返回当时的版本；`retire` 后当前查询看不到该实例及其关系，历史时间点仍能看到；`link` 幂等；并发两次 `link` 同一关系只有一个当前版本；`at` 早于当前版本的 `valid_from` 时被拒绝；`reachable` 在有环的图上终止且不重复；`follow` 在“批次 → 托盘 → 货位”的三跳路径上返回正确路径，并且按 `asOf` 区分托盘移库前后的货位。
- `docs/02-architecture/overview.md` 第 1 节仓库结构增加 `wp-instances/` 一行：“实例库 PoC（ADR-013）：纯 Java + JDBC，独立数据库，禁止依赖 wp-kernel / wp-app；是否服务化由 P6-34 决定”。

## 实现要点
- 时间语义：区间左闭右开 `[valid_from, valid_to)`；`asOf` 查询条件为 `valid_from <= asOf and (valid_to is null or valid_to > asOf)`。所有时间参数由调用方传入，`InstanceStore` 内部不调用 `now()`，测试才可重复。
- 并发与时间顺序：`upsert` 先 `select … for update` 锁住当前版本再比较与关闭；首次插入或并发插入撞上唯一索引时重试一次；`link` 同理。`at` 早于当前版本的 `valid_from` 时抛出 `IllegalArgumentException`（不允许改写历史）。
- `upsert`、`retire`、`link`、`unlink` 各自在一个事务内完成；本卡不做批量导入（P6-32 用 `COPY` 造数据，放在测试代码中）。
- 递归 CTE 用路径数组做环检测，深度上限作为参数传入；在 Javadoc 中写明复杂度随扇出指数增长，调用方必须给出 `maxHops`。
- 这是 PoC：不做服务端口、认证、配置文件、连接池（测试用 Testcontainers 的 `DataSource`）。服务形态在 P6-34 决定。
- 与编辑器的 Flyway 完全分开：不同的 `locations`、不同的数据库，wp-app 的迁移脚本不受影响。
- 运行时依赖都是 wp-app 已在使用的库（版本由同一个 BOM 管理）；测试用的 `slf4j-simple` 版本由根 POM 的依赖管理提供。不引入新的第三方库。

## ECC 技能
工作项类型：`db` —— 数据库 schema、Flyway 迁移与 JDBC 仓储。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-31` 查看。

- 开工前加载的 skill：`postgres-patterns`、`database-migrations`、`springboot-tdd`
- 提交前调用的审查 agent：`database-reviewer`、`java-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-instances -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=InstanceStoreTest
./mvnw -q -pl :wp-instances -am verify -DskipTests
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 规模造数与性能基准（P6-32）
- 由本体生成校验规则（P6-33）
- 服务端口、REST 接口、认证、部署、Apache AGE（P6-34 依据 PoC 结论规划）
- 修改 wp-app 或编辑器的数据库
