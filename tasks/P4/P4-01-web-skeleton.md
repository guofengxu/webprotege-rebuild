---
id: P4-01
title: wp-web 工程骨架：Vue 3.5 + Vite 8 + TypeScript strict + ESLint + Vitest + Playwright
phase: P4
module: wp-web
type: impl
size: S
work_type: web
ecc_extra: []
status: todo
depends_on: [P3-27]
---

# P4-01 wp-web 工程骨架

## 目标
`frontend/wp-web` 成为 pnpm 工作区成员，`verify` 脚本（类型检查、lint、单元测试、构建）可以被 `tools/task.py test` 调用，后续功能卡只需要往里加代码。

## 参考
- `tasks/P4/EPIC.md` 第 1 项
- ADR-002（前端版本表）、ADR-008
- `docs/02-architecture/overview.md` 第 1 节（前端命令的运行位置、`/api` 代理、构建方向）
- `.cursor/rules/30-vue-web.mdc`
- `frontend/pnpm-workspace.yaml`（已包含 `wp-web`）、`frontend/packages/api-types/package.json`（版本写法参照：不带 `^`）
- `tools/task.py` 的 `full_check_commands`：`web` 类卡的全量检查是 `pnpm -C frontend --filter wp-web run verify`

## 产出
- `frontend/wp-web/package.json`：`name: "wp-web"`、`private: true`、`type: "module"`。版本号全部写死（不带 `^` / `~`）。
  - `dependencies`：`vue` 3.5.x、`vue-router` 5.4.x、`pinia`、`element-plus` 2.14.x、`vue-i18n`、`@wp/api-types`（`workspace:*`）。
  - `devDependencies`：`vite` 8.3.x、`@vitejs/plugin-vue` 6.x、`typescript` 6.0.x、`vue-tsc` 3.x、`eslint`、`eslint-plugin-vue`、`@vue/eslint-config-typescript`、`vitest` 5.0.5、`@vue/test-utils` 2.x、`jsdom`、`@playwright/test` 1.64.1、`@types/node` 24.x。
  - `scripts`：`dev`（`vite`）、`build`（`vite build`）、`preview`（`vite preview`）、`typecheck`（`vue-tsc --noEmit`）、`lint`（`eslint .`）、`test`（`vitest run`）、`e2e`（`playwright test`）、`verify`（依次执行 `vue-tsc --noEmit`、`eslint .`、`vitest run`、`vite build`，任一步失败即停止）。
- `frontend/wp-web/tsconfig.json`：`strict: true`、`noUncheckedIndexedAccess: true`、`moduleResolution: "bundler"`、路径别名 `@/*` → `src/*`、`types: ["vite/client", "node"]`；`include` 覆盖 `src/**/*`、`e2e/**/*`、`*.config.ts`。只用这一个 tsconfig，保证 `vue-tsc --noEmit` 检查到全部文件。
- `frontend/wp-web/vite.config.ts`（`defineConfig` 从 `vitest/config` 导入，`test` 字段才能通过类型检查）：`@vitejs/plugin-vue`；别名 `@`；`server.port = 5173`；`server.proxy` 与 `preview.proxy` 都把 `/api` 代理到 `http://localhost:8080`（`changeOrigin: false`，浏览器只看到 5173 一个源，会话 Cookie 与 `XSRF-TOKEN` Cookie 同源）；Vitest 配置写在同一文件的 `test` 字段：`environment: "jsdom"`、`include: ["src/**/*.spec.ts"]`。
- `frontend/wp-web/eslint.config.js`：flat config，`eslint-plugin-vue` 的 `flat/recommended` + `@vue/eslint-config-typescript`；开启 `@typescript-eslint/no-explicit-any: error`；忽略 `dist/`、`playwright-report/`、`test-results/`。
- `frontend/wp-web/playwright.config.ts`：`testDir: "e2e"`，`testIgnore: "m4/**"`（M4 验收另有配置，见 P4-17），只跑 Chromium；`webServer.command` 为 `pnpm exec vite build && pnpm exec vite preview --port 4173 --strictPort`，`url: "http://localhost:4173"`，`reuseExistingServer: !process.env.CI`。
- `frontend/wp-web/index.html`、`src/main.ts`（`createApp(App)` 后依次 `use(createPinia())`、`use(createAppRouter())`、`use(createAppI18n())`、`use(ElementPlus)`，引入 `element-plus/dist/index.css`）、`src/App.vue`（只有 `<RouterView />`）、`src/env.d.ts`。
- `src/app/router.ts`：`export function createAppRouter(history = createWebHistory()): Router`。路由不集中写在这里：用 `import.meta.glob("/src/features/*/routes.ts", { eager: true })` 收集各功能目录导出的 `featureRoutes: FeatureRoutes`（`src/app/featureRoutes.ts`：`type FeatureRoutes = { root?: RouteRecordRaw[]; layout?: RouteRecordRaw[]; project?: RouteRecordRaw[] }`）。本卡只挂载 `root`；`layout` 由 P4-03 挂到主布局下，`project` 由 P4-05 挂到项目外壳下。
- `src/features/home/HomePage.vue` 与 `src/features/home/routes.ts`（`root: [{ path: "/", component: HomePage }]`）：显示 `t("app.title")` 的占位首页，P4-04 删除整个 `home` 目录。
- `src/app/i18n.ts`：`export function createAppI18n(locale: AppLocale = "zh-CN")`（`legacy: false`、`fallbackLocale: "en-US"`）；`export type AppLocale = "zh-CN" | "en-US"`。文案同样按目录收集：`import.meta.glob("/src/**/locales/{zh-CN,en-US}.ts", { eager: true })`，每个文件 `export default` 一个只有一个顶层键（功能名）的对象，合并时顶层键重复就抛错；`en-US.ts` 写成 `export default { … } satisfies typeof zhCN`（`import type zhCN from "./zh-CN"`），缺键时类型检查失败。本卡只有 `src/app/locales/zh-CN.ts`、`en-US.ts` 中的 `app.title`（“WebProtégé 本体编辑器” / “WebProtégé Ontology Editor”）；后续卡的文案放在各自目录的 `locales/` 下。
- 测试：
  - `src/features/home/HomePage.spec.ts`：用内存路由（`createMemoryHistory()`）挂载 `App`，断言首页显示中文标题；切换到 `en-US` 后显示英文标题（P4-04 删除首页时一并删除本测试）。
  - `src/app/i18nCatalog.spec.ts`：两个文案模块的顶层键重复时合并抛错；`en-US` 与 `zh-CN` 合并后的顶层键一致。
  - `src/app/apiTypes.spec.ts`：用 `expectTypeOf` 断言从 `@wp/api-types` 导入的 `EntityNode` 有 `iri` 字段，证明 `workspace:*` 引用和类型解析可用（`expectTypeOf` 在运行时不做任何事，真正起作用的是 `verify` 中的 `vue-tsc`）。
  - `e2e/smoke.spec.ts`：打开 `/`，断言页面标题文字可见；用 `page.route("**/api/**", …)` 把所有 API 请求返回 404，保证冒烟测试不依赖后端。
- `frontend/wp-web/README.md`：本地开发步骤（先 `docker compose up -d postgres` 并启动 wp-app，再 `pnpm -C frontend --filter wp-web run dev`），以及首次运行 Playwright 前安装浏览器的命令。
- `frontend/pnpm-lock.yaml`：随依赖更新。

## 实现要点
- 版本以 ADR-002 为准：Vue 3.5.x（不要用 3.6 RC）、Vite 8.x、TypeScript 6.0.x（不要用 7）、Element Plus 2.14.x、Vue Router 5.4.x、Vitest 5.0.5、Playwright 1.64.1；ADR 没写版本的包（`pinia`、`vue-i18n`、ESLint 相关、`jsdom`、`@vue/test-utils`）取开工当天的最新稳定版。
- `@wp/api-types` 自己用 TypeScript 5.9；wp-web 用 6.0。两者互不影响，因为类型包只导出 `.ts` 源码，由 wp-web 的编译器检查。
- pnpm 12 默认不执行依赖的构建脚本。如果 `pnpm -C frontend install` 报 `ERR_PNPM_IGNORED_BUILDS`，把报出的包名加到 `frontend/pnpm-workspace.yaml` 的 `allowBuilds`（P3-27 的做法），并在执行记录中写明。
- 命令一律从仓库根目录写成 `pnpm -C frontend --filter wp-web …`；`pnpm --filter … exec` 在 `frontend/wp-web` 目录下执行，所以测试文件路径相对于 `frontend/wp-web`。
- 本卡就引入 `element-plus` 与 `vue-i18n` 并完成注册，是为了让 P4-02 的登录页能直接使用表单组件和文案；主题、设计令牌、语言切换和完整的文案组织由 P4-03 负责。
- 路由与文案按目录自动收集，是为了让后续并行的功能卡只新增自己目录下的文件：Agent 不能自己解决合并冲突（`AGENTS.md` 禁止 merge / rebase），多张卡同时改 `router.ts` 或同一个文案文件就会卡住。
- 首次运行 Playwright 需要下载 Chromium；验收命令中的 `playwright install chromium` 是幂等的。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P4-01` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend install
pnpm -C frontend --filter wp-web exec vitest run src/features/home/HomePage.spec.ts src/app/i18nCatalog.spec.ts src/app/apiTypes.spec.ts
pnpm -C frontend --filter wp-web exec playwright install chromium
pnpm -C frontend --filter wp-web exec playwright test e2e/smoke.spec.ts
pnpm -C frontend --filter wp-web run verify
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] `verify` 脚本按 vue-tsc、ESLint、Vitest、vite build 的顺序执行
- [ ] 开发服务器把 `/api` 代理到 `http://localhost:8080`
- [ ] 执行记录已填写

## 不在本卡范围
- API 客户端（P4-02）、登录与路由守卫（P4-25）
- 主题、设计令牌、语言切换、顶部栏（P4-03）
- 把 `dist` 打进 wp-app 的 jar（P4-16）
- 修改 `.github/workflows/ci.yml`：它属于开发标准文件（`.github/CODEOWNERS`），在 CI 中加入 `wp-web` 的 `verify` 由维护者另行修改；在执行记录中提醒
- 引入本卡未列出的依赖（例如 Prettier、`unplugin-*` 自动导入、MSW）
