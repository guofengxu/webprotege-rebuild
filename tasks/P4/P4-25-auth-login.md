---
id: P4-25
title: 登录页、当前用户、路由守卫与 401 统一跳转
phase: P4
module: wp-web
type: impl
size: M
work_type: web
ecc_extra: []
status: todo
depends_on: [P4-02]
---

# P4-25 认证与登录页

## 目标
用户可以登录、登出；未登录访问受保护页面时被带到登录页，登录后回到原页面；任何接口返回 401 都统一回到登录页。本卡由 EPIC 第 2 项拆出（客户端与基础设施见 P4-02）。

## 参考
- `tasks/P4/EPIC.md` 第 2 项
- ADR-010（会话 Cookie + CSRF）
- P3-04：`POST /api/auth/login`（JSON `{username, password}`）、`POST /api/auth/logout`、`GET /api/auth/me`（`{userId, displayName, email, appRoles}`）；密码错误 401；`password_hash` 为空的用户得到 `PASSWORD_RESET_REQUIRED`；执行记录：登录成功后服务端轮换 CSRF 令牌
- `backend/wp-app/src/main/java/wprebuild/app/web/security/SecurityConfig.java`：登录接口免认证但**需要** CSRF 头
- `docs/api/openapi.json` 的 operationId：`login`、`logout`、`getCurrentUser`
- P4-02 的 `createApiClient`（`onUnauthorized`）、`unwrap` / `problemMessage`、`queryKeys.me()`、`renderWithProviders`
- P4-01 的约定：路由与文案按目录收集

## 产出
- `src/features/auth/useAuth.ts`：`useCurrentUser()`（`useQuery`，键 `queryKeys.me()`，调用 `getCurrentUser`，401 时返回 `null` 而不是报错）；`useLogin()`（成功后让 `me` 失效）、`useLogout()`（成功后 `queryClient.clear()` 并跳转 `/login`）；`isSystemAdmin(user)`（`appRoles` 含 `SYSTEM_ADMIN`）。
- `src/features/auth/LoginPage.vue`：Element Plus 表单（用户名、密码，必填校验，回车提交）；失败时显示 `problemMessage`；`PASSWORD_RESET_REQUIRED` 显示“需要管理员重置密码”的专门提示；查询参数 `reason=network` 时显示 `errors.network`；成功后 `router.replace(safeRedirect(route.query.redirect))`。
- `src/app/authGuard.ts`：
  - `export function safeRedirect(raw: unknown): string`：只接受以 `/` 开头且不以 `//` 开头的站内路径，否则返回 `/`（防止开放重定向；守卫与登录页共用）；
  - `export function installAuthGuard(router: Router, queryClient: QueryClient, client: ApiClient)`：`meta.requiresAuth` 为真时用 `queryClient.fetchQuery` 获取当前用户；为 `null` 时跳转 `/login?redirect=<fullPath>`；获取失败但不是 401（例如后端不可用）时跳转 `/login?redirect=<fullPath>&reason=network`；已登录访问 `/login` 时跳到 `/`；`meta.requiresSystemAdmin` 为真且 `isSystemAdmin(user)` 为假时跳到 `/`，并用 `ElMessage` 显示 `auth.adminRequired`（P4-21 的 `/admin/users` 只需声明这个 `meta`，不再修改守卫）；
  - `createAppRouter` 本身不带守卫，守卫只在 `main.ts` 中安装，所以 P4-01 的 `HomePage.spec.ts` 不受影响。
- `src/features/auth/routes.ts`：`root` 路由 `/login`。修改 `src/features/home/routes.ts`（P4-01）：首页加 `meta.requiresAuth: true`。
- `src/main.ts`：`createApiClient({ onUnauthorized })`，`onUnauthorized` 清空 `queryKeys.me()` 的缓存并 `router.replace("/login?redirect=<当前路径>")`；调用 `installAuthGuard`。
- 文案：`src/features/auth/locales/`（顶层键 `auth`）。
- 修改 `e2e/smoke.spec.ts`（P4-01）：`GET /api/auth/me` 返回 401 的 `ProblemDetail`，其它 `/api/**` 仍返回 404；断言打开 `/` 后被带到登录页并显示登录表单。
- 测试：
  - `src/app/authGuard.spec.ts`：未登录访问 `/` 跳到 `/login?redirect=%2F`；已登录访问 `/login` 跳到 `/`；`getCurrentUser` 返回 500 时跳到带 `reason=network` 的登录页；用一条带 `meta.requiresSystemAdmin` 的桩路由验证：普通用户被重定向到 `/`，`SYSTEM_ADMIN` 可以进入；`safeRedirect` 对 `/projects`、`//evil.example`、`https://evil.example`、`undefined` 的结果。
  - `src/features/auth/LoginPage.spec.ts`：错误密码显示 401 文案；`PASSWORD_RESET_REQUIRED` 显示专门提示；提交时调用 `login` 且请求带 `X-XSRF-TOKEN`；成功后跳到 `redirect`；`redirect=//evil.example` 时跳到 `/`。

## 实现要点
- 浏览器拿到 `XSRF-TOKEN` 的时机：任何一个 `/api` 请求（包括返回 401 的 `GET /api/auth/me`）都会写入该 Cookie。守卫在显示登录页之前已经调用过 `getCurrentUser`，所以登录请求能带上 CSRF 头；登录成功后令牌会轮换，P4-02 的中间件每次重新读取 Cookie。
- 当前用户走 Vue Query，不存进 Pinia。
- 登录页是唯一不在主布局里的页面；顶部栏与用户菜单（登出按钮）由 P4-03 放进布局。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P4-25` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend --filter wp-web exec vitest run src/app/authGuard.spec.ts src/features/auth/LoginPage.spec.ts
pnpm -C frontend --filter wp-web exec playwright install chromium
pnpm -C frontend --filter wp-web exec playwright test e2e/smoke.spec.ts
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 登录后只会跳转到站内路径
- [ ] 执行记录已填写

## 不在本卡范围
- 顶部栏、用户菜单、语言切换（P4-03）
- 修改密码、自助重置密码（后端没有对应接口）
- 移动端访问令牌（P5）
