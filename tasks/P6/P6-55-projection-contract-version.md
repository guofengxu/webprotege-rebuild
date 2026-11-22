---
id: P6-55
title: 编辑器：投影 project_state 增加 contract_version（V9），作为实例库读取投影表的契约版本
phase: P6
module: wp-app/projection
type: impl
size: S
work_type: db
ecc_extra: []
status: todo
depends_on: [P6-37]
---

# P6-55 投影契约版本号

## 目标
编辑器的 `projection.project_state` 带有整数列 `contract_version`，实例库（及其它投影消费者）可以在加载规则前核对版本；列含义变化时必须递增该版本，避免静默读到不兼容的投影形状。

## 参考
- P6-34 / `tasks/P6/EPIC.md`「实例库服务化」决定第 7 条
- P6-09：`V5__projection_schema.sql` 的 `project_state`；P6-13：`docs/api/projection.md`
- `projection/ProjectionWriter.java`（插入 / 更新 `project_state` 的 SQL）
- EPIC：本卡 Flyway 为 **V9**（接在 P6-37 的 V8 之后）

## 产出
- `backend/wp-app/src/main/resources/db/migration/V9__projection_contract_version.sql`：`alter table projection.project_state add column contract_version integer not null default 1`；已有行得到 `1`。
- `ProjectionWriter`：所有写入 `project_state` 的路径带上当前契约版本常量（例如 `ProjectionContract.VERSION = 1`）；全量重建与增量更新都不得把版本写回 `0` 或 `null`。
- `docs/api/projection.md`：`project_state` 表增加 `contract_version` 行；新增小节说明：当前值为 `1`、语义是“实例库依赖的列集合的版本”、破坏性变更（删列、改列含义、改 entity_type 枚举等）必须递增并同步改 `wp-instances` 的期望版本；非破坏性加列可不递增（写明规则）。
- `CoreSchemaTest`（或现有投影 schema 测试）断言列存在且默认 / 写入值为 `1`。
- 测试：空库迁移后 `project_state` 含 `contract_version`；写入一条投影状态后读回为 `1`。

## 实现要点
- 依赖 P6-37 **只为** Flyway V8 → V9 串行；本卡不改 OIDC / `user_identities`。
- 不改投影业务语义，不加必填 / 基数 / 枚举列（P6-63）。
- 当前版本常量放在 `projection` 包内一处，避免魔法数散落。

## ECC 技能
工作项类型：`db` —— 数据库 schema、Flyway 迁移与 JDBC 仓储。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-55` 查看。

- 开工前加载的 skill：`postgres-patterns`、`database-migrations`、`springboot-tdd`
- 提交前调用的审查 agent：`database-reviewer`、`java-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='CoreSchemaTest,ProjectionWriterTest,ProjectionRebuildTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- `wp-instances` 侧的期望版本校验（P6-56）
- 扩展必填 / 基数 / 枚举投影列（P6-63）
- 修改 OpenAPI / `@wp/api-types`
