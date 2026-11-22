---
id: P6-57
title: PoC：新建 wp-instances-app Spring Boot 服务骨架（双数据源、Flyway、健康检查）
phase: P6
module: wp-instances-app
type: impl
size: M
work_type: app-service
ecc_extra: []
status: todo
depends_on: [P6-31]
---

# P6-57 实例库服务 PoC（骨架）

## 目标
仓库里多一个可独立启动的 Spring Boot 4.1.1 模块 `backend/wp-instances-app`：连接独立的实例库数据库并跑通 `wp-instances` 的 Flyway；另配只读投影库数据源；暴露健康检查。本卡验证“同仓独立服务”可行，通过后才做认证与 REST。

## 参考
- P6-34 / EPIC「实例库服务化」决定第 1、6 条；ADR-013 第 4 节；ADR-002（Spring Boot 4.1.1）
- P6-31：`wp-instances`（Enforcer 禁止 Spring / wp-app / wp-kernel / OWL API）、`InstanceSchema.migrate`
- `backend/wp-app` 的 BOM 导入与 Enforcer 写法；根 `pom.xml` 模块列表
- `docs/02-architecture/overview.md` 第 1 节

## 产出
- 根 `pom.xml`：`<modules>` 增加 `backend/wp-instances-app`。
- `backend/wp-instances-app/pom.xml`（artifactId `wp-instances-app`）：父工程同上；依赖 `wprebuild:wp-instances`、`spring-boot-starter-web`、`spring-boot-starter-jdbc`、`spring-boot-starter-actuator`、PostgreSQL 驱动、Flyway（若未由 starter 带入）；测试用 `spring-boot-starter-test`、Testcontainers。版本一律由已有 `spring-boot.version` / BOM 管理，**本卡不新增第三方坐标**（只用 Boot 已管理的 starter）。
- 主类 `wprebuild.instances.app.InstancesApplication`；配置：
  - `wp.instances.datasource.*` → 实例库 `DataSource`；启动时先 `InstanceSchema.migrate`（`classpath:db/instances`），再跑应用 Flyway（`classpath:db/instances-app`；本卡可空目录或 README 占位，真实脚本从 P6-58 起）；
  - `wp.projection.datasource.*` → 只读投影 `DataSource`；
  - `wp.instances.project-id`（UUID，必填，单租户）；
  - `server.port` 默认与 wp-app 不同（例如 `8082`）。
- Actuator：**liveness** 仅看进程；**readiness** 要求实例库与投影库均可连接（投影可 `select 1`），并在文档写明。
- Enforcer：禁止依赖 `wprebuild:wp-app`、`wprebuild:wp-kernel`、`net.sourceforge.owlapi*`。
- 测试 `InstancesAppSmokeTest`（`@SpringBootTest` + Testcontainers，同一 Postgres 两库）：启动成功；内核表存在；双库可达时 readiness UP；投影不可达时 readiness 非 UP。
- `docs/02-architecture/overview.md` 第 1 节：`wp-instances/` 标明内核库；新增 `wp-instances-app/`（独立 Spring Boot 服务，依赖 wp-instances，禁止依赖 wp-app / wp-kernel）。

## 通过标准（全部满足才算 PoC 通过）
1. `./mvnw -q -pl :wp-instances-app -am test` 通过；`wp-instances` 的 Enforcer 仍禁止 Spring。
2. `InstancesAppSmokeTest`：启动 + 双 Flyway 顺序 + readiness 行为如上。
3. 模块依赖树中**没有** `wprebuild:wp-app`、`wprebuild:wp-kernel`。
4. 执行记录写明 starter 列表、默认端口、两个 Flyway location。

任一项不满足时运行 `task.py block`，不要把实例库塞进 wp-app Modulith 绕过。

## 实现要点
- `wp-instances` 保持纯 JDBC；Spring 只出现在 `wp-instances-app`。
- 不暴露业务 REST、不做认证（P6-58 / P6-59）。
- 不改 `compose.yaml`（P6-61）。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-57` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-instances-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='InstancesAppSmokeTest'
./mvnw -q -pl :wp-instances -am verify
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 通过标准 1 ~ 4 的结果已写入执行记录
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- API Key、写入 / 读取 REST、幂等键、Compose、OpenAPI
