---
id: P6-48
title: 规划卡：MCP 写工具与人工确认机制（设计、ADR 草案与拆卡）
phase: P6
module: tasks/P6
type: planning
size: S
work_type: planning
ecc_extra: [security]
status: todo
depends_on: [P6-16, P6-24]
---

# P6-48 规划卡：MCP 写工具与人工确认

## 目标
在 P6-14 ~ P6-16 的只读 MCP 工具之上，设计“写操作需要人工确认”的机制，决定首批开放哪些写工具，并展开为任务卡。

## 参考
- `tasks/P6/EPIC.md` 候选项“MCP 工具”：写操作需要人工确认，第一轮只做了只读工具
- ADR-007（命令 `record` 即 MCP 工具的参数结构）；P6-14 ~ P6-16：`web/mcp/`（API Key 认证、会话与密钥绑定、统一结果结构、结果大小限制）、`docs/api/mcp.md`
- P3-05：API Key 没有权限范围（不是只读密钥）；P3-07：权限只在命令总线校验
- 候选的写工具：创建 / 删除实体（P3-14）、修改 Frame（P3-16 ~ P3-18，带乐观并发）、合并实体（P6-24）、回滚（P3-20）
- `AGENTS.md` 第 7 节

## 产出
- 新 ADR 草案（编号取当时的下一个，状态“提议”）至少回答：
  1. 人工确认的形态：MCP 协议的 elicitation、两阶段“提议 → 在 Web 端由人批准 → 执行”（提议记录、过期时间、批准人的权限校验），或两者结合；
  2. 密钥范围：是否为 API Key 增加“只读 / 可写”范围（涉及 `access` 模块与 Flyway）；
  3. 审计：修订描述或元数据中如何标记“由 MCP 代理提出、由某人批准”；
  4. 防滥用：速率限制、单次变更规模上限、乐观并发（Frame 的 from / to）；
  5. 首批开放的写工具与各自的确认要求。
- 展开为任务卡（编号接续当时的最大编号）；第一张卡为 PoC（一个写工具走完整的确认流程），附可量化的通过标准。

## 实现要点
- 规划卡一次只做一张：开工前确认没有其它规划卡处于 `in_progress`；新卡编号取开工时 main 上的最大编号 + 1，新的 Flyway 版本号取当时 main 上已分配的最大版本 + 1（见 EPIC「第二轮规划」中的“规划卡的编号规则”）。
- 写工具同样只在命令总线上校验权限，确认机制不能绕过总线。
- 不修改 ADR-007、ADR-010；需要改变其含义时在新 ADR 中说明，由人决定。

## ECC 技能
工作项类型：`planning` —— 规划卡（产出任务卡，不写业务代码）；附加：`security`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-48` 查看。

- 开工前加载的 skill：`blueprint`、`intent-driven-development`、`security-review`
- 提交前调用的审查 agent：`planner`、`architect`、`security-reviewer`
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
- [ ] 人已确认确认机制的方向
- [ ] 执行记录已填写

## 不在本卡范围
- 写任何业务代码
