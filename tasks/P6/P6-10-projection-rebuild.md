---
id: P6-10
title: 本体投影表 PoC（二）：从内存模型计算投影行，并全量重建一个项目
phase: P6
module: wp-app
type: impl
size: M
work_type: app-service
ecc_extra: [concurrency]
status: done
depends_on: [P6-09]
---

# P6-10 投影表 PoC（二）：计算与全量重建

## 目标
给定一个项目，读取它的内存模型，算出全部投影行，并通过 P6-09 的写入器整体替换写入。结果与 Web 端层级树看到的父子关系一致，重建可以随时重复执行。

## 参考
- P6-09：`projection/ProjectionSnapshot` 与行 `record`、`ProjectionWriter`（`lockProject`、`replaceProject`，修订号只进不退）
- ADR-006（写锁与读锁）；`wprebuild.kernel.runtime.ProjectWriteGate#read`；`wprebuild.kernel.runtime.ChangePipeline#commit`（先写日志、再在写锁内改内存模型）
- `ontology/ProjectRuntimeRegistry.java`（`get`）、`ontology/ProjectHandle.java`（`gate()`）、`wprebuild.kernel.runtime.ProjectRuntime`（`ontologies()`、`head()`、`view(Class)`）
- `wprebuild.kernel.hierarchy.HierarchyView`（`roots()`、`parents(E)`）以及 `ClassHierarchyView`、`ObjectPropertyHierarchyView`、`DataPropertyHierarchyView`、`AnnotationPropertyHierarchyView`
- `web/UploadImportTest.java`（测试中导入本体的方式）

## 产出
- `projection/ProjectionRows.java`：纯函数 `static ProjectionSnapshot compute(ProjectRuntime runtime)`，只读内存模型：
  - `entities`：项目签名中的命名实体；`deprecated` 取 `owl:deprecated "true"^^xsd:boolean` 注解断言；
  - `labels`：`rdfs:label`、`skos:prefLabel`、`skos:altLabel` 的字面量值；非字面量的值跳过；
  - `hierarchy_edges`：取四个层级视图的 `parents(E)`，与 Web 端层级树一致；根节点不写到 `owl:Thing` / `owl:topObjectProperty` / `owl:topDataProperty` 的边；
  - `property_domains` / `property_ranges`：只取命名类与命名数据类型，匿名类表达式跳过。
- `projection/ProjectionRebuilder.java`（`@Component`）：`void rebuild(ProjectId projectId)`。在一个事务中依次执行：`writer.lockProject` → 在 `handle.gate().read(...)` 内调用 `ProjectionRows.compute(runtime)` 并记下 `runtime.head()` → 释放读锁 → `writer.replaceProject(…, head)`。已有事务时加入该事务（P6-12 需要）。
- 测试 `projection/ProjectionRebuildTest.java`（Testcontainers；导入 `testdata/ontologies/warehouse-sample.ttl`）：
  - 已知类“托盘”有 `entities` 行和 `labels` 行，并有指向“搬运单元”的 `hierarchy_edges` 行；
  - 属性的定义域与值域写入对应的表；带匿名父类表达式的类不产生多余的边；顶层类没有到 `owl:Thing` 的边；
  - 连续重建两次，全部行完全相同；编辑一次之后重建，结果反映该次编辑；`project_state.rev_no` 等于 `runtime.head()`；
  - `ProjectionRows.compute` 的单元测试：弃用实体、多语言标签、非字面量标签值各一例。

## 实现要点
- **先取咨询锁，再计算**：如果先算后取锁，较早算出的结果可能覆盖另一个线程已经写入的较新结果。这个顺序不会死锁，因为编辑线程从不等待投影的咨询锁；`replaceProject` 的“修订号只进不退”再兜一层底。
- 写库期间不持有内核的锁，避免阻塞编辑。读锁内同时取模型与 `head()`，两者一致：`ChangePipeline` 在写锁内应用修订并推进 `head`。
- `ProjectionRows` 不访问数据库，便于 P6-11 在没有应用上下文的情况下直接调用。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口；附加：`concurrency`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-10` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：审查重点：锁顺序（ADR-006）、WP-1 ~ WP-8（docs/01-rea/write-path.md）。

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='ProjectionRebuildTest,ProjectionWriterTest,ModularityTest'
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写

## 不在本卡范围
- 规模测量与通过标准（P6-11）
- 增量更新（P6-12）；重建接口（P6-13）
- 推理得到的层级（只取层级视图给出的父子关系）

## 执行记录
- 日期：2026-11-14
- 分支：feature/p6-10-projection-rebuild
- 测试：2026-11-14T16:20:57+08:00 通过（3 项）
- 实际改动：Added ProjectionRows.compute and ProjectionRebuilder. ProjectionRebuildTest covers warehouse rebuild, idempotency, anonymous parents, deprecated and label cases. Review fix: warn when replaceProject returns false.
- 偏差：无
- 审查：java-reviewer, code-reviewer, silent-failure-hunter
