---
id: P1-03
title: 变更列表最小化（BS-01）
phase: P1
module: wp-kernel
type: impl
size: S
work_type: kernel
ecc_extra: [rea]
status: todo
depends_on: [P1-02]
---

# P1-03 变更列表最小化（BS-01）

## 目标
同一批变更中互相抵消的添加和删除被去掉，结果与旧系统一致。

## 参考
- BS-01
- 旧实现：`../webprotege/webprotege-server-api/src/main/java/edu/stanford/bmir/protege/web/server/change/ChangeListMinimiser.java`
- 旧测试：`../webprotege/webprotege-server-api/src/test/java/edu/stanford/bmir/protege/web/server/change/ChangeListMinimiser_TestCase.java`

## 产出
- `wprebuild.kernel.change.ChangeListMinimiser`：`static List<OntologyChange> minimise(List<OntologyChange>)`，算法按 BS-01（两遍扫描，键为 `(OntologyKey, OWLAxiom)`）。
- 测试 `BS01_ChangeListMinimiserTest`：移植旧测试中的全部用例，并补充 BS-01 表格中的 6 个例子；补充：不同本体上的同一公理互不抵消；非公理变更（注解、导入、登记本体）保持原位置。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`kernel` —— 内核领域代码（wp-kernel，纯 Java + OWL API 5）；附加：`rea`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P1-03` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`、`hexagonal-architecture`、`code-tour`
- 开工时可用的辅助 agent：`spec-miner`
- 提交前调用的审查 agent：`java-reviewer`、`type-design-analyzer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。

## 验收
```bash
./mvnw -q -pl wp-kernel test -Dtest=BS01_ChangeListMinimiserTest
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 与本体状态相关的过滤（P1-05）
