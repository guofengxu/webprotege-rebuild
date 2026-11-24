# P7 Manchester 语法编辑：卡片清单

> **暂缓（2026-11-24，人的决定，记录于 `tasks/P6/EPIC.md`「规划卡暂缓与新阶段目录」）**：规划卡 `P6-47` 的状态为 `blocked`，`python tools/task.py next` 不再列出它。本目录预留给 `P6-47` 拆出的任务卡，目前还没有卡片；恢复方式见 `tasks/P6/P6-47-manchester-rea-planning.md` 的「阻塞」小节。

本阶段承接 P6 第二轮候选项“Manchester 语法编辑”（`tasks/P6/EPIC.md`「第二轮优先级」）。P6 卡号已用到 P6-99，所以由规划卡 `P6-47` 在本目录下编写任务卡，而不是接在 P6 后面。

## 来源与已知要点

- 规划卡：`tasks/P6/P6-47-manchester-rea-planning.md`（依赖 P4-09，已完成）。
- 逆向结论写在 `docs/01-rea/manchester-syntax.md`，行为规格追加到 `docs/01-rea/behavior-specs.md`，均由 `P6-47` 产出。
- P6-19 调研的已知要点（待 `P6-47` 核实）：旧系统基于 OWL API 的 Manchester 解析器；保存时把新旧文本都解析为（本体, 公理）对，旧有新无为删除、新有旧无为新增；检查返回未变化 / 已变化 / 解析错误；权限为检查 `VIEW_PROJECT`、保存 `EDIT_ONTOLOGY`。新系统可以使用 OWL API 5 自带的 Manchester 解析与渲染（ADR-003）。

## 候选项（首期范围由 P6-47 提出，人确认）

| # | 候选项 | 规划时要回答的问题 |
|---|---|---|
| 1 | 解析与差异生成（内核或应用层） | 实体名称的解析规则、错误位置的报告方式；保存走 `ChangePipeline` 与现有的乐观并发约定（409 `FRAME_CHANGED`），不另开写路径 |
| 2 | 接口：取得、检查、保存 | 首期支持哪些实体类型；补全是否进入首期；在 OpenAPI 串行链上的位置 |
| 3 | Web 端编辑器 | 是否需要新的第三方依赖（例如代码编辑器组件），需要时在卡片中写明名称与理由 |

## 编号规则

- 卡号：从 `P7-01` 起，取开工时 main 上本目录中的最大编号 + 1，最多到 `P7-99`。
- Flyway 版本号、BS 编号、ADR 编号全局唯一：仍取当时 main 上已分配（含已规划未实现）的最大值 + 1，见 `tasks/P6/EPIC.md`「规划卡的编号规则」。
- 本阶段的卡可以依赖 P6 的卡（例如 OpenAPI 或 `Action.java` 串行链上的最后一张），`depends_on` 直接写卡号。
- 首期范围、展开的卡片、依赖关系与共享文件写在本文件，不再写进 `tasks/P6/EPIC.md`。
