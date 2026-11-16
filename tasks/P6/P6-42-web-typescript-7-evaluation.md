---
id: P6-42
title: Web 端：评估 TypeScript 7（原生编译器）与 vue-tsc、typescript-eslint 的兼容性，能升则升，不能升则记录阻塞点
phase: P6
module: wp-web
type: impl
size: S
work_type: web
ecc_extra: []
status: todo
depends_on: [P6-07]
---

# P6-42 Web 端：TypeScript 7 评估

## 目标
回答“wp-web 现在能不能用 TypeScript 7”：在 wp-web 中试升级，跑通类型检查、lint、测试与构建则保留升级；任何一环不兼容则撤回依赖改动，把阻塞点与需要关注的上游进展写成评估文档。

## 参考
- `tasks/P6/EPIC.md` 候选项“TypeScript 7 评估：vue-tsc 正式支持后再评估”
- 现状（2026-11-15）：npm 上 `typescript` 的 `latest` 为 7.0.2（2026-07-08 发布）；`vue-tsc` 的 `latest` 为 3.3.14，`peerDependencies` 写的是 `typescript >= 5.0.0`，但它依赖 TypeScript 的 JS 编程接口，是否能与原生编译器配合需要实测
- `frontend/wp-web/package.json`（`typescript` 6.0.3、`vue-tsc` 3.3.14、`@vue/eslint-config-typescript` 14.9.0、`eslint` 10.14.0；`verify` = `vue-tsc --noEmit && eslint . && vitest run && vite build`）

## 产出
- 试升级：把 wp-web 的 `typescript` 改为 7.0 的最新正式版，按需调整 `tsconfig`（例如被移除的选项），依次运行 `vue-tsc --noEmit`、`eslint .`、`vitest run`、`vite build`，记录每一步的结果与 `vue-tsc` 的耗时（与 6.0.3 对比）。
- 结论二选一：
  - 全部通过：保留 `package.json` 与 `frontend/pnpm-lock.yaml` 的改动；
  - 任一步不兼容：撤回依赖改动（wp-web 保持 6.0.3），不做任何绕过（例如关掉类型检查或 lint 规则）。
- `docs/03-plan/typescript-7-evaluation.md`：环境与版本、每一步的结果（失败时附关键错误摘录）、耗时对比、结论，以及不兼容时需要关注的上游事项（`vue-tsc`、`typescript-eslint` 等的相关版本或议题）与重新评估的条件。

## 实现要点
- 只评估 wp-web；`frontend/packages/api-types` 固定使用 TypeScript 5.9.3（P3-27），不在本卡范围。
- 结论文档无论升级与否都要写；它是本卡的主要交付物。
- 与 P6-41（Vue 3.6）都修改 `package.json`，所以 P6-41 依赖本卡。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-42` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend install
pnpm -C frontend --filter wp-web run verify
test -s docs/03-plan/typescript-7-evaluation.md
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写（写明结论：已升级 / 保持 6.0.3）

## 不在本卡范围
- `@wp/api-types` 与移动端的 TypeScript 版本
- 为了兼容 TypeScript 7 而替换 `vue-tsc` 或 lint 工具链
