---
id: P6-45
title: 规划卡：关注（Watches）与邮件通知逆向分析，确定首期范围并拆卡
phase: P6
module: tasks/P6
type: planning
size: S
work_type: planning
ecc_extra: [rea]
status: done
depends_on: [P6-21]
---

# P6-45 规划卡：关注与通知逆向分析与拆卡

## 目标
对旧系统的“关注实体 / 关注分支 + 邮件通知”做逆向分析，写出规格与首期范围，并展开为任务卡。

## 参考
- 旧工程（只读）：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/watches/`（约 14 个文件）、`../webprotege/webprotege-shared/src/main/java/edu/stanford/bmir/protege/web/shared/watches/`（约 14 个）、客户端约 6 个
- 已知要点（P6-19 调研）：`Watch{userId, entity, type ENTITY|BRANCH}`，Mongo 集合 `Watches`；`WatchManagerImpl` 监听 Frame 变化事件，按层级求出被改实体的祖先（`IndirectlyWatchedEntitiesFinder`），分支关注匹配祖先、实体关注只匹配自身；邮件只发给有 `VIEW_PROJECT` 的用户，**不排除**修改者本人；模板 `watch-notification-email-template.html`；权限 `WATCH_CHANGES`
- 新系统可复用的能力：P6-08 outbox 中继与 P6-21 的“登记 + 独立投递 + 重试”模式；BS-08 层级视图（求祖先）；BS-11 事件
- 新系统目前没有邮件发送能力（需要 SMTP 配置，可能新增 `spring-boot-starter-mail` 依赖）；移动端推送随 P5 暂停
- `AGENTS.md` 第 7 节

## 产出
- `docs/01-rea/watches.md`：五层逆向结论与代码出处。
- `docs/01-rea/behavior-specs.md` 追加关注相关的 BS-xx 规格（是否排除修改者本人、同一修订多次命中时是否合并为一封邮件等，按新系统的取舍写明）。
- 在 `tasks/P6/EPIC.md` 中写明首期范围与理由，至少决定：邮件发送能力作为独立的卡（与讨论 P6-44 共用）、通知的触发源（outbox 消费者按修订计算受影响实体，而不是进程内事件）、退订与频率控制、站内通知是否进入首期。
- 编写任务卡（编号接续当时的最大编号）；涉及新依赖（邮件）的第一张卡写明依赖名称与理由。

## 实现要点
- 规划卡一次只做一张：开工前确认没有其它规划卡处于 `in_progress`；新卡编号取开工时 main 上的最大编号 + 1，新的 Flyway 版本号取当时 main 上已分配的最大版本 + 1（见 EPIC「第二轮规划」中的“规划卡的编号规则”）。
- 计算“哪些关注被触发”需要读内存模型（层级），按 P6-12 的做法在模型未应用该修订时抛出 `OutboxRetryLater`；拆卡时写进相应卡片。

## ECC 技能
工作项类型：`planning` —— 规划卡（产出任务卡，不写业务代码）；附加：`rea`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-45` 查看。

- 开工前加载的 skill：`blueprint`、`intent-driven-development`、`code-tour`
- 开工时可用的辅助 agent：`spec-miner`
- 提交前调用的审查 agent：`planner`、`architect`
- 注意：blueprint 产出的步骤必须转写成 tasks/TEMPLATE.md 的格式，并通过 plan_lint；不要另建 plans/ 目录。
- 注意：spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。

## 验收
```bash
python tools/plan_lint.py --check
test -s docs/01-rea/watches.md
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 新卡片已通过评审
- [x] 人已确认首期范围
- [x] 执行记录已填写

## 不在本卡范围
- 写任何业务代码；移动端推送通知（P5 暂停）

## 执行记录
- 日期：2026-11-23
- 分支：docs/p6-45-watches-rea-planning
- 测试：2026-11-23T13:15:24+08:00 通过（3 项）
- 实际改动：完成关注（Watches）五层逆向与首期拆卡：docs/01-rea/watches.md、BS-21/22、EPIC「关注（P6-45）」决定（独立 SMTP 卡、outbox 按修订触发、排除作者、每用户每修订一封、不做站内通知）；实现卡 P6-83~P6-91（邮件 V12、关注 V13、权限、API、事件、outbox 匹配、Web、迁移进出）。审查后钉死受影响实体=revision_subjects 语义、匿名个体拒绝、读锁后再入队等。
- 偏差：无
- 审查：planner, (APPROVE, after, fixes), architect, (APPROVE;, MEDIUM, RevisionHistoryQueries, note, applied)
