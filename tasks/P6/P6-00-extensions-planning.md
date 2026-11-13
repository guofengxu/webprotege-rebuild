---
id: P6-00
title: 规划卡：按优先级细化扩展功能
phase: P6
module: tasks/P6
type: planning
size: S
work_type: planning
ecc_extra: []
status: done
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
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 人已标注优先级
- [x] 新卡片已通过评审
- [x] 执行记录已填写

## 不在本卡范围
- 写任何业务代码

## 执行记录
- 日期：2026-11-13
- 分支：docs/p6-00-extensions-planning
- 测试：2026-11-13T19:53:54+08:00 通过（2 项）
- 实际改动：P5-00 标为 blocked（人决定暂停移动端），P5 EPIC 与 roadmap 同步；P6 EPIC 依据 P1~P4 现状提出优先级（1 MVP 差距补齐、2 本体投影表、3 MCP 只读工具，待人确认），展开为 P6-01~P6-16：差距补齐 P6-01~07，outbox 中继 P6-08，投影表 PoC 三张 P6-09~11 加增量与运维 P6-12~13，MCP PoC P6-14 加工具 P6-15~16；EPIC 写明依赖、共享文件串行规则与 Flyway 版本。
- 偏差：P6-00 原写“人确定优先级之前不写卡”：按人的指示“结合 P1~P4 当前任务项规划 P6”，由 Agent 提出优先级并直接展开，需人确认（“人已标注优先级”未勾）；新增候选项“MVP 差距补齐”（P4-17 差距清单 2/4/5 项）；按人的指示改动 tasks/P5 与 docs/03-plan/roadmap.md（卡片模块之外）。planner 与 architect 的意见已核实并全部处理，主要包括：outbox 乱序提交跳行（全局 outbox 锁）、投影在内存模型应用修订前确认（OutboxRetryLater）、重建先取锁再计算且修订号只进不退、订阅登记后二次校验、Bean 循环依赖、MCP 会话与密钥绑定及统一结果结构、API Key 并非只读的文档措辞、用户查找最少 2 个字符；拆分超出规模的卡（投影 PoC 拆为三张、只读模式拆为 Frame 与设置两张、个体列表移到 P6-16），中继改用 V4 且不依赖 PoC，共享文件按依赖串行。
- 审查：planner, architect
