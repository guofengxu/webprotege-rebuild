---
id: P6-40
title: 规划卡：多实例部署（按项目路由），产出 ADR 草案与拆卡
phase: P6
module: tasks/P10
type: planning
size: S
work_type: planning
ecc_extra: [concurrency]
status: cancelled
depends_on: [P6-52, P6-27, P6-21]
---

# P6-40 规划卡：多实例部署

## 目标
设计编辑器的多实例部署：请求按项目路由到加载了该项目的实例，保证“每个项目单写者”（ADR-006）在多实例下仍然成立；把方案写成 ADR 草案，并展开为任务卡。拆出的任务卡放在新阶段目录 `tasks/P10/`。

## 参考
- ADR-005（内存读模型）、ADR-006（每个项目单写者）、ADR-007（REST + 命令总线 + SSE）、ADR-009（Lucene 本地索引）
- 已经支持多实例的部分：P6-08 outbox 中继（`for update skip locked`）、P6-21 webhook 投递（认领 + 租期）、P6-12 投影（项目级咨询锁）
- 只在单实例下成立的部分：P3-03 `ProjectRuntime` 注册表（Caffeine，进程内）、P3-21 / P6-03 事件订阅（进程内）、P3-19 Lucene 索引（本地磁盘）、P6-52 会话注册表与“已删除”检查的缓存（进程内）、P6-27 上传暂存（本地磁盘）、Web 会话（servlet 会话，进程内）
- `tasks/P10/EPIC.md`（本卡拆出的任务卡所在的阶段目录）；`tasks/P6/EPIC.md`「规划卡暂缓与新阶段目录」
- `AGENTS.md` 第 7 节

## 产出
- 新 ADR 草案（编号取当时的下一个，状态“提议”）至少回答：
  1. 项目归属：哪个实例持有哪个项目的运行时（数据库中的归属表 + 租约、PostgreSQL 咨询锁，或外部协调服务），归属转移与故障接管的步骤，如何保证任何时刻至多一个实例写同一项目（两个实例同时持有时，修订号冲突 WP-5 是最后一道防线）；
  2. 路由：网关按项目 ID 路由（路径或请求头），非归属实例收到请求时转发还是重定向；SSE 与长轮询如何路由；
  3. 会话：是否引入 Spring Session（JDBC，新依赖），P6-52 的会话失效如何跨实例生效；
  4. 搜索索引：归属转移时 Lucene 索引在新实例上重建还是共享存储；
  5. 上传暂存（P6-27）与其它本地文件；
  6. 运维：健康检查、滚动发布时的项目迁移、监控指标。
- 在 `tasks/P10/EPIC.md` 中记录 ADR 草案的结论与拆卡清单。
- 在 `tasks/P10/` 下展开为任务卡（编号从 P10-01 起；P6 卡号已用到 P6-99）；第一张卡为 PoC，附可量化的通过标准（例如：故障接管时间、两实例并发写同一项目时没有双写、归属转移期间编辑的失败率）。

## 实现要点
- 规划卡一次只做一张：开工前确认没有其它规划卡处于 `in_progress`；新卡编号取开工时 main 上 `tasks/P10/` 中的最大编号 + 1（从 P10-01 起），新的 Flyway 版本号、BS 编号与 ADR 编号仍取当时 main 上已分配的最大值 + 1（见 `tasks/P6/EPIC.md`「第二轮规划」中的“规划卡的编号规则”与「规划卡暂缓与新阶段目录」）。
- 不改 ADR-006；若方案需要改变“单写者”的含义，在新 ADR 中说明与 ADR-006 的关系，由人决定。

## ECC 技能
工作项类型：`planning` —— 规划卡（产出任务卡，不写业务代码）；附加：`concurrency`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-40` 查看。

- 开工前加载的 skill：`blueprint`、`intent-driven-development`
- 提交前调用的审查 agent：`planner`、`architect`、`silent-failure-hunter`
- 注意：blueprint 产出的步骤必须转写成 tasks/TEMPLATE.md 的格式，并通过 plan_lint；不要另建 plans/ 目录。
- 注意：审查重点：锁顺序（ADR-006）、WP-1 ~ WP-8（docs/01-rea/write-path.md）。

## 验收
```bash
python tools/plan_lint.py --check
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 新卡片已通过评审
- [ ] 人已确认 ADR 草案的方向
- [ ] 执行记录已填写

## 不在本卡范围
- 写任何业务代码；修改已接受的 ADR

## 阻塞
- 日期：2026-11-24
- 原因：人决定 P6 中尚未开工的规划卡（P6-40、P6-47、P6-48、P6-63）统一暂缓，标为 `blocked`。这不是卡片本身的问题。本卡的依赖 P6-52 尚未完成，P6-52 合并后本卡会进入“可以开工”列表，并按编号排在 P6-55 等实现卡之前被选中（`python tools/task.py next` 与 `AGENTS.md` 第 1 节取编号最小的卡）；而且 P6 卡号已用到 P6-99，本卡拆出的卡不能再放进 P6。决定见 `tasks/P6/EPIC.md`「规划卡暂缓与新阶段目录」。
- 两位阶段号：已支持（2026-11-24，人决定修改卡号定义并保留 P10），`P10-01` 起的卡与对应分支可以通过校验，见 `tasks/P6/EPIC.md`「两位阶段号」。
- 恢复方式：P6-52 完成后，人决定开始本卡时，把本卡 `status` 改回 `todo` 并删除本节，同时撤销 `tasks/P10/EPIC.md` 顶部的暂缓说明；合并后按 `python tools/task.py start P6-40` 开工。
- 影响：没有卡依赖本卡，P6 实现卡的依赖关系不变；编辑器继续只支持单实例部署（`tasks/P6/EPIC.md`「多实例现状」）。

## 取消
- 日期：2026-11-28
- 决定：人决定把状态为“阻塞”的规划卡（P5-00、P6-40、P6-47、P6-48、P6-63）统一改为取消（`status: cancelled`）。上面的「阻塞」小节保留作历史记录，其中的“恢复方式”不再适用。决定见 `tasks/P6/EPIC.md`「阻塞的规划卡改为取消」。
- 影响：没有卡依赖本卡，实现卡的依赖关系不变；`python tools/task.py next` 与 `tasks/INDEX.md`「现在可以开工」「阻塞中」都不再列出本卡。`tasks/P10/EPIC.md` 的预留目录与候选项保留，目前没有卡片。
- 重新启动：由人决定。把本卡 `status` 改回 `todo`，删除「阻塞」与「取消」两节，并撤销 `tasks/P10/EPIC.md` 顶部的说明；也可以另开一张新的规划卡。
