---
id: P6-36
title: 规划卡：评估 outbox → 图数据库 / RDF 三元组库发布器（按 ADR-013 第 5 节的条件，产出 ADR 草案或“暂不做”的结论）
phase: P6
module: tasks/P6
type: planning
size: S
work_type: planning
ecc_extra: []
status: todo
depends_on: [P6-32]
---

# P6-36 规划卡：图数据库发布器评估

## 目标
判断“把每个修订同步到图数据库（Neo4j + neosemantics）或 RDF 三元组库（Jena Fuseki、GraphDB）”现在是否值得做；值得做时写出 ADR 草案与 PoC 卡，不值得做时写明理由与重新评估的触发条件。

## 参考
- ADR-013 第 5 节：满足任一条件时另开 ADR 评估专门的图数据库或 RDF 库——需要标准 SPARQL 或 OWL 推理；AGE 的 PoC 中深层追溯 p95 超过 1 秒；关系边超过约 1 亿条且以多跳遍历为主；需要图算法
- P6-32 的报告 `docs/perf/instances-poc-<日期>.md`（递归 CTE 的深层追溯结果）
- P6-09 ~ P6-13（本体投影表，已经能用 SQL 回答层级与定义域 / 值域问题）；P6-14 ~ P6-16（MCP 只读工具，AI Agent 的另一条查询路径）；P6-35（Kafka 发布器，图数据库同步可以作为它的下游）
- `AGENTS.md` 第 7 节

## 产出
- 在 `tasks/P6/EPIC.md` 中记录评估结论：逐条对照 ADR-013 第 5 节的四个条件，写明当前是否满足及依据（P6-32 的数据、已知的 AI Agent 查询需求）。
- 结论为“做”时：
  - 新 ADR 草案（编号取当时的下一个，状态“提议”，**不修改 ADR-013**）：候选方案对比（Neo4j + n10s、Fuseki、GraphDB、继续用投影表 + AGE），同步方式（直接消费 outbox，或订阅 P6-35 的 Kafka 主题），映射规则（TBox 投影还是完整的 RDF 三元组），一致性与重建方式；
  - 第一张卡为 PoC，附可量化的通过标准（同步延迟、全量重建时间、代表性查询的 p95、新增依赖与运维成本）；
- 结论为“暂不做”时：写明重新评估的触发条件，候选清单中该条目保持未排期。

## 实现要点
- 规划卡一次只做一张：开工前确认没有其它规划卡处于 `in_progress`；新卡编号取开工时 main 上的最大编号 + 1，新的 Flyway 版本号取当时 main 上已分配的最大版本 + 1（见 EPIC「第二轮规划」中的“规划卡的编号规则”）。
- 不在本卡里引入任何依赖或写代码；ADR 草案由人确认后才能改为“已接受”。

## ECC 技能
工作项类型：`planning` —— 规划卡（产出任务卡，不写业务代码）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-36` 查看。

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
- [ ] 人已确认评估结论
- [ ] 执行记录已填写

## 不在本卡范围
- 写任何业务代码
