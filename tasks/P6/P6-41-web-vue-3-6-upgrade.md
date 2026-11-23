---
id: P6-41
title: Web 端：升级到 Vue 3.6 正式版（不启用 Vapor 模式），验证生态库兼容
phase: P6
module: wp-web
type: impl
size: S
work_type: web
ecc_extra: []
status: blocked
depends_on: [P6-42]
---

# P6-41 Web 端：升级 Vue 3.6

## 目标
wp-web 使用 Vue 3.6 正式版运行、测试与构建，行为不变；记录构建体积与测试耗时的变化。

## 参考
- `tasks/P6/EPIC.md` 候选项“Vue 3.6 升级：3.6 正式发布后升级 Web 端，单独一张卡”
- ADR-008（Vue 前端）；`frontend/wp-web/package.json`（当前 `vue` 3.5.45、`vue-router` 5.4.0、`pinia` 4.1.0、`element-plus` 2.14.8、`vue-i18n` 11.4.18、`@vitejs/plugin-vue` 6.0.10、`vue-tsc` 3.3.14、`@vue/test-utils` 2.5.1）
- P4-17：Playwright 端到端用例（`frontend/wp-web/e2e/`）

## 产出
- `frontend/wp-web/package.json` 与 `frontend/pnpm-lock.yaml`：`vue` 升级到 3.6 的最新正式版；只有在需要时才同步升级 `@vitejs/plugin-vue`、`vue-tsc`、`@vue/test-utils`、`vue-router`、`pinia`、`vue-i18n`、`element-plus`，每个升级都在执行记录中写明原因。
- 修复升级引起的类型错误、弃用警告与测试失败；不借机重构。
- 执行记录中记录：升级前后 `vite build` 的产物体积、`vitest run` 耗时、端到端用例结果。

## 实现要点
- 不启用 Vapor 模式，不改组件写法；Vapor 是否采用需要单独评估。
- 如果 ADR、`.cursor/rules/30-vue-web.mdc` 或 `tools/ecc-routing.json` 中写着“Vue 3.5”，Agent 不修改这些开发标准文件（CODEOWNERS），在执行记录中列出，由人更新。
- 依赖 P6-42 只是为了串行修改 `package.json` 与锁文件。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-41` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend install
pnpm -C frontend --filter wp-web run verify
pnpm -C frontend --filter wp-web exec node -e "const v=require('vue/package.json').version; if(!/^3\.6\.\d+$/.test(v)){console.error('vue '+v);process.exit(1)}"
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写（含体积、耗时与端到端结果）

## 不在本卡范围
- Vapor 模式；移动端（uni-app 固定自己的 Vue 版本，且 P5 暂停）
- 与升级无关的依赖更新

## 阻塞
- 日期：2026-11-15
- 原因：外部条件未满足。截至 2026-11-15，npm 上 `vue` 的 `latest` 仍为 3.5.45，3.6 只有 `3.6.0-rc.15`（2026-11-12 发布）；候选清单要求正式发布后再升级。
- 恢复方式：`npm view vue dist-tags.latest` 显示 3.6.x，且 `element-plus` 等关键依赖没有已知的不兼容问题时，人把本卡 `status` 改回 `todo` 并删除本节，再按 `python tools/task.py start P6-41` 开工。
- 复核（2026-11-22）：外部条件仍未满足。npm 上 `vue` 的 `latest` 为 3.5.46，3.6 最新为 `3.6.0-rc.16`（2026-11-20 发布）；`element-plus` 的 `latest` 仍为 2.14.8。
- 依赖说明（2026-11-22）：`task.py start` 要求全部依赖为 `done`，而本卡依赖的 P6-42 当时是 `blocked`（被一条与 TypeScript 无关的 wp-web 测试失败卡住）。修复卡 P6-65 合并、P6-42 按“保持 6.0.3”结束后，这项依赖即可解除；之后本卡只等 Vue 3.6 正式版。恢复时如果 P6-42 还没有完成，先完成 P6-42，不要去掉本卡对它的依赖（两张卡都修改 `package.json` 与锁文件，需要串行）。
