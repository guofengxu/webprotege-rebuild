---
id: P6-47
title: 规划卡：Manchester 语法编辑逆向分析，确定首期范围并拆卡
phase: P6
module: tasks/P7
type: planning
size: S
work_type: planning
ecc_extra: [rea]
status: cancelled
depends_on: [P4-09]
---

# P6-47 规划卡：Manchester 语法编辑逆向分析与拆卡

## 目标
对旧系统“用 Manchester 语法查看与编辑实体描述”的功能做逆向分析，写出规格与首期范围，并展开为任务卡。拆出的任务卡放在新阶段目录 `tasks/P7/`。

## 参考
- 旧工程（只读）：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/mansyntax/`（约 74 个文件，其中约 68 个是 `render/` 下的渲染器）；动作定义在 `../webprotege/webprotege-shared/src/main/java/edu/stanford/bmir/protege/web/shared/frame/`；处理器在 `server/frame/`（取得、检查、保存、补全）
- 已知要点（P6-19 调研）：`ManchesterSyntaxFrameParser` 基于 OWL API 的 Manchester 解析器（`WebProtegeOWLEntityChecker`、`ShellOntologyChecker`）；保存时把新旧文本都解析为（本体, 公理）对，旧有新无为删除、新有旧无为新增（`OntologyAxiomPairChangeGenerator`）；检查返回未变化 / 已变化 / 解析错误；修订描述为 `Edited description of X.` 加用户说明；权限：检查 `VIEW_PROJECT`，保存 `EDIT_ONTOLOGY`
- 新系统：OWL API 5 自带 Manchester 语法的解析与渲染（ADR-003）；P3-15 ~ P3-18 的 Frame 接口与乐观并发（409 `FRAME_CHANGED`）；P4-09 的类 Frame 编辑器
- `tasks/P7/EPIC.md`（本卡拆出的任务卡所在的阶段目录）；`tasks/P6/EPIC.md`「规划卡暂缓与新阶段目录」
- `AGENTS.md` 第 7 节

## 产出
- `docs/01-rea/manchester-syntax.md`：五层逆向结论与代码出处；评估旧渲染器（约 68 个文件）中哪些可以由 OWL API 5 自带的渲染器替代。
- `docs/01-rea/behavior-specs.md` 追加相关 BS-xx 规格（解析、实体名称解析规则、差异生成、乐观并发、错误位置的报告方式）。
- 在 `tasks/P7/EPIC.md` 中写明首期范围与理由，至少决定：首期支持哪些实体类型、补全是否进入首期、Web 端编辑器组件是否需要新的第三方依赖（例如代码编辑器组件；需要时在卡片中写明名称与理由）。
- 在 `tasks/P7/` 下编写任务卡（编号从 P7-01 起；P6 卡号已用到 P6-99）：内核或应用层的解析与差异生成、接口、Web 端编辑器。

## 实现要点
- 规划卡一次只做一张：开工前确认没有其它规划卡处于 `in_progress`；新卡编号取开工时 main 上 `tasks/P7/` 中的最大编号 + 1（从 P7-01 起），新的 Flyway 版本号、BS 编号与 ADR 编号仍取当时 main 上已分配的最大值 + 1（见 `tasks/P6/EPIC.md`「第二轮规划」中的“规划卡的编号规则”与「规划卡暂缓与新阶段目录」）。
- 保存必须走 `ChangePipeline` 与现有的乐观并发约定，不另开写路径。

## ECC 技能
工作项类型：`planning` —— 规划卡（产出任务卡，不写业务代码）；附加：`rea`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-47` 查看。

- 开工前加载的 skill：`blueprint`、`intent-driven-development`、`code-tour`
- 开工时可用的辅助 agent：`spec-miner`
- 提交前调用的审查 agent：`planner`、`architect`
- 注意：blueprint 产出的步骤必须转写成 tasks/TEMPLATE.md 的格式，并通过 plan_lint；不要另建 plans/ 目录。
- 注意：spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。

## 验收
```bash
python tools/plan_lint.py --check
test -s docs/01-rea/manchester-syntax.md
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 新卡片已通过评审
- [ ] 人已确认首期范围
- [ ] 执行记录已填写

## 不在本卡范围
- 写任何业务代码

## 阻塞
- 日期：2026-11-24
- 原因：人决定 P6 中尚未开工的规划卡（P6-40、P6-47、P6-48、P6-63）统一暂缓，标为 `blocked`。这不是卡片本身的问题：本卡的依赖（P4-09）已完成。规划卡按编号会排在 P6-50、P6-57、P6-65、P6-68 等实现卡之前被选中（`python tools/task.py next` 与 `AGENTS.md` 第 1 节取编号最小的卡），打乱实现卡的推进顺序；而且 P6 卡号已用到 P6-99，本卡拆出的卡不能再放进 P6。决定见 `tasks/P6/EPIC.md`「规划卡暂缓与新阶段目录」。
- 恢复方式：人决定开始本卡时，把本卡 `status` 改回 `todo` 并删除本节，同时撤销 `tasks/P7/EPIC.md` 顶部的暂缓说明；合并后按 `python tools/task.py start P6-47` 开工。远端已有本卡的认领分支 `docs/p6-47-manchester-rea-planning`（2026-11-24 从当时的 main 创建，没有提交）：恢复前由人删除该分支，否则 `start` 会提示远程已存在分支。
- 影响：没有卡依赖本卡，P6 实现卡的依赖关系不变。本卡拆出的卡放在 `tasks/P7/`，编号从 P7-01 起。

## 取消
- 日期：2026-11-28
- 决定：人决定把状态为“阻塞”的规划卡（P5-00、P6-40、P6-47、P6-48、P6-63）统一改为取消（`status: cancelled`）。上面的「阻塞」小节保留作历史记录，其中的“恢复方式”不再适用。决定见 `tasks/P6/EPIC.md`「阻塞的规划卡改为取消」。
- 影响：没有卡依赖本卡，实现卡的依赖关系不变；`python tools/task.py next` 与 `tasks/INDEX.md`「现在可以开工」「阻塞中」都不再列出本卡。`tasks/P7/EPIC.md` 的预留目录与候选项保留，目前没有卡片。
- 重新启动：由人决定。把本卡 `status` 改回 `todo`，删除「阻塞」与「取消」两节，并撤销 `tasks/P7/EPIC.md` 顶部的说明；也可以另开一张新的规划卡。远端认领分支 `docs/p6-47-manchester-rea-planning` 如仍存在，重新启动前由人删除。
