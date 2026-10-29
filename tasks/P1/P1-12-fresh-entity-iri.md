---
id: P1-12
title: 临时实体 IRI 协议与 IRI 替换（BS-05）
phase: P1
module: wp-kernel
type: impl
size: M
work_type: kernel
ecc_extra: [rea]
status: todo
depends_on: [P1-11]
---

# P1-12 临时实体 IRI 协议与 IRI 替换（BS-05）

## 目标
客户端可以用临时 IRI 描述新实体，服务端统一生成正式 IRI，并把映射返回给客户端。

## 参考
- BS-05
- WP-7
- 旧实现：`../webprotege/webprotege-shared/src/main/java/edu/stanford/bmir/protege/web/shared/entity/FreshEntityIri.java`
- 旧测试：`../webprotege/webprotege-shared/src/test/java/edu/stanford/bmir/protege/web/shared/entity/FreshEntityIri_TestCase.java`
- 旧处理流程：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/project/chg/ChangeManager.java`（`applyChanges` 中处理临时 IRI 的部分）

## 产出
- `wprebuild.kernel.iri.FreshEntityIri`：`record FreshEntityIri(String suppliedName, String langTag, String discriminator, Set<IRI> parentIris)`；`IRI toIri()`；`static Optional<FreshEntityIri> parse(IRI)`；`static boolean isFresh(IRI)`。格式与转义规则按 BS-05。
- `wprebuild.kernel.iri.IriReplacer`：`static List<OntologyChange> replace(List<OntologyChange>, Map<IRI, IRI>)`，替换公理、注解和注解断言主语中出现的 IRI（可以使用 OWL API 的 `OWLObjectDuplicator`）。
- `wprebuild.kernel.iri.EntityCreator`：`CreatedEntity create(EntityType<?> type, FreshEntityIri fresh, UserId user, ProjectOntologies ontologies)`；`record CreatedEntity(OWLEntity entity, List<OntologyChange> creationChanges)`。具体实现在 P1-13。
- `wprebuild.kernel.iri.CreatePermission`：函数式接口 `boolean canCreate(UserId, EntityType<?>)`；常量 `ALLOW_ALL`。
- `wprebuild.kernel.iri.DefaultFreshIriMinter implements FreshIriMinter`：扫描每条变更的签名，以及注解断言中 IRI 类型的主语，找出临时 IRI；无权限时抛 `wprebuild.kernel.iri.CreatePermissionDeniedException`；同一个临时 IRI 只创建一次实体；返回替换后的变更（创建变更追加在末尾）和映射。
- `ChangePipeline` 改为使用传入的 minter，并在返回前调用 `generator.renameResult`（WP-7）。
- 测试 `BS05_FreshEntityIriTest`：移植旧测试的全部用例（格式、转义、解析往返、多个父 IRI）。
- 测试 `BS05_DefaultFreshIriMinterTest`：使用测试用的 `EntityCreator` 桩；同一临时 IRI 出现在多条变更和注解断言主语中，只创建一次，全部被替换；无权限时抛异常；端到端：通过 `ChangePipeline` 提交含临时 IRI 的生成器，修订中不含任何临时 IRI，结果对象中的临时 IRI 已被替换。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`kernel` —— 内核领域代码（wp-kernel，纯 Java + OWL API 5）；附加：`rea`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P1-12` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`、`hexagonal-architecture`、`code-tour`
- 开工时可用的辅助 agent：`spec-miner`
- 提交前调用的审查 agent：`java-reviewer`、`type-design-analyzer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。

## 验收
```bash
./mvnw -q -pl wp-kernel test -Dtest='BS05_*'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- IRI 后缀的生成规则（P1-13、P1-14）
