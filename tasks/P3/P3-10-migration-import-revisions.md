---
id: P3-10
title: 导入迁移包：修订历史与 IC 校验
phase: P3
module: wp-app/migration
type: impl
size: M
work_type: app-service
ecc_extra: [security]
status: todo
depends_on: [P3-08, P2-05]
---

# P3-10 导入迁移包：修订历史与 IC 校验

## 目标
旧项目连同完整的修订历史进入新系统，并经过结构等价校验。

## 参考
- `docs/02-architecture/interchange-format.md`（IC-1 ~ IC-8）
- ADR-011（`ONTOLOGY` 行）
- P1-06 的 `OfnLines.decodeLine`

## 产出
- `wprebuild.app.migration.MigrationPackageReader`：读取并校验 IC-1 ~ IC-6，失败时抛 `InvalidMigrationPackageException`，消息中包含规则编号与位置。
- `wprebuild.app.migration.ProjectMigrator`：用迁移包中的原项目 ID 创建 `projects` 行（项目已存在时，没有 `replace` 选项就报错）；按原修订号、用户、时间戳和描述，直接调用 `RevisionLog.append` 写入每个修订（不经过 `ChangePipeline`，因为旧日志是权威的）；在每个本体 key 第一次被引用的修订开头插入 `DeclareOntology`（取自 manifest 的 `ontologies`）。缺失的用户先以“需重置密码”状态创建（P3-11 会补全资料）。
- 加载后校验 IC-7（与 `head.ofn-lines.gz` 结构相等）和 IC-8（按 `AxiomType` 计数），失败则回滚整个项目的导入（删除已写入的行），并报告差异。
- 入口：`wprebuild.app.migration.MigrationRunner`（`ApplicationRunner`），在设置 `wp.migrate.package=<目录>` 时运行，可选 `wp.migrate.replace=true`；运行结束后输出摘要日志，并以非 0 退出码表示失败。
- 测试 `ProjectMigratorTest`：导入 `testdata/migration/sample-package`（由 P2-05 生成），head 修订号、每个修订的用户与时间戳与原包一致，IC-7、IC-8 通过；篡改 `head.ofn-lines.gz` 的一行后 IC-7 失败且没有残留数据；重复导入且未设置 replace 时报错。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口；附加：`security`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P3-10` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`、`security-review`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`、`security-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ProjectMigratorTest
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 用户资料、角色、设置（P3-11）
