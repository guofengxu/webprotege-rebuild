---
id: P5-00
title: 规划卡：细化移动端（uni-app）MVP 任务卡
phase: P5
module: tasks/P5
type: planning
size: S
work_type: planning
ecc_extra: []
status: blocked
depends_on: [P3-24, P3-27]
---

# P5-00 规划卡：细化移动端（uni-app）MVP 任务卡

## 目标
把 `tasks/P5/EPIC.md` 中的每一项展开为可以直接开工的任务卡。

## 参考
- `tasks/P5/EPIC.md`
- ADR-014、ADR-010（移动端令牌）、ADR-007（长轮询）
- `frontend/packages/api-types`（P3-27）及 P3-27 执行记录中的 uni-app 安装验证结果
- `docs/02-architecture/overview.md` 第 1 节（uni-app 安装方式）
- `.cursor/rules/31-uniapp.mdc`
- `AGENTS.md` 第 7 节

## 产出
- `tasks/P5/` 下的任务卡 P5-01 起；每张卡使用 `tasks/TEMPLATE.md` 的全部小节，`work_type` 为 `mobile` 或 `e2e`。
- 每张卡写到页面（`frontend/wp-mobile/src/pages/...`）、组件、composable 和文件路径一级；涉及平台差异的地方写明条件编译范围（`#ifdef H5` / `#ifdef APP-PLUS`）。
- 每张卡至少有一条验收命令：`pnpm -C frontend/wp-mobile exec vitest run <文件>`、`pnpm -C frontend/wp-mobile run build:h5` 或 H5 的 Playwright 用例。命令用目录形式而不是 `--filter`，wp-mobile 在不在工作区里都能运行。
- P5-01（工程骨架）中写明：按 P3-27 的验证结果，wp-mobile 作为工作区成员安装，或单独安装（`pnpm -C frontend/wp-mobile install`；wp-mobile 有自己的 `pnpm-workspace.yaml`、`.npmrc` 与 `pnpm-lock.yaml`，验收中检查依赖装在 `frontend/wp-mobile/node_modules` 而不是 `frontend/node_modules`）；`@wp/api-types` 用 `"link:../packages/api-types"` 引用；不得为了 uni-app 把整个工作区改成 `node-linker=hoisted`；以及 `wp-mobile` 的 `verify` 脚本内容。

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

## 阻塞
- 日期：2026-11-13
- 原因：人决定移动端（P5）暂时停止开发，在 P6-00 中记录。这不是卡片本身的问题：卡片内容保持不变，依赖（P3-24、P3-27）均已完成。
- 恢复方式：人决定恢复移动端开发时，把本卡 `status` 改回 `todo` 并删除本节，再按 `python tools/task.py start P5-00` 开工；同时撤销 `tasks/P5/EPIC.md` 顶部的暂停说明。
- 影响：P6 的卡片都不依赖 P5；`tasks/P6/EPIC.md` 中的“移动端：iOS 与微信小程序”“移动端推送通知”随 P5 一起暂缓。后端已完成的移动端令牌（P3-26）、长轮询（P3-21）与 `@wp/api-types`（P3-27）保持原样，不回退。
