---
id: P4-00
title: 规划卡：细化 Web 端（Vue）MVP 任务卡
phase: P4
module: tasks/P4
type: planning
size: S
work_type: planning
ecc_extra: []
status: todo
depends_on: [P3-24, P3-27]
---

# P4-00 规划卡：细化 Web 端（Vue）MVP 任务卡

## 目标
把 `tasks/P4/EPIC.md` 中的每一项展开为可以直接开工的任务卡。

## 参考
- `tasks/P4/EPIC.md`（卡片清单与验收要点）
- ADR-002（前端版本）、ADR-008
- `docs/api/openapi.json`（P3-23）、`packages/api-types`（P3-27）
- `AGENTS.md` 第 7 节
- `.cursor/skills/wp-ecc-routing/SKILL.md`（`web` 类工作项使用的 ECC skill）

## 产出
- `tasks/P4/` 下的任务卡 P4-01 起，按 EPIC 中的顺序编号；每张卡使用 `tasks/TEMPLATE.md` 的全部小节，frontmatter 的 `work_type` 为 `web` 或 `e2e`。
- 每张卡的「产出」写到组件、路由、composable 和文件路径一级（例如 `wp-web/src/features/hierarchy/HierarchyTree.vue`），并写明调用哪些 API（以 `docs/api/openapi.json` 中的 operationId 为准）。
- 每张卡至少有一条验收命令：`pnpm --filter wp-web exec vitest run <文件>` 或 `pnpm --filter wp-web exec playwright test <spec>`。
- P4-01（工程骨架）中写明：`wp-web` 的 `verify` 脚本依次执行 `vue-tsc --noEmit`、ESLint、`vitest run`、`vite build`；`tools/task.py test` 会调用它。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`planning` —— 规划卡（产出任务卡，不写业务代码）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P4-00` 查看。

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
- 写任何前端代码
