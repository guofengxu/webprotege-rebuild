---
id: P5-00
title: 规划卡：细化移动端（uni-app）MVP 任务卡
phase: P5
module: tasks/P5
type: planning
size: S
work_type: planning
ecc_extra: []
status: todo
depends_on: [P3-24, P3-27]
---

# P5-00 规划卡：细化移动端（uni-app）MVP 任务卡

## 目标
把 `tasks/P5/EPIC.md` 中的每一项展开为可以直接开工的任务卡。

## 参考
- `tasks/P5/EPIC.md`
- ADR-014、ADR-010（移动端令牌）、ADR-007（长轮询）
- `packages/api-types`（P3-27）
- `.cursor/rules/31-uniapp.mdc`
- `AGENTS.md` 第 7 节

## 产出
- `tasks/P5/` 下的任务卡 P5-01 起；每张卡使用 `tasks/TEMPLATE.md` 的全部小节，`work_type` 为 `mobile` 或 `e2e`。
- 每张卡写到页面（`wp-mobile/src/pages/...`）、组件、composable 和文件路径一级；涉及平台差异的地方写明条件编译范围（`#ifdef H5` / `#ifdef APP-PLUS`）。
- 每张卡至少有一条验收命令：`pnpm --filter wp-mobile exec vitest run <文件>`、`pnpm --filter wp-mobile run build:h5` 或 H5 的 Playwright 用例。
- P5-01（工程骨架）中验证并记录：uni-app CLI 工程在 pnpm 工作区中能否正常安装与构建（ADR-014 风险），以及 `wp-mobile` 的 `verify` 脚本内容。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`planning` —— 规划卡（产出任务卡，不写业务代码）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P5-00` 查看。

- 开工前加载的 skill：`blueprint`、`intent-driven-development`
- 提交前调用的审查 agent：`planner`、`architect`
- 注意：blueprint 产出的步骤必须转写成 tasks/TEMPLATE.md 的格式，并通过 plan_lint；不要另建 plans/ 目录。

## 验收
```bash
python tools/plan_lint.py --check
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] EPIC 中每一项都有对应的卡片
- [ ] 人已评审新卡片
- [ ] 执行记录已填写

## 不在本卡范围
- 写任何移动端代码
