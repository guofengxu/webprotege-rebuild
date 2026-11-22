---
id: P6-33
title: 实例库 PoC（三）：从本体投影表生成定义域 / 值域校验规则，写入实例时校验
phase: P6
module: wp-instances
type: impl
size: M
work_type: db
ecc_extra: []
status: done
depends_on: [P6-32, P6-13]
---

# P6-33 实例库 PoC（三）：由本体生成校验规则

## 目标
实例库能读取编辑器的本体投影表（P6-09 ~ P6-13，只读账号），为一个项目生成校验规则：类是否存在、属性是否存在、属性的定义域与值域（含子类闭包）、数据属性值的类型；写入实例与关系时按规则校验，并记录写入时依据的本体修订号 `ontology_rev`。这样实例库复用投影表，而不需要自己再消费一遍 outbox 或加载 OWL 模型。

## 参考
- ADR-013 第 4 节：“由 TBox 生成校验规则（属性的定义域和值域、必填属性、基数、枚举取值），写入时进行校验”；`ontology_rev` 记录写入时依据的本体修订号
- P6-09：`backend/wp-app/src/main/resources/db/migration/V5__projection_schema.sql`（`projection.entities`、`hierarchy_edges`、`property_domains`、`property_ranges(range_kind CLASS | DATATYPE)`、`project_state.rev_no`）
- P6-13：`docs/api/projection.md`（只读账号 `wp_projection_reader`、一致性语义：最终一致，以 `project_state.rev_no` 为准）
- P6-31：`InstanceStore`、`InstanceRecord`、`LinkRecord`
- 测试语料 `testdata/ontologies/warehouse-sample.ttl`（P6-12 用过的仓库样例本体），用来挑选测试中的类与属性 IRI

## 产出
- 包 `wprebuild.instances.rules`：
  - `ProjectionRuleSource`（构造参数：投影库的 `DataSource`）：`ValidationRules load(UUID projectId)`，在一个只读、`repeatable read` 的事务中读取该项目的实体、类层级、定义域、值域与 `project_state.rev_no`；项目没有投影时抛出 `IllegalStateException`（消息说明需要先开启投影）。
  - `ValidationRules`（不可变）：`long ontologyRev()`、类与属性是否存在、某类的祖先闭包（基于 `hierarchy = 'CLASS'` 的边）、属性的定义域与值域。
  - `record Violation(String code, String subjectIri, String propertyIri, String message)`，`code` 取 `UNKNOWN_CLASS`、`UNKNOWN_PROPERTY`、`DEPRECATED_CLASS`、`DOMAIN_MISMATCH`、`RANGE_MISMATCH`、`DATATYPE_MISMATCH`。
  - `InstanceValidator`：`List<Violation> validate(InstanceRecord record, ValidationRules rules)`（`attributesJson` 的键是数据属性 IRI，值的 JSON 类型与 `xsd:string` / `xsd:integer` / `xsd:decimal` / `xsd:boolean` / `xsd:dateTime` 对照，其余数据类型只检查是字符串）与 `List<Violation> validateLink(LinkRecord link, String subjectClassIri, String objectClassIri, ValidationRules rules)`。
  - `ValidatingInstanceWriter`（包装 `InstanceStore`）：校验通过才写入，并把 `rules.ontologyRev()` 写入 `ontology_rev`；不通过时抛出 `InstanceValidationException`（携带全部 `Violation`），什么都不写。
- 测试 `wprebuild/instances/rules/InstanceValidationTest.java`（Testcontainers `postgres:18`，同一个容器中两个数据库：投影库与实例库）：
  - 投影库的表结构直接执行 `../wp-app/src/main/resources/db/migration/V5__projection_schema.sql`（只读引用，保证与编辑器一致，结构漂移时测试失败），再插入一个小型仓库本体的投影行（含两层子类、一个对象属性、两个数据属性、一个弃用类）；
  - 子类实例满足父类上的定义域；属性有两个定义域时，只属于其中一个类的实例得到 `DOMAIN_MISMATCH`；值域不匹配、未知属性、未知类、弃用类、数据类型不匹配各得到对应的 `code`；
  - 校验失败时实例库没有任何新行；成功写入的行 `ontology_rev` 等于投影的 `rev_no`；
  - 项目没有投影时 `load` 抛出带说明的异常。
- `docs/api/projection.md` 增加一小节“实例库如何使用投影表”：列出 `ProjectionRuleSource` 读取的表与列，说明这些列是实例库的依赖，修改时需要同步 wp-instances。

## 实现要点
- 校验是封闭世界的“写入守门”，不是 OWL 推理：定义域 / 值域在 OWL 中本来用于推断类型，这里按约束解释。把这一点写进 `InstanceValidator` 的 Javadoc，并写进 `docs/api/projection.md` 新增的小节。
- 定义域或值域有多个类时按 OWL 语义取**交集**：实例必须属于其中每一个类（含子类闭包）；属性没有定义域 / 值域时不检查。投影表只含属性自身声明的命名类定义域 / 值域，不含从父属性继承的、也不含复杂类表达式，这两点写进 `InstanceValidator` 的 Javadoc 与 `docs/api/projection.md` 新增的小节。
- 规则按项目一次性加载，不做缓存与刷新；规则如何随本体修订更新（轮询 `project_state.rev_no` 或订阅 outbox）留给 P6-34。
- 必填属性、基数、枚举取值在投影表中没有，本卡不做；在报告与 P6-34 中列为需要扩展投影表（新 ADR）的事项。

## ECC 技能
工作项类型：`db` —— 数据库 schema、Flyway 迁移与 JDBC 仓储。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-33` 查看。

- 开工前加载的 skill：`postgres-patterns`、`database-migrations`、`springboot-tdd`
- 提交前调用的审查 agent：`database-reviewer`、`java-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-instances -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='InstanceValidationTest,InstanceStoreTest'
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写

## 不在本卡范围
- 必填属性、基数、枚举取值（需要扩展投影表，另开 ADR）
- 规则的缓存与自动刷新、outbox 订阅（P6-34）
- 修改编辑器的投影表或 wp-app 代码

## 执行记录
- 日期：2026-11-21
- 分支：feature/p6-33-instance-store-validation
- 测试：2026-11-21T22:50:54+08:00 通过（3 项）
- 实际改动：Added wprebuild.instances.rules (ProjectionRuleSource, ValidationRules, InstanceValidator, ValidatingInstanceWriter) to load projection domains/ranges under REPEATABLE READ and gate InstanceStore writes; documented projection column dependencies; InstanceValidationTest covers domain intersection, subclass domain, datatype/range mismatches, and no-write-on-failure.
- 偏差：无
- 审查：database-reviewer, java-reviewer
