---
id: P6-19
title: 规划卡（第二轮）：结合 P6 已完成的卡，细化候选清单中尚未规划的扩展功能
phase: P6
module: tasks/P6
type: planning
size: S
work_type: planning
ecc_extra: [rea]
status: done
depends_on: [P6-05, P6-06, P6-07, P6-13, P6-16]
---

# P6-19 规划卡（第二轮）：细化剩余的扩展功能

## 目标
P6 第一轮（P6-00 展开的 P6-01 ~ P6-16，以及修复卡 P6-17、P6-18）已全部完成。本卡结合这些已完成的能力（outbox 中继、投影表、MCP 只读工具、权限感知的 Web 端），为 `tasks/P6/EPIC.md` 候选清单中其余尚未规划的条目提出第二轮优先级，并把它们展开为任务卡。移动端（P5）继续暂停。

## 参考
- `tasks/P6/EPIC.md`（候选清单、第一轮的展开结果与共享文件规则）；`tasks/P5/EPIC.md`、`tasks/P5/P5-00-mobile-planning.md`（暂停说明）
- ADR-006、ADR-007、ADR-010、ADR-013；`docs/03-plan/mvp-scope.md`（“暂不纳入”清单）
- 第一轮的交付：P6-08 outbox 中继（消费者 SPI）、P6-09 ~ P6-13 投影表（含只读账号说明）、P6-14 ~ P6-16 MCP 只读工具、P6-01 ~ P6-07 权限感知与成员查找
- 旧工程（只读）：`webhook/`、`entity/MergeEntitiesChangeListGenerator.java`、`merge/`、`merge_add/`、`tag/`、`issues/`、`watches/`、`form/`、`mansyntax/`、`shared-core/.../access/BuiltInRole.java`
- `AGENTS.md` 第 7 节

## 产出
- `docs/01-rea/behavior-specs.md`：新增 BS-13（合并实体）、BS-14（上传并合并到已有项目）、BS-15（项目 webhook），并在 BS-10 中记录第二轮新增的三个动作及其角色归属。
- `tasks/P6/EPIC.md`：新增「第二轮规划（P6-19）」一节——第二轮优先级（Agent 提出、待人确认）与理由、展开结果、依赖关系、共享文件与迁移号；P5 继续暂停的说明。
- 新任务卡 P6-20 ~ P6-52（33 张；P6-49 ~ P6-52 是评审后从 P6-20、P6-27、P6-29、P6-37 拆出的卡）：
  - webhook：P6-20 存储与地址校验、P6-49 管理接口、P6-21 投递、P6-22 Web 端标签页；
  - 合并实体：P6-23 内核生成器、P6-24 命令与接口、P6-25 Web 端；
  - 上传并合并：P6-26 内核计划、生成器与修订号前置条件、P6-27 动作、暂存与本体列表、P6-50 预览与应用接口、P6-28 Web 端；
  - 删除用户：P6-29 后端、P6-52 会话立即失效、P6-30 Web 端；
  - 实例库：P6-31 ~ P6-33 PoC（表结构与写入、规模基准与通过标准、由投影表生成校验规则）、P6-34 服务化规划卡；
  - outbox → Kafka：P6-35；outbox → 图数据库：P6-36 评估规划卡；
  - 企业登录：P6-37 外部身份绑定、P6-51 通用 OIDC 登录、P6-38 登录页入口、P6-39 企业微信（阻塞：外部条件）；
  - 多实例部署：P6-40 规划卡；
  - 前端升级：P6-41 Vue 3.6（阻塞：尚未正式发布）、P6-42 TypeScript 7 评估；
  - 逆向分析规划卡：P6-43 表单、P6-44 讨论与评论、P6-45 关注与通知、P6-46 标签、P6-47 Manchester 语法；
  - MCP 写工具：P6-48 规划卡。
- `docs/03-plan/roadmap.md`：P6 一行补充第二轮规划；`tasks/P5/EPIC.md`：补充“第二轮规划时继续暂停”的说明。

## 实现要点
- 第二轮优先级是 Agent 依据第一轮交付提出的**建议**，人确认后才算定稿（“人已确认优先级”由人勾选）；人改了优先级时用 `python tools/task.py reject P6-19 --reason "…"` 退回重排。
- 需要逆向分析的条目（表单、讨论、关注、标签、Manchester 语法）本轮只建规划卡，不直接拆实现卡；依赖外部条件的条目（Vue 3.6 正式版、企业微信凭据）建卡并标为 `blocked`，写明恢复条件。
- 涉及新存储或新依赖的条目，第一张卡为 PoC 或评估卡，并附可量化的通过标准（实例库 P6-32、图数据库 P6-36、多实例 P6-40、MCP 写工具 P6-48）。
- 规划卡一次只做一张，避免新卡编号、EPIC 与 Flyway 版本号冲突（EPIC「规划卡的编号规则」）。
- 共享文件按依赖串行：OpenAPI 生成物 P6-49 → P6-24 → P6-27 → P6-50 → P6-29 → P6-51；Flyway V6（P6-20）、V7（P6-29）、V8（P6-37）；详见 EPIC。

## ECC 技能
工作项类型：`planning` —— 规划卡（产出任务卡，不写业务代码）；附加：`rea`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-19` 查看。

- 开工前加载的 skill：`blueprint`、`intent-driven-development`、`code-tour`
- 开工时可用的辅助 agent：`spec-miner`
- 提交前调用的审查 agent：`planner`、`architect`
- 注意：blueprint 产出的步骤必须转写成 tasks/TEMPLATE.md 的格式，并通过 plan_lint；不要另建 plans/ 目录。
- 注意：spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。

## 验收
```bash
python tools/plan_lint.py --check
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 新卡片已通过评审
- [x] 人已确认优先级
- [x] 执行记录已填写

## 不在本卡范围
- 写任何业务代码
- 恢复移动端（P5）；`docs/03-plan/mvp-scope.md` 中列为“暂不纳入”但不在 P6 候选清单中的条目（可视化 `viz/`、批量操作 `bulkop/`、CSV 导入 `csv/`、OBO 视图 `obo/`、视图布局 `perspective/`），需要时由人先加入候选清单

## 执行记录
- 日期：2026-11-15
- 分支：docs/p6-19-extensions-round2-planning
- 测试：2026-11-15T21:54:32+08:00 通过（2 项）
- 实际改动：按人的要求建规划分支、P5 继续暂停，并结合 P6 第一轮已完成的 outbox 中继、投影表、MCP 只读工具规划其余候选项：新增 BS-13 合并实体、BS-14 上传合并、BS-15 webhook 及 BS-10 第二轮动作；EPIC 增加第二轮规划（建议优先级、依赖、共享文件与 Flyway V6~V8、规划卡编号规则）；新建 P6-20~P6-52 共 33 张卡（实现卡、PoC/评估卡与 9 张规划卡，P6-39 企业微信与 P6-41 Vue 3.6 因外部条件标为 blocked）；路线图与 P5 EPIC 记录继续暂停。
- 偏差：P6-19 不在 main 上，无法用 task.py start：按 task.py 的命名规则从 origin/main 在本地建分支 docs/p6-19-extensions-round2-planning，推送由 task.py commit 完成。候选清单原写“上传并合并对应 merge_add”，调研发现旧系统 merge/ 才是新版本差异合并，两种都纳入 BS-14。planner 与 architect 评审后拆出 P6-49~P6-52（避免超过 400 行），并修正：webhook 并发投递与防重令牌、停用时取消待投递；预览修订号改在生成锁内校验（P6-26 给 ChangePipeline 增加 expectedHead 重载）；合并实体从本体解析类型、固定检查顺序、弃用时保留源声明、同名标签不误删；删除用户时“已删除”优先于“需重置密码”、会话失效拆为 P6-52；OIDC 按提供方开关且系统管理员不自动绑定；Kafka 阻塞上限；实例库写入门禁、区间查询与并发约束；定义域取交集；规划卡一次只做一张。改动了卡片模块之外的 tasks/P5、docs/01-rea、docs/03-plan。“人已确认优先级”待人勾选。
- 审查：planner, architect
