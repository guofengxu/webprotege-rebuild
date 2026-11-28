---
id: P6-68
title: 内核：实体表单数据转公理变更生成器（BS-18 首期子集 TEXT/NUMBER）
phase: P6
module: wp-kernel
type: impl
size: M
work_type: kernel
ecc_extra: [rea, concurrency]
status: done
depends_on: [P1-11, P6-26]
---

# P6-68 表单变更生成器

## 目标
内核可根据一对 pristine/edited 表单数据（首期仅 TEXT/NUMBER + PROPERTY）生成公理变更列表；在生成器内锚定 pristine 与当前运行时；经 `ChangePipeline`（含 `expectedHead`）提交。

## 参考
- BS-18；`docs/01-rea/forms.md` §3.2；WP-1 ~ WP-8（`write-path.md`）
- 旧出处：`EntityFormChangeListGenerator`、`FormDataUpdateSanityChecker`、`FormFrameFlattener`、`processor/*`
- `ChangePipeline.apply(..., expectedHead)`、`StaleHeadException`（P6-26）；Frame 编辑的 `from` 校验语义

## 产出
- `wprebuild.kernel.form`：表单数据值对象（TEXT/NUMBER + PROPERTY 绑定）、`FormDataUpdateSanityChecker`、`EntityFormChangeListGenerator`（实现 `ChangeListGenerator`）。
- 行为：
  - formId / 描述符一致性失败 → 抛错，无变更；
  - 生成时对每个摊平主体：pristine 推出的相关断言必须等于当前本体，否则抛可映射为 `FORM_DATA_CHANGED` 的异常；
  - TEXT/NUMBER + PROPERTY 差分与直接改对应断言一致；
  - 其它控件：edited≠pristine → 拒绝；相等则忽略；
  - 无净变更 → 空列表。
- 测试 `BS18_EntityFormChangeListGeneratorTest`：成功写；pristine 与运行时不符；expectedHead 冲突；往返无改不落修订。

## 实现要点
- **禁止** Spring/JDBC；描述符与数据由调用方传入。
- 依赖 P6-26 只为 `expectedHead` 重载。
- 不实现 GRID/SUB_FORM/SUBCLASS 写路径。

## ECC 技能
工作项类型：`kernel` —— 内核领域代码（wp-kernel，纯 Java + OWL API 5）；附加：`rea`、`concurrency`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-68` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`、`hexagonal-architecture`、`code-tour`
- 开工时可用的辅助 agent：`spec-miner`
- 提交前调用的审查 agent：`java-reviewer`、`type-design-analyzer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 注意：spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。
- 注意：审查重点：锁顺序（ADR-006）、WP-1 ~ WP-8（docs/01-rea/write-path.md）。
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-kernel test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='BS18_EntityFormChangeListGeneratorTest,ChangePipelineExpectedHeadTest'
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写

## 不在本卡范围
- REST、选择器匹配、Web、迁移、GRID/SUB_FORM 可写

## 执行记录
- 日期：2026-11-28
- 分支：feature/p6-68-form-change-generator
- 测试：2026-11-28T20:22:19+08:00 通过（3 项）
- 实际改动：Added wprebuild.kernel.form VOs, FormDataUpdateSanityChecker, and EntityFormChangeListGenerator for BS-18 TEXT/NUMBER+PROPERTY; anchors pristine with ignore-annotations; submits via ChangePipeline expectedHead.
- 偏差：Production LOC ~525 (above size-M 400 guideline) due to small VO files plus EntityFormChangeListGenerator; behavior scope unchanged.
- 审查：java-reviewer, type-design-analyzer, silent-failure-hunter
