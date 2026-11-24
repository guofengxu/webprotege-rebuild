---
id: P6-46
title: 规划卡：标签（项目标签、实体标签、按条件自动打标签）逆向分析，确定首期范围并拆卡
phase: P6
module: tasks/P6
type: planning
size: S
work_type: planning
ecc_extra: [rea]
status: done
depends_on: [P3-19]
---

# P6-46 规划卡：标签逆向分析与拆卡

## 目标
对旧系统的标签功能做逆向分析，写出规格与首期范围，并展开为任务卡。

## 参考
- 旧工程（只读）：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/tag/`（约 11 个文件，另有 `webprotege-server-api` 中 3 个仓储接口）、`../webprotege/webprotege-shared/src/main/java/edu/stanford/bmir/protege/web/shared/tag/`（约 17 个）、客户端约 28 个
- 已知要点（P6-19 调研）：`Tag{id, projectId, label, description, color, backgroundColor, criteria}`、`EntityTags{projectId, entity, tags[]}`；实体的标签 = 显式标签 + 按条件命中的标签（`CriteriaBasedTagsManager` 使用 `MatchingEngine`，条件语言规模较大）；保存项目标签是整表替换，被删除的标签同时从实体上移除，并发出 `EntityTagsChangedEvent`、`ProjectTagsChangedEvent`；Mongo 集合 `Tags`（项目 + 标签名唯一）、`EntityTags`；权限 `EDIT_PROJECT_TAGS`（CAN_MANAGE）、`EDIT_ENTITY_TAGS`（旧系统只在客户端校验，服务端没有校验）
- P3-19：实体搜索（标签可能作为搜索过滤条件）；BS-10、BS-11
- `AGENTS.md` 第 7 节

## 产出
- `docs/01-rea/tags.md`：五层逆向结论与代码出处，单独评估 `MatchingEngine` 条件语言的规模与是否值得移植。
- `docs/01-rea/behavior-specs.md` 追加标签相关的 BS-xx 规格；修正旧系统缺失的服务端权限校验，作为“新系统新增”。
- 在 `tasks/P6/EPIC.md` 中写明首期范围与理由（建议：首期只做显式标签，按条件打标签另行评估），并编写任务卡（编号接续当时的最大编号）：存储（Flyway）、项目标签管理、实体打标签、事件、搜索过滤、Web 端、旧数据迁移。

## 实现要点
- 规划卡一次只做一张：开工前确认没有其它规划卡处于 `in_progress`；新卡编号取开工时 main 上的最大编号 + 1，新的 Flyway 版本号取当时 main 上已分配的最大版本 + 1（见 EPIC「第二轮规划」中的“规划卡的编号规则”）。
- 标签不是本体内容，不产生修订；它和项目设置一样存放在编辑器数据库中。拆卡时写明这一点，以及标签变化如何通知其它客户端（新的高层事件类型需要前后端同步，见 P6-44 中的同类说明）。

## ECC 技能
工作项类型：`planning` —— 规划卡（产出任务卡，不写业务代码）；附加：`rea`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-46` 查看。

- 开工前加载的 skill：`blueprint`、`intent-driven-development`、`code-tour`
- 开工时可用的辅助 agent：`spec-miner`
- 提交前调用的审查 agent：`planner`、`architect`
- 注意：blueprint 产出的步骤必须转写成 tasks/TEMPLATE.md 的格式，并通过 plan_lint；不要另建 plans/ 目录。
- 注意：spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。

## 验收
```bash
python tools/plan_lint.py --check
test -s docs/01-rea/tags.md
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 新卡片已通过评审
- [x] 人已确认首期范围
- [x] 执行记录已填写

## 不在本卡范围
- 写任何业务代码

## 执行记录
- 日期：2026-11-24
- 分支：docs/p6-46-tags-rea-planning
- 测试：2026-11-24T08:40:21+08:00 通过（3 项）
- 实际改动：逆向旧系统标签子系统，写 tags.md 与 BS-23/BS-24；首期仅显式标签（criteria 只存不求值）；拆 P6-92~P6-99（存储/权限/API/EntityNode/事件/Web/迁移）；审查后修正 OpenAPI 链与剥离事件。
- 偏差：原产出「搜索过滤」改为 EntityNode 显式标签填充 + Web 可见性过滤；旧系统无按项目 Tag 的服务端搜索过滤，故不做 search-by-tag。
- 审查：planner, architect
