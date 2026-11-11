---
id: P4-00
title: 规划卡：细化 Web 端（Vue）MVP 任务卡
phase: P4
module: tasks/P4
type: planning
size: S
work_type: planning
ecc_extra: []
status: done
depends_on: [P3-24, P3-27]
---

# P4-00 规划卡：细化 Web 端（Vue）MVP 任务卡

## 目标
把 `tasks/P4/EPIC.md` 中的每一项展开为可以直接开工的任务卡。

## 参考
- `tasks/P4/EPIC.md`（卡片清单与验收要点）
- ADR-002（前端版本）、ADR-008
- `docs/api/openapi.json`（P3-23）、`frontend/packages/api-types`（P3-27）
- `docs/02-architecture/overview.md` 第 1 节（前端命令的运行位置、产物打包方向）
- `AGENTS.md` 第 7 节
- `.cursor/skills/wp-ecc-routing/SKILL.md`（`web` 类工作项使用的 ECC skill）

## 产出
- `tasks/P4/` 下的任务卡 P4-01 起，按 EPIC 中的顺序编号；每张卡使用 `tasks/TEMPLATE.md` 的全部小节，frontmatter 的 `work_type` 为 `web` 或 `e2e`。
- 每张卡的「产出」写到组件、路由、composable 和文件路径一级（例如 `frontend/wp-web/src/features/hierarchy/HierarchyTree.vue`），并写明调用哪些 API（以 `docs/api/openapi.json` 中的 operationId 为准）。
- 每张卡至少有一条验收命令：`pnpm -C frontend --filter wp-web exec vitest run <文件>` 或 `pnpm -C frontend --filter wp-web exec playwright test <spec>`。
- P4-01（工程骨架）中写明：`wp-web` 的 `verify` 脚本依次执行 `vue-tsc --noEmit`、ESLint、`vitest run`、`vite build`；`tools/task.py test` 会调用它；`@wp/api-types` 用 `workspace:*` 引用；开发服务器把 `/api` 代理到本地 wp-app。
- EPIC 第 16 项（单包打包）单独成卡，module 为 `wp-app`、work_type 为 `build`，不并入 P4-01：它改的是后端模块的 POM。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`planning` —— 规划卡（产出任务卡，不写业务代码）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P4-00` 查看。

- 开工前加载的 skill：`blueprint`、`intent-driven-development`
- 提交前调用的审查 agent：`planner`、`architect`
- 注意：blueprint 产出的步骤必须转写成 tasks/TEMPLATE.md 的格式，并通过 plan_lint；不要另建 plans/ 目录。

## 验收
```bash
python tools/plan_lint.py --check
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] EPIC 中每一项都有对应的卡片
- [x] 人已评审新卡片
- [x] 执行记录已填写

## 不在本卡范围
- 写任何前端代码

## 执行记录
- 日期：2026-11-11
- 分支：docs/p4-00-web-frontend-planning
- 测试：2026-11-11T14:11:08+08:00 通过（2 项）
- 实际改动：把 P4 EPIC 的 17 项展开为 P4-01～P4-17（编号与 EPIC 序号一致），另增 P4-18～P4-29；每张卡写到文件、组件与 operationId 一级，ECC 小节与 task.py ecc 一致；EPIC.md 写明展开结果、依赖关系、允许的跨卡文件修改与后端缺口。
- 偏差：另增 12 张卡：P4-18 补齐 OpenAPI 成功响应的 schema，并修正 ValueNode 判别值与事件 type 的枚举（api-contract）；P4-19/20/21 覆盖 mvp-scope 中 Web 端的下载、个人设置、用户管理；P4-22/23/24/25 分别从第 9-10、10、15、2 项拆出；审查后再从 P4-22、P4-15、P4-21、P4-14 拆出 P4-26（Frame 输入组件）、P4-27（新实体与显示语言）、P4-28（角色与重置密码）、P4-29（事件连接与重连），控制在 400 行内。planner 先审 P4-01～P4-06，第二轮 planner（11 条）与 architect（20 条）覆盖 P4-07～P4-25，结论已对照代码核实并全部处理：补查询前缀键与 search 键的 limit、注册表 props 与 hierarchyToolbar、设置页标签注册表、from 必须回传原始服务端对象、层级事件失效整个层级前缀、内置数据类型与注解属性、显示名称设置写入无语言/zh/en 三项、分页从 0 开始、P4-16 增加已登录不回退测试与安全响应头。为此一并修改了 P4-02、P4-05、P4-25 的跨卡接口。后端缺口（没有“我的权限”接口、事件订阅只在建立连接时校验权限、没有用户搜索）记入 P4-17 的差距清单，建议另开 P3 补卡。
- 审查：planner, architect
