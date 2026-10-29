---
id: P1-14
title: 实体 IRI 生成：OBO 风格（BS-06）
phase: P1
module: wp-kernel
type: impl
size: S
work_type: kernel
ecc_extra: [rea]
status: todo
depends_on: [P1-13]
---

# P1-14 实体 IRI 生成：OBO 风格（BS-06）

## 目标
支持按用户分配编号区间的 OBO 风格 IRI。

## 参考
- BS-06
- 旧实现：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/crud/obo/`（`OBOIdSuffixEntityCrudKitHandler.java`、`OBOIdSession.java`、`CannotGenerateFreshEntityIdForUserException.java`）
- 旧设置：`../webprotege/webprotege-shared/src/main/java/edu/stanford/bmir/protege/web/shared/crud/oboid/`

## 产出
- 补全 `OboKitSettings`：字段与旧系统 `OboIdSuffixSettings` 一致（ID 前缀、总位数、用户区间列表等，以旧代码为准）。
- `wprebuild.kernel.crud.OboIdEntityCrudKit implements EntityCrudKit`：编号取当前用户区间内未被项目签名使用的最小值，零填充到总位数；区间耗尽时抛 `wprebuild.kernel.crud.CannotGenerateFreshEntityIdException`。
- 测试 `BS06_OboIdEntityCrudKitTest`：区间内顺序分配；跳过已被使用的编号；区间耗尽抛异常；未分配区间的用户按旧系统的行为处理（先读旧代码确认，再写成测试）。

## 实现要点
- 本卡描述与旧代码的行为冲突时，以旧代码为准，并在执行记录中写明差异。

## ECC 技能
工作项类型：`kernel` —— 内核领域代码（wp-kernel，纯 Java + OWL API 5）；附加：`rea`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P1-14` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`、`hexagonal-architecture`、`code-tour`
- 开工时可用的辅助 agent：`spec-miner`
- 提交前调用的审查 agent：`java-reviewer`、`type-design-analyzer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。

## 验收
```bash
./mvnw -q -pl wp-kernel test -Dtest=BS06_OboIdEntityCrudKitTest
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 区间的管理界面
