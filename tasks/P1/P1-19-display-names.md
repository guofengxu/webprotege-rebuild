---
id: P1-19
title: 显示名称视图（BS-09）
phase: P1
module: wp-kernel
type: impl
size: M
work_type: kernel
ecc_extra: [rea]
status: todo
depends_on: [P1-09]
---

# P1-19 显示名称视图（BS-09）

## 目标
每个实体都能按项目的语言设置得到显示名称，并能报告哪些实体的名称变了。

## 参考
- BS-09
- 旧实现：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/shortform/DefaultShortFormAnnotationPropertyIRIs.java`、`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/shortform/DictionaryManager.java`

## 产出
- `wprebuild.kernel.shortform.DisplayNameSetting`：`record DisplayNameSetting(IRI annotationProperty, String lang)`（`lang` 为空串表示不带语言标签）。
- `wprebuild.kernel.shortform.DisplayNameView implements DerivedView`：构造参数为 `List<DisplayNameSetting>`（为空时使用 BS-09 的默认顺序）；`String displayName(OWLEntity)`；`Map<DisplayNameSetting, String> allNames(OWLEntity)`；`Set<OWLEntity> lastChanged()`（最近一次 `onApplied` 后显示名称发生变化的实体）。
- 测试 `BS09_DisplayNameViewTest`：设置为 `[(rdfs:label, zh), (rdfs:label, en)]` 时优先取中文；只有英文时取英文；都没有时取本地名；本地名为空时取完整 IRI；内置实体显示为 `owl:Thing`；修改标签后该实体出现在 `lastChanged` 中，未改动的实体不在其中。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`kernel` —— 内核领域代码（wp-kernel，纯 Java + OWL API 5）；附加：`rea`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P1-19` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`、`hexagonal-architecture`、`code-tour`
- 开工时可用的辅助 agent：`spec-miner`
- 提交前调用的审查 agent：`java-reviewer`、`type-design-analyzer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。

## 验收
```bash
./mvnw -q -pl wp-kernel test -Dtest=BS09_DisplayNameViewTest
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 搜索索引（P3-19）
- 显示语言设置的持久化（P3-22）
