---
id: P6-65
title: Web 端：WorkspacePage 测试与真实注册表隔离，修复 main 上 wp-web verify 的失败
phase: P6
module: wp-web
type: impl
size: S
work_type: web
ecc_extra: []
status: done
depends_on: []
---

# P6-65 Web 端：修复 wp-web verify 的测试失败

## 目标
`main` 上 `pnpm -C frontend --filter wp-web run verify` 重新全部通过：`WorkspacePage.spec.ts` 不再受 `src/features/**/register.ts` 中真实注册项的影响；`authGuard.spec.ts` 不再因首次加载路由依赖图超过 5 秒而偶发超时。

## 参考
- 原因分析（2026-11-22）：`WorkspacePage` 通过 `useWorkspaceRegistry()` 取默认注册表，默认注册表用 `import.meta.glob("/src/features/**/register.ts", { eager: true })` 收集全部特性。P4-09（`ac0d7ac`）为 `CLASS` 注册 `ClassFrameEditor` 之后，用例 “opening with ?entity= shows the same IRI in the detail panel” 渲染的是真实编辑器：`GET /api/projects/{id}/frames/class` 没有 mock，`mockFetch` 抛出 `No mock handler`，编辑器停在“加载中…”；IRI 只在 frame 加载成功后由 `FrameHeader` 显示，所以断言失败。该用例在 `bf28b23`（P4-09 合并前）通过，在 `1347beb`（P4-09 合并后）、`0f88386`（P6-25）与 `c32552f` 上都稳定失败，与 TypeScript 版本无关。
- 这条失败导致 P6-42 被标为 `blocked`（见 P6-42「首次执行记录」）。
- CI 的 `frontend` job 只运行 `install` 与 `@wp/api-types` 的 `check`，不运行 wp-web 的 `verify`，所以这条失败没有被 CI 拦下。
- 同目录的隔离写法：`src/features/workspace/EntityDetailPanel.spec.ts`、`src/features/workspace/HierarchyPanel.spec.ts`（用 `provide(WorkspaceRegistryKey, registry)` 注入测试注册表）。
- `src/features/workspace/registry.ts`：`WorkspaceRegistryKey`、`mergeRegistrations`。
- `src/app/authGuard.spec.ts` 中的用例 “createAppRouter mounts projects under an authenticated AppLayout parent”：在测试体内 `await import("./router")`，首次转换整个路由依赖图的时间计入 vitest 默认的 5 秒超时。2026-11-22 全量 `vitest run` 时观察到一次 `Test timed out in 5000ms`；单独运行该文件 8/8 通过。

## 产出
- `frontend/wp-web/src/features/workspace/WorkspacePage.spec.ts`：增加一个测试包装组件，在 `setup` 中 `provide(WorkspaceRegistryKey, mergeRegistrations({}))` 后渲染 `WorkspacePage`；`renderPage` 中的三个路由组件改用这个包装组件。两条用例的断言不变。
- `frontend/wp-web/src/app/authGuard.spec.ts`：把上述用例中的 `await import("./router")` 与 `await import("vue-router")` 改为文件顶部的静态导入（`vue-router` 已有静态导入，补上 `createMemoryHistory` 即可，不重复导入）；断言不变。
- 不修改任何生产代码（`src/` 下非 `*.spec.ts` 的文件），也不修改 `src/shared/testing/` 下的测试工具。

## 实现要点
- 页面用例只验证 URL 与详情面板、层级页签的联动；特性编辑器和页签由各自的 spec 覆盖，所以用空注册表隔离。不要改为给页面用例补齐 `/frames/class`、`/permissions/me`、`/entities/history` 等 mock，那样以后每注册一个特性，这条用例都可能再坏一次。
- 隔离后，第一条用例走 `EntityDetailPanel` 的回退分支显示 IRI；第二条用例的页签来自 `WORKSPACE_KINDS`，与注册表无关。
- 上述 `WorkspacePage.spec.ts` 的改法已在临时副本中验证：`vue-tsc --noEmit`、`eslint`、全量 `vitest run`（80 个文件、340 条用例）都通过。
- 如果把 `./router` 改为静态导入后出现导入期副作用或循环依赖，导致其它用例失败，就改为只给这一条用例单独设置超时（`test(name, fn, 15_000)`），并在执行记录的偏差中写明原因。不要提高全局 `testTimeout`。
- 不跳过、不删除用例，不放宽断言。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-65` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend install
pnpm -C frontend --filter wp-web exec vitest run src/features/workspace/WorkspacePage.spec.ts src/app/authGuard.spec.ts
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写（含全量 `vitest run` 的文件数与用例数）

## 不在本卡范围
- 修改 `.github/workflows/ci.yml`，让 CI 的 `frontend` job 运行 wp-web 的 `verify`：`.github/` 是开发标准文件（CODEOWNERS），由人另开分支修改。
- 重新执行 P6-42；`main` 上 Java CI 的失败（`AdminUserManagementTest`）。
- 现有的 eslint 警告（34 条 warning，不影响退出码）。

## 执行记录
- 日期：2026-11-28
- 分支：feature/p6-65-web-workspace-spec-isolation
- 测试：2026-11-28T12:12:55+08:00 通过（4 项）
- 实际改动：WorkspacePage.spec 用空注册表 Host 隔离真实 register.ts；authGuard.spec 将 createAppRouter 改为静态导入。全量 vitest：81 文件、348 用例通过。
- 偏差：无
- 审查：vue-reviewer, (LGTM);, typescript-reviewer, (LGTM);, a11y-architect, (LGTM, test-only)
