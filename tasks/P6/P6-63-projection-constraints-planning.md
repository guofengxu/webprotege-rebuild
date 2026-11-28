---
id: P6-63
title: 规划卡：扩展投影表以支持必填、基数、枚举取值（ADR 草案与拆卡）
phase: P6
module: tasks/P9
type: planning
size: S
work_type: planning
ecc_extra: []
status: cancelled
depends_on: [P6-33]
---

# P6-63 规划卡：投影约束扩展

## 目标
针对 P6-33 / P6-34 列出的缺口（必填属性、基数、枚举取值），决定是否扩展投影表、如何从 TBox 抽出这些约束，并展开为可执行的任务卡；需要时产出状态为「提议」的 ADR 草案（**不**修改已接受的 ADR-013）。拆出的任务卡放在新阶段目录 `tasks/P9/`。

## 参考
- P6-34 / EPIC 决定第 4 条；P6-33「不在本卡范围」与 `docs/api/projection.md` 实例库小节
- ADR-013 第 4 节原文（校验含必填、基数、枚举）
- P6-09 ~ P6-13 投影表与 outbox 增量更新路径
- `tasks/P9/EPIC.md`（本卡拆出的任务卡所在的阶段目录）；`tasks/P6/EPIC.md`「规划卡暂缓与新阶段目录」
- `AGENTS.md` 第 7 节；规划卡编号规则（EPIC）

## 产出
- 在 `tasks/P9/EPIC.md` 记录本卡结论（做 / 延后 / 不做的范围）。
- 若结论为“做”：新增 ADR 草案文件（编号取当时下一个，状态「提议」），至少回答：约束来源公理形态、投影表形状、对 `contract_version` 的影响、实例库校验语义（封闭世界）、与 OWL 开放世界的差异说明。
- 在 `tasks/P9/` 下展开任务卡（编号从 P9-01 起；P6 卡号已用到 P6-99）：编辑器投影抽出与写入、实例库校验、文档与契约版本递增；涉及新依赖的第一张为 PoC。
- 每张新卡单模块、一天内可完成、有可执行验收命令。

## 实现要点
- 规划卡一次只做一张：开工前确认没有其它规划卡 `in_progress`；新卡编号取开工时 main 上 `tasks/P9/` 中的最大编号 + 1（从 P9-01 起），新的 Flyway 版本号、BS 编号与 ADR 编号仍取当时 main 上已分配的最大值 + 1（见 `tasks/P6/EPIC.md`「规划卡暂缓与新阶段目录」）。
- 不得修改已接受的 ADR；异议写入阻塞小节。
- 首期实例库服务化（P6-55 ~ P6-62、P6-64）**不**依赖本卡完成。

## ECC 技能
工作项类型：`planning` —— 规划卡（产出任务卡，不写业务代码）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-63` 查看。

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
- [ ] 新卡片已通过评审
- [ ] 人已确认是否扩展投影表及 ADR 方向
- [ ] 执行记录已填写

## 不在本卡范围
- 写任何业务代码；修改 ADR-013

## 阻塞
- 日期：2026-11-24
- 原因：人决定 P6 中尚未开工的规划卡（P6-40、P6-47、P6-48、P6-63）统一暂缓，标为 `blocked`。这不是卡片本身的问题：本卡的依赖（P6-33）已完成。规划卡按编号会排在 P6-65、P6-68 等实现卡之前被选中（`python tools/task.py next` 与 `AGENTS.md` 第 1 节取编号最小的卡），打乱实现卡的推进顺序；而且 P6 卡号已用到 P6-99，本卡拆出的卡不能再放进 P6。决定见 `tasks/P6/EPIC.md`「规划卡暂缓与新阶段目录」。
- 恢复方式：人决定开始本卡时，把本卡 `status` 改回 `todo` 并删除本节，同时撤销 `tasks/P9/EPIC.md` 顶部的暂缓说明；合并后按 `python tools/task.py start P6-63` 开工。
- 影响：没有卡依赖本卡，P6 实现卡的依赖关系不变；首期实例库服务化（P6-55 ~ P6-62、P6-64）本来就不依赖本卡，校验仍只做定义域 / 值域 / 类与属性存在性。本卡拆出的卡放在 `tasks/P9/`，编号从 P9-01 起。

## 取消
- 日期：2026-11-28
- 决定：人决定把状态为“阻塞”的规划卡（P5-00、P6-40、P6-47、P6-48、P6-63）统一改为取消（`status: cancelled`）。上面的「阻塞」小节保留作历史记录，其中的“恢复方式”不再适用。决定见 `tasks/P6/EPIC.md`「阻塞的规划卡改为取消」。
- 影响：没有卡依赖本卡，实现卡的依赖关系不变；`python tools/task.py next` 与 `tasks/INDEX.md`「现在可以开工」「阻塞中」都不再列出本卡。`tasks/P9/EPIC.md` 的预留目录与候选项保留，目前没有卡片。
- 重新启动：由人决定。把本卡 `status` 改回 `todo`，删除「阻塞」与「取消」两节，并撤销 `tasks/P9/EPIC.md` 顶部的说明；也可以另开一张新的规划卡。
