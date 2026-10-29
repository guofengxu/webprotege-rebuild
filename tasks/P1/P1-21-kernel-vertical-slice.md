---
id: P1-21
title: 内核竖切验收（里程碑 M1）
phase: P1
module: wp-kernel
type: impl
size: M
work_type: kernel
ecc_extra: [milestone]
status: todo
depends_on: [P1-15, P1-16, P1-18, P1-19, P1-06]
---

# P1-21 内核竖切验收（里程碑 M1）

## 目标
不依赖 Spring 和数据库，用内核完整走一遍“导入 → 编辑 → 回滚 → 快照 → 重新加载”。

## 参考
- BS-03（初始导入）
- 旧实现：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/project/ProjectImporter.java`（`getInitialChangeRecords`）
- `docs/03-plan/roadmap.md`（M1 条件）

## 产出
- `wprebuild.kernel.generator.InitialImportGenerator implements ChangeListGenerator<List<OntologyKey>>`：输入为已解析的 `List<OWLOntology>`；为每个本体依次生成 `DeclareOntology`（key 从 `o1` 起）、全部 `AddAxiom`、`AddOntologyAnnotation`、`AddImport`（BS-03）；项目不为空时拒绝执行；描述为 `Imported <n> ontologies`。
- 测试 `BS03_InitialImportGeneratorTest`：导入 `imports/` 下的三个本体，key、公理数、导入声明都正确。
- 测试 `KernelVerticalSliceTest`，按顺序执行：（1）导入 `warehouse-sample.ttl`；（2）用 `CreateEntitiesGenerator` 在“托盘”类下创建子类“周转托盘”（UUID 方式）；（3）断言类层级中出现该类，显示名称为“周转托盘”；（4）删除另一个类；（5）回滚第 3 号修订；（6）打快照；（7）用 `ProjectRuntimeLoader` 从同一份内存日志和快照重新加载；（8）断言新旧 runtime 的本体结构相等，类层级的 `roots` / `children` 和所有实体的显示名称一致。
- 同一个测试类再写一个变体：日志使用 `OfnLineChangeCodec` 编码后再解码（模拟持久化），结果仍一致。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`kernel` —— 内核领域代码（wp-kernel，纯 Java + OWL API 5）；附加：`milestone`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P1-21` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`、`hexagonal-architecture`、`production-audit`、`brownfield-retrospective`
- 提交前调用的审查 agent：`java-reviewer`、`type-design-analyzer`、`silent-failure-hunter`
- 可用命令：`/review-pr`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：里程碑 PR 合并前跑一次 /review-pr 多代理审查；brownfield-retrospective 的差距清单写入卡片执行记录，不新建文档目录。

## 验收
```bash
./mvnw -q -pl wp-kernel test -Dtest='KernelVerticalSliceTest,BS03_*'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] M1 的其余条件（P1-20 报告）已满足，或已在执行记录中说明
- [ ] 执行记录已填写

## 不在本卡范围
- 上传文件的解析与格式识别（P3-09）
