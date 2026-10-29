---
id: P1-13
title: 实体 IRI 生成：UUID 与用户输入名称两种方式（BS-06）
phase: P1
module: wp-kernel
type: impl
size: M
work_type: kernel
ecc_extra: [rea]
status: todo
depends_on: [P1-12]
---

# P1-13 实体 IRI 生成：UUID 与用户输入名称两种方式（BS-06）

## 目标
新实体的正式 IRI 按项目设置生成，并自动带上标签。

## 参考
- BS-06
- 旧实现：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/crud/uuid/UuidEntityCrudKitHandler.java`、`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/crud/supplied/`、`../webprotege/webprotege-shared/src/main/java/edu/stanford/bmir/protege/web/shared/crud/uuid/UuidSuffixSettings.java`
- 旧测试：`../webprotege/webprotege-server-core/src/test/java/edu/stanford/bmir/protege/web/server/crud/uuid/UuidEntityCrudKitHandlerTestCase.java`、`../webprotege/webprotege-server-core/src/test/java/edu/stanford/bmir/protege/web/server/crud/supplied/SuppliedNameSuffixEntityCrudKitHandlerTestCase.java`

## 产出
- `wprebuild.kernel.crud.NewEntitySettings`：`record NewEntitySettings(String iriPrefix, KitSettings kit, IRI labelProperty, String defaultLang, OntologyKey targetOntology)`；`sealed interface KitSettings` 及实现 `UuidKitSettings()`、`SuppliedNameKitSettings(WhiteSpaceTreatment)`、`OboKitSettings(...)`（OBO 的字段在 P1-14 补全，本卡先占位）；默认设置为 UUID、`rdfs:label`。
- `wprebuild.kernel.crud.WhiteSpaceTreatment`：`CAMEL_CASE`（默认）、`UNDERSCORES`。
- `wprebuild.kernel.crud.PrefixedNameExpander`：构造参数为前缀映射；内置 `owl:`、`rdf:`、`rdfs:`、`xsd:`、`skos:`、`dc:`、`dcterms:`；`Optional<IRI> expand(String)`。
- `wprebuild.kernel.crud.Base62`：`static String encode(UUID)`。
- `wprebuild.kernel.crud.EntityCrudKit`：`CreatedEntity create(EntityType<?>, String suppliedName, Optional<String> lang, NewEntitySettings, PrefixedNameExpander)`；实现 `UuidEntityCrudKit`、`SuppliedNameEntityCrudKit`。
- `wprebuild.kernel.crud.KitEntityCreator implements EntityCreator`：构造参数为 `Supplier<NewEntitySettings>` 和 `Supplier<PrefixedNameExpander>`，按设置选择 kit。
- 测试 `BS06_UuidEntityCrudKitTest`、`BS06_SuppliedNameEntityCrudKitTest`：移植旧测试中的断言，至少覆盖：UUID 后缀匹配 `R[0-9A-Za-z]+`；输入非空时生成标签，语言正确；输入为 `owl:Thing` 时直接使用内置 IRI；输入为 `<http://x/y>` 时直接使用该 IRI，Supplied name 方式下标签为 `y`；`My Class` 在两种空白处理方式下分别得到 `MyClass`、`My_Class`；输入为空时不生成标签。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`kernel` —— 内核领域代码（wp-kernel，纯 Java + OWL API 5）；附加：`rea`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P1-13` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`、`hexagonal-architecture`、`code-tour`
- 开工时可用的辅助 agent：`spec-miner`
- 提交前调用的审查 agent：`java-reviewer`、`type-design-analyzer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。

## 验收
```bash
./mvnw -q -pl wp-kernel test -Dtest='BS06_*'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- OBO 方式（P1-14）
- 设置的持久化与 API（P3-22）
