---
id: P6-43
title: 规划卡：表单（Forms）逆向分析，确定首期范围并拆卡
phase: P6
module: tasks/P6
type: planning
size: S
work_type: planning
ecc_extra: [rea]
status: todo
depends_on: [P4-22]
---

# P6-43 规划卡：表单逆向分析与拆卡

## 目标
对旧系统的表单子系统做逆向分析，写出规格与首期范围，并展开为任务卡。表单是旧系统后期扩张最快、规模最大的子系统，不先分析就拆卡会重蹈“任务太粗”的覆辙。

## 参考
- 旧工程（只读）：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/form/`（约 83 个文件）、`../webprotege/webprotege-shared/src/main/java/edu/stanford/bmir/protege/web/shared/form/`（约 146 个）、`../webprotege/webprotege-client/src/main/java/edu/stanford/bmir/protege/web/client/form/`（约 237 个）
- 已知要点（P6-19 调研）：`FormDescriptor` / `FormFieldDescriptor`（控件：文本、数字、单选、多选、实体名、图片、表格、子表单；绑定：属性、类、实例、子类）；`EntityFormSelector` 按条件为实体选择表单；表单数据不单独存储，保存时把编辑前后的表单转换为 Frame 再求差，生成公理变更（`EntityFormChangeListGenerator`、`FormDataUpdateSanityChecker`）；Mongo 集合 `Forms`、`FormSelectors`；权限 `EDIT_FORMS`，部分处理器没有声明权限
- `docs/01-rea/README.md`（五层逆向方法）；`docs/01-rea/legacy-data.md`（旧数据）；P2-04（mongoexport 元数据迁移）
- P4-22 / P4-26：Web 端 Frame 编辑机制与输入组件；P3-15 ~ P3-18：Frame 读写接口与乐观并发
- `AGENTS.md` 第 7 节

## 产出
- `docs/01-rea/forms.md`：按五层方法写出结论（边界、静态结构、动态行为、数据与状态、演进证据），附代码出处；列出“本质约束”与“偶然实现”。
- `docs/01-rea/behavior-specs.md` 追加表单相关的 BS-xx 规格（编号接续当时的最大编号），每条可以直接写成测试。
- 在 `tasks/P6/EPIC.md` 中写明首期范围（建议：只读渲染 + 少数控件的编辑；条件选择器、表格、子表单、旧表单数据迁移是否进入首期由人决定）与理由，并编写任务卡（编号接续当时的最大编号）：存储与迁移、描述符管理接口、按实体取表单、保存（表单 → 公理变更）、Web 端渲染与编辑、旧数据迁移（迁移工具与导入两侧）。
- 新卡单模块、一天内可完成、有可执行的验收命令；新增依赖（如有）必须在卡片中写明。

## 实现要点
- 规划卡一次只做一张：开工前确认没有其它规划卡处于 `in_progress`；新卡编号取开工时 main 上的最大编号 + 1，新的 Flyway 版本号取当时 main 上已分配的最大版本 + 1（见 EPIC「第二轮规划」中的“规划卡的编号规则”）。
- 行为以旧代码与旧测试为准；`spec-miner` 只用来辅助阅读，不生成 `openspec/` 目录。
- 表单保存最终都是公理变更，必须走 `ChangePipeline` 与乐观并发，不允许另开写路径。

## ECC 技能
工作项类型：`planning` —— 规划卡（产出任务卡，不写业务代码）；附加：`rea`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-43` 查看。

- 开工前加载的 skill：`blueprint`、`intent-driven-development`、`code-tour`
- 开工时可用的辅助 agent：`spec-miner`
- 提交前调用的审查 agent：`planner`、`architect`
- 注意：blueprint 产出的步骤必须转写成 tasks/TEMPLATE.md 的格式，并通过 plan_lint；不要另建 plans/ 目录。
- 注意：spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。

## 验收
```bash
python tools/plan_lint.py --check
test -s docs/01-rea/forms.md
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
