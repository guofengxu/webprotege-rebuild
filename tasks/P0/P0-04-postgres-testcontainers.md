---
id: P0-04
title: 本地 PostgreSQL 与 Testcontainers 测试基座
phase: P0
module: wp-app
type: impl
size: S
work_type: db
ecc_extra: [docker]
status: todo
depends_on: [P0-02]
---

# P0-04 本地 PostgreSQL 与 Testcontainers 测试基座

## 目标
本地可以用 `docker compose` 启动数据库，测试用 Testcontainers 自动拉起 PostgreSQL 18，Flyway 迁移在启动时执行。

## 参考
- ADR-004

## 产出
- 根目录 `compose.yaml`：服务 `postgres`，镜像 `postgres:18`，库 / 用户 / 密码均为 `wp`，端口 5432，命名卷 `wp-pg-data`。
- wp-app 依赖：`spring-boot-starter-jdbc`、`org.flywaydb:flyway-core`、`org.flywaydb:flyway-database-postgresql`、`org.postgresql:postgresql`；测试依赖 `spring-boot-testcontainers`、`org.testcontainers:postgresql`、`org.testcontainers:junit-jupiter`。
- `application.yaml` 增加 `spring.datasource.url/username/password`，使用环境变量占位并给出本地默认值（`jdbc:postgresql://localhost:5432/wp`）。
- `src/main/resources/db/migration/V1__baseline.sql`：`create table app_info(key text primary key, value text not null); insert into app_info values ('schema', '1');`
- `src/test/java/wprebuild/app/support/TestcontainersConfiguration.java`：`@TestConfiguration(proxyBeanMethods = false)`，提供 `@Bean @ServiceConnection PostgreSQLContainer<?>`（镜像 `postgres:18`）。
- `src/test/java/wprebuild/app/support/DatabaseTest.java`：组合注解 `@SpringBootTest` + `@Import(TestcontainersConfiguration.class)`，供后续测试复用。
- `src/test/java/wprebuild/app/FlywayBaselineTest.java`：用 `JdbcClient` 查询 `app_info`，断言 `schema = 1`。
- 修改 `WpApplicationContextTest`：改用 `@DatabaseTest`，删除 P0-02 中的数据源排除。

## 实现要点
- 本机需要 Docker。Docker 不可用时状态改为 `blocked`，不要改用 H2 等内存数据库替代。

## ECC 技能
工作项类型：`db` —— 数据库 schema、Flyway 迁移与 JDBC 仓储；附加：`docker`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P0-04` 查看。

- 开工前加载的 skill：`postgres-patterns`、`database-migrations`、`springboot-tdd`、`docker-patterns`
- 提交前调用的审查 agent：`database-reviewer`、`java-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
docker compose up -d postgres
./mvnw -q -pl wp-app -am test -Dtest='FlywayBaselineTest,WpApplicationContextTest' -Dsurefire.failIfNoSpecifiedTests=false
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 业务表（P3-01）
