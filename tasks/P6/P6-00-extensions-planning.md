---
id: P6-00
title: 规划卡：按优先级细化扩展功能
phase: P6
module: tasks/P6
type: planning
size: S
work_type: planning
ecc_extra: []
status: todo
depends_on: [P3-24]
---

# P6-00 规划卡：按优先级细化扩展功能

## 目标
由人确定 P6 的优先级后，把选中的条目展开为任务卡。

## 参考
- `tasks/P6/EPIC.md`
- ADR-013（实例库、本体投影表）
- `AGENTS.md` 第 7 节

## 产出
- 人在 `tasks/P6/EPIC.md` 中给条目标注优先级之后，为优先级最高的 1 ~ 3 项编写任务卡（P6-01 起）。涉及新存储或新服务的条目（例如实例库），第一张卡必须是 PoC，并附可量化的通过标准。

## 实现要点
- P6 的条目之间差异很大，请勿在人确定优先级之前编写卡片。

## ECC 技能
工作项类型：`planning` —— 规划卡（产出任务卡，不写业务代码）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-00` 查看。

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
- [ ] 人已标注优先级
- [ ] 新卡片已通过评审
- [ ] 执行记录已填写

## 不在本卡范围
- 写任何业务代码
