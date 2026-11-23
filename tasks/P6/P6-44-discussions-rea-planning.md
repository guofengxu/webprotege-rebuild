---
id: P6-44
title: 规划卡：讨论与评论（实体级讨论线程）逆向分析，确定首期范围并拆卡
phase: P6
module: tasks/P6
type: planning
size: S
work_type: planning
ecc_extra: [rea]
status: done
depends_on: [P6-03]
---

# P6-44 规划卡：讨论与评论逆向分析与拆卡

## 目标
对旧系统的实体讨论功能做逆向分析，写出规格与首期范围，并展开为任务卡。

## 参考
- 旧工程（只读）：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/issues/`（约 20 个文件）、`../webprotege/webprotege-shared/src/main/java/edu/stanford/bmir/protege/web/shared/issues/`（约 63 个，其中 GitHub 风格的 `Issue`、`Milestone` 等是死代码）、客户端约 25 个
- 已知要点（P6-19 调研）：`EntityDiscussionThread{id, projectId, entity, status OPEN|CLOSED, comments[]}`、`Comment{id, createdBy, createdAt, updatedAt, body(markdown), renderedBody}`，Mongo 集合 `EntityDiscussionThreads`；权限 `VIEW_OBJECT_COMMENT`、`CREATE_OBJECT_COMMENT`、`EDIT_OWN_OBJECT_COMMENT`（服务端没有校验作者）、`SET_OBJECT_COMMENT_STATUS`；`@user`、`#123`、`R123` 提及；`CommentNotificationEmailer` 的收件人包括项目中所有非访客用户；事件 `CommentPosted`、`CommentUpdated`、`DiscussionThreadCreated`、`DiscussionThreadStatusChanged`；Slack 通知（BS-15 中未移植）
- BS-10：`CAN_COMMENT` 角色目前只是占位；BS-11：高层事件；BS-13：合并实体时旧系统会转移讨论线程（新系统暂未移植）
- P3-21 / P6-03：事件推送与订阅；Web 端对事件联合类型做了穷尽检查（新增事件类型需要前后端同步）
- `AGENTS.md` 第 7 节

## 产出
- `docs/01-rea/discussions.md`：五层逆向结论与代码出处。
- `docs/01-rea/behavior-specs.md` 追加讨论相关的 BS-xx 规格；同时修正旧系统的已知缺陷（例如编辑他人评论没有服务端校验）作为“新系统新增”。
- 在 `tasks/P6/EPIC.md` 中写明首期范围与理由，至少决定：Markdown 渲染与 XSS 防护的位置（服务端还是客户端）、提及的范围、邮件通知是否进入首期（与关注 P6-45 共用一套邮件发送能力）、新事件类型如何接入 SSE 与 Web 端、合并实体（P6-24）时线程是否随之转移、旧数据迁移。
- 编写任务卡（编号接续当时的最大编号）：存储（Flyway）、命令与接口、事件、Web 端讨论面板、旧数据迁移等；新卡单模块、一天内可完成、有可执行的验收命令。

## 实现要点
- 规划卡一次只做一张：开工前确认没有其它规划卡处于 `in_progress`；新卡编号取开工时 main 上的最大编号 + 1，新的 Flyway 版本号取当时 main 上已分配的最大版本 + 1（见 EPIC「第二轮规划」中的“规划卡的编号规则”）。
- 新增事件类型会触发 Web 端的穷尽检查，拆卡时把“后端事件 + `@wp/api-types` + Web 端处理”放在相邻的卡并写清依赖。

## ECC 技能
工作项类型：`planning` —— 规划卡（产出任务卡，不写业务代码）；附加：`rea`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-44` 查看。

- 开工前加载的 skill：`blueprint`、`intent-driven-development`、`code-tour`
- 开工时可用的辅助 agent：`spec-miner`
- 提交前调用的审查 agent：`planner`、`architect`
- 注意：blueprint 产出的步骤必须转写成 tasks/TEMPLATE.md 的格式，并通过 plan_lint；不要另建 plans/ 目录。
- 注意：spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。

## 验收
```bash
python tools/plan_lint.py --check
test -s docs/01-rea/discussions.md
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 新卡片已通过评审
- [x] 人已确认首期范围
- [x] 执行记录已填写

## 不在本卡范围
- 写任何业务代码；GitHub 风格的 Issue / Milestone（旧系统中是死代码）

## 执行记录
- 日期：2026-11-23
- 分支：docs/p6-44-discussions-rea-planning
- 测试：2026-11-23T11:16:20+08:00 通过（3 项）
- 实际改动：逆向实体讨论并写入 discussions.md / BS-19~20；EPIC 锁定首期范围（服务端 Markdown+消毒、仅 @user、邮件留给 P6-45、SSE 同 head 事件、删除合并转移线程、迁移）；拆出 P6-74~P6-82。按 planner/architect 意见拆出渲染器卡、固定导入重渲、hub 同 head 唤醒与删除权限门。
- 偏差：相对初稿增补 P6-82 渲染器卡；删除统一走 EDIT_OWN_OBJECT_COMMENT+作者校验；导入必须重渲消毒；P6-77 须推广 ProjectEventHub 同 head 唤醒
- 审查：planner, architect
