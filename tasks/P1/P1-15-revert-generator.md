---
id: P1-15
title: 回滚修订生成器（BS-04）
phase: P1
module: wp-kernel
type: impl
size: S
work_type: kernel
ecc_extra: [rea]
status: todo
depends_on: [P1-11]
---

# P1-15 回滚修订生成器（BS-04）

## 目标
可以把任意一个历史修订撤销为一个新修订。

## 参考
- BS-04
- 旧实现：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/change/RevisionReverterChangeListGenerator.java`
- 旧测试：`../webprotege/webprotege-server-core/src/test/java/edu/stanford/bmir/protege/web/server/change/RevisionReverterChangeListGenerator_TestCase.java`

## 产出
- `wprebuild.kernel.generator.RevertResult`：`record RevertResult(boolean reverted, Optional<String> reason)`。
- `wprebuild.kernel.generator.RevertRevisionGenerator implements ChangeListGenerator<RevertResult>`：构造参数为 `ProjectId`、`RevisionNumber target`、`RevisionLog`；变更 = 目标修订的逆变更倒序排列；描述为 `Reverted revision <N>`；目标不存在或含 `DeclareOntology` 时返回空变更，并给出原因。
- 测试 `BS04_RevertRevisionGeneratorTest`：移植旧测试；场景：修订 2 添加 A、B，修订 3 删除 A，回滚修订 2 后，产生的修订只包含“删除 B”（A 已不存在，被 BS-02 过滤）；回滚含 `DeclareOntology` 的修订 1 被拒绝；目标不存在被拒绝。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`kernel` —— 内核领域代码（wp-kernel，纯 Java + OWL API 5）；附加：`rea`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P1-15` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`、`hexagonal-architecture`、`code-tour`
- 开工时可用的辅助 agent：`spec-miner`
- 提交前调用的审查 agent：`java-reviewer`、`type-design-analyzer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。

## 验收
```bash
./mvnw -q -pl wp-kernel test -Dtest=BS04_RevertRevisionGeneratorTest
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 回滚 API 与权限（P3-20）
