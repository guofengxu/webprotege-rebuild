---
id: P1-05
title: 有效变更过滤（BS-02）
phase: P1
module: wp-kernel
type: impl
size: S
work_type: kernel
ecc_extra: [rea]
status: done
depends_on: [P1-03, P1-04]
---

# P1-05 有效变更过滤（BS-02）

## 目标
只有真正改变本体状态的变更才会被持久化。

## 参考
- BS-02（注意导入变更与旧系统的有意差异）
- 旧实现：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/index/impl/RootIndexImpl.java`（`getEffectiveChanges`、`ChangeFilter`）

## 产出
- `wprebuild.kernel.ontology.EffectiveChanges`：`static List<OntologyChange> compute(ProjectOntologies current, List<OntologyChange> changes)` = 先 `ChangeListMinimiser.minimise`，再按 BS-02 逐条判断；`DeclareOntology` 在 key 不存在时有效；引用不存在 key 的其它变更抛 `IllegalArgumentException`。
- 测试 `BS02_EffectiveChangesTest`：BS-02 表格中的每一行一个用例；导入变更单独一个用例，测试方法注释写明与旧系统的差异；同一批中先 `DeclareOntology(o2)` 再向 o2 添加公理时视为有效。
- 属性测试 `BS02_EffectiveChangesPropertyTest`（jqwik）：在 3 条公理、2 个本体的小池子上随机生成变更序列；断言（1）对同一初始状态，应用有效变更后的状态等于先按 BS-01 抵消、再只把真正改变成员关系的变更作用到初始集合后的状态；（2）应用有效变更后，再把它们的逆变更倒序应用，状态恢复如初。抵消与先后顺序无关，所以「删除已存在的公理再添加」和「向已含该公理的本体先添加再删除」在最小化后不再改变本体。

## 实现要点
- 同一批变更中，后面的变更依赖前面变更的结果（例如先登记本体，再向其中添加公理）。判断“目标本体是否包含某公理”时要考虑批内前面已判为有效的变更，可以在一个临时的增量视图上判断，不要修改 `current`。

## ECC 技能
工作项类型：`kernel` —— 内核领域代码（wp-kernel，纯 Java + OWL API 5）；附加：`rea`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P1-05` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`、`hexagonal-architecture`、`code-tour`
- 开工时可用的辅助 agent：`spec-miner`
- 提交前调用的审查 agent：`java-reviewer`、`type-design-analyzer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。

## 验收
```bash
./mvnw -q -pl :wp-kernel test -Dtest='BS02_*'
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写

## 不在本卡范围
- 持久化（P1-08 之后）

## 执行记录
- 日期：2026-11-02
- 分支：feature/p1-05-effective-changes
- 测试：2026-11-02T21:32:16+08:00 通过（3 项）
- 实际改动：实现 EffectiveChanges：先按 BS-01 最小化，再按 BS-02 保留真正改变成员关系的变更。同一批里先登记的本体对其后的公理、注解和导入可见，且不修改传入的 ProjectOntologies。
- 偏差：属性（1）按已确认的语义，与「先 BS-01 抵消，再把改变成员关系的变更作用到初始集合」比较，不与原序列从左到右的集合应用比较。
- 审查：java-reviewer, type-design-analyzer, silent-failure-hunter
