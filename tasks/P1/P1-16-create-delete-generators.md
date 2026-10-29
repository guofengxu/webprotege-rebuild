---
id: P1-16
title: 创建实体与删除实体生成器（BS-07）
phase: P1
module: wp-kernel
type: impl
size: M
work_type: kernel
ecc_extra: [rea]
status: todo
depends_on: [P1-13, P1-05]
---

# P1-16 创建实体与删除实体生成器（BS-07）

## 目标
创建和删除实体有统一的生成器，供各类实体的 API 复用。

## 参考
- BS-05、BS-07
- 旧实现：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/change/AbstractCreateEntitiesChangeListGenerator.java`、`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/crud/DeleteEntitiesChangeListGenerator.java`、`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/util/EntityDeleter.java`

## 产出
- `wprebuild.kernel.generator.CreateEntitiesGenerator<E extends OWLEntity> implements ChangeListGenerator<List<E>>`：参数为实体类型、名称列表、可选语言、父实体集合、目标本体 key；为每个名称生成临时 IRI（BS-05，区分符用随机 UUID），并按实体类型生成父关系公理：类用 `SubClassOf`，对象 / 数据 / 注解属性用对应的子属性公理，个体用 `ClassAssertion`（父实体为类）；`renameResult` 把结果中的临时实体换成正式实体；描述形如 `Created class Foo`、`Created 3 classes`。
- `wprebuild.kernel.generator.DeleteEntitiesGenerator implements ChangeListGenerator<Set<OWLEntity>>`：删除项目内所有本体中引用这些实体的公理，以及以其 IRI 为主语的注解断言（BS-07）；描述形如 `Deleted 2 entities`。
- 测试 `BS07_CreateEntitiesGeneratorTest`：通过 `ChangePipeline` + `KitEntityCreator` 在某父类下创建两个类，修订中包含声明、标签、`SubClassOf`；返回的实体是正式 IRI；空名称列表不产生修订。
- 测试 `BS07_DeleteEntitiesGeneratorTest`：删除后 `referencingAxioms` 为空；以该实体为主语的注解断言被删除；对“以该实体 IRI 作为注解值”的断言，按旧 `EntityDeleter` 的实际行为处理（先读旧代码确认，再写成测试）。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`kernel` —— 内核领域代码（wp-kernel，纯 Java + OWL API 5）；附加：`rea`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P1-16` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`、`hexagonal-architecture`、`code-tour`
- 开工时可用的辅助 agent：`spec-miner`
- 提交前调用的审查 agent：`java-reviewer`、`type-design-analyzer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。

## 验收
```bash
./mvnw -q -pl wp-kernel test -Dtest='BS07_*'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- Frame 编辑（P3-15 ~ P3-18）
- 合并实体（P6）
