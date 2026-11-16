---
id: P6-34
title: 规划卡：依据实例库 PoC 结论，规划实例库服务化
phase: P6
module: tasks/P6
type: planning
size: S
work_type: planning
ecc_extra: []
status: todo
depends_on: [P6-33]
---

# P6-34 规划卡：实例库服务化

## 目标
根据 P6-31 ~ P6-33 的结论，决定实例库以什么形态上线，并把它展开为任务卡。

## 参考
- ADR-013 第 4、5 节
- P6-32 的报告 `docs/perf/instances-poc-<日期>.md`（门禁结论）；P6-33 中列出的“需要扩展投影表”的事项
- P6-08（outbox 中继，消费者 SPI）、P6-13（投影状态接口与只读账号）
- `AGENTS.md` 第 7 节

## 产出
- 在 `tasks/P6/EPIC.md` 中记录决定，并编写任务卡（编号接续当时的最大编号），至少回答：
  1. 服务形态：独立的 Spring Boot 服务（新模块或新仓库）还是 wp-app 中的一个 Modulith 模块；与 ADR-013“独立服务、独立数据库”是否一致，不一致时需要新 ADR（不修改 ADR-013）；
  2. 写入接口：业务系统如何写入（REST 批量写入、幂等键、时间戳由谁给出），认证方式（复用 API Key 还是独立凭据）；
  3. 规则刷新：轮询 `project_state.rev_no` 还是订阅 outbox；本体修订后已有实例与新规则不符时怎么处理；
  4. 必填、基数、枚举取值：是否扩展投影表（需要新 ADR 与编辑器侧的卡）；
  5. 性能：若 P6-32 未达标，人选择的路线（索引、物化路径、Apache AGE、另开图数据库 ADR）；
  6. 部署与运维：数据库、备份、监控，经网络接口的端到端写入基准；
  7. 契约版本：实例库读取的投影表列是对外接口，服务化前是否在 `project_state` 等处加入契约版本号（需要编辑器侧的卡）。
- 每张新卡单模块、一天内可完成、有可执行的验收命令；涉及新依赖或新服务的第一张卡是 PoC。

## 实现要点
- 规划卡一次只做一张：开工前确认没有其它规划卡处于 `in_progress`；新卡编号取开工时 main 上的最大编号 + 1，新的 Flyway 版本号取当时 main 上已分配的最大版本 + 1（见 EPIC「第二轮规划」中的“规划卡的编号规则”）。
- P6-32 阻塞未解除时不要开工：先由人选定性能路线。

## ECC 技能
工作项类型：`planning` —— 规划卡（产出任务卡，不写业务代码）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-34` 查看。

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
- [ ] 人已确认服务形态
- [ ] 执行记录已填写

## 不在本卡范围
- 写任何业务代码
