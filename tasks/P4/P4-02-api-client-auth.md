---
id: P4-02
title: API 客户端、CSRF、ProblemDetail 错误处理、查询键、Vue Query 全局配置与测试支持
phase: P4
module: wp-web
type: impl
size: M
work_type: web
ecc_extra: []
status: todo
depends_on: [P4-01]
---

# P4-02 API 客户端与前端基础设施

## 目标
Web 端有一个类型安全的 API 客户端：自动携带 CSRF 令牌，把错误统一包装成带 `code` 的 `ApiError` 并给出本地化提示；全部查询键集中定义；Vue Query 有统一的默认配置；后续卡的组件测试有共用的假 API 和挂载工具。本卡与 P4-25（登录页、路由守卫、401 跳转）由 EPIC 第 2 项拆出。

## 参考
- `tasks/P4/EPIC.md` 第 2 项
- ADR-008（`openapi-fetch`、禁止手写请求与响应类型）、ADR-010（会话 Cookie + CSRF）
- P3-04（执行记录：Spring Security 7 `csrf.spa()`，Cookie `XSRF-TOKEN`、请求头 `X-XSRF-TOKEN`，path `/`）
- `backend/wp-app/src/main/java/wprebuild/app/web/security/SecurityConfig.java`：未认证统一返回 401 `ProblemDetail`；会话请求的 `POST` / `PUT` / `PATCH` / `DELETE` 都要 CSRF 头（包括登录）
- `backend/wp-app/src/main/java/wprebuild/app/web/ApiProblems.java`、`ProblemDetailsAdvice.java`：错误码 `VALIDATION_FAILED`、`PERMISSION_DENIED`、`NOT_FOUND`、`CONFLICT`、`PROJECT_TRASHED`、`FRAME_CHANGED`、`LAST_MANAGER`、`LAST_SYSTEM_ADMIN`、`UPLOAD_TOO_LARGE`、`ONTOLOGY_PARSE_FAILED`、`PASSWORD_RESET_REQUIRED`、`CONCURRENT_APPEND`、`INTERNAL_ERROR`
- `docs/api/openapi.json` 的 operationId：本卡不调用具体业务接口，测试中用 `getCurrentUser`、`createProject` 作为样例
- P4-01 的约定：文案按目录收集（`<目录>/locales/{zh-CN,en-US}.ts`，顶层键唯一）
- `.cursor/rules/30-vue-web.mdc`

## 产出
- 依赖（写入 `frontend/wp-web/package.json`，版本写死）：`openapi-fetch`（与 openapi-typescript 7 配套的最新 0.x）、`@tanstack/vue-query` 5.x。
- `src/shared/api/csrf.ts`：`readCsrfToken(cookie = document.cookie): string | undefined`，读取 `XSRF-TOKEN`（需要 `decodeURIComponent`）。
- `src/shared/api/client.ts`：
  - `export type ApiClient = Client<paths>`（`paths` 来自 `@wp/api-types`）。
  - `export function createApiClient(options?: { baseUrl?: string; fetch?: typeof fetch; onUnauthorized?: (response: Response) => void }): ApiClient`；`credentials: "same-origin"`。
  - 中间件：对 `POST` / `PUT` / `PATCH` / `DELETE` 在**发送时**读取 Cookie 并设置 `X-XSRF-TOKEN`（登录后令牌会轮换，不能缓存）；收到 401 且请求不是 `POST /api/auth/login` / `GET /api/auth/me` 时调用 `onUnauthorized`。
  - `export function provideApiClient(app: App, client: ApiClient)` 与 `export function useApiClient(): ApiClient`（`inject`，测试中可替换）。
- `src/shared/api/problem.ts`：
  - `export class ApiError extends Error { status: number; code?: string; detail?: string; problem?: ProblemDetail }`。
  - `export function unwrap<T>(result: { data?: T; error?: unknown; response: Response }): T`：`error` 存在或状态码不是 2xx 时抛出 `ApiError`（响应体不是 `ProblemDetail` 时用状态码兜底）。
  - `export function problemMessage(error: unknown, t: ComposerTranslation): string`：按 `code` 查 `errors.<CODE>`，没有 `code` 时按状态码查 `errors.http.<status>`，再没有则用 `errors.unknown`；`detail` 只作为补充信息显示在消息后面。
- `src/shared/api/locales/zh-CN.ts`、`en-US.ts`：顶层键 `errors`，上面列出的每个错误码都有文案，另有 `http.401/403/404/409/413/500`、`network`、`unknown`。
- `src/shared/api/queryKeys.ts`：全部查询键集中定义，后续卡只能从这里取键，事件失效（P4-14）依赖这个结构：
  - `me()` → `["auth", "me"]`；`projects()` → `["projects", "active"]`；`trashedProjects()` → `["projects", "trash"]`
  - `project(id)` → `["project", id]`（项目内一切数据的前缀）；`projectDetails(id)`；`hierarchy(id, kind)`；`hierarchyRoots(id, kind)`；`hierarchyChildren(id, kind, parentIri)`；`hierarchyPathsAll(id, kind)`；`hierarchyPaths(id, kind, entityIri)`；`frames(id)`；`frame(id, entityType, iri)`；`individualsAll(id)`；`individuals(id, typeIri, page, size)`；`searchAll(id)`；`search(id, q, types, limit)`（`limit` 也进键：P4-12 用 50、P4-26 用 20，避免共用缓存）；`revisionsAll(id)`；`revisions(id, page, size)`；`revisionDiff(id, rev, limit)`；`entityHistoryAll(id, iri)`；`entityHistory(id, iri, page, size)`；`settings(id, key)`；`members(id)`
  - `apiKeys()`、`devices()`、`adminUsersAll()`、`adminUsers(page, size)`
  - 每个键都以 `as const` 返回，项目内的键都以 `project(id)` 为前缀，层级相关的键都以 `hierarchy(id, kind)` 为前缀。带分页或查询参数的键都有一个不带这些参数的前缀键（`hierarchyPathsAll`、`frames`、`individualsAll`、`searchAll`、`revisionsAll`、`entityHistoryAll`、`adminUsersAll`），失效时用前缀键（P4-11、P4-14、P4-21、P4-23 依赖它们）；例如 `individuals(id, t, 0, 50)` 以 `individualsAll(id)` 开头，`frame(id, "CLASS", iri)` 以 `frames(id)` 开头。
- `src/app/queryClient.ts`：`export function createAppQueryClient(): QueryClient`；默认 `staleTime: 30_000`、`refetchOnWindowFocus: false`（实时更新由 P4-14 的事件负责）；`retry`：`ApiError` 且 `status < 500` 时不重试，其它最多 1 次；`mutations.onError` 不在全局弹消息（由调用处决定）。
- `src/main.ts`：创建 `QueryClient` 与 `ApiClient`，安装 `VueQueryPlugin`，调用 `provideApiClient`。`onUnauthorized` 由 P4-25 接到登录跳转，本卡先不传。
- 测试支持（后续卡共用，属于生产目录下的代码）：
  - `src/shared/testing/mockFetch.ts`：`createMockFetch(handlers: Record<string, MockHandler>): { fetch: typeof fetch; requests: RecordedRequest[] }`。键为 `"GET /api/projects/{id}"` 这种 OpenAPI 路径模板，按方法与路径匹配；`MockHandler = (req: { params: Record<string, string>; query: URLSearchParams; headers: Headers; body: unknown }) => MockResponse | Promise<MockResponse>`；`MockResponse = { status?: number; json?: unknown; problem?: Partial<ProblemDetail>; body?: BodyInit; headers?: Record<string, string> }`；未匹配的请求抛错让测试失败；`requests` 记录每个请求的方法、路径、查询参数、请求头和请求体。
  - `src/shared/testing/renderWithProviders.ts`：`renderWithProviders(component, options?: { props?: Record<string, unknown>; handlers?: Record<string, MockHandler>; routes?: RouteRecordRaw[]; route?: string; locale?: AppLocale }): Promise<{ wrapper: VueWrapper; router: Router; queryClient: QueryClient; requests: RecordedRequest[] }>`。挂载时提供内存路由、Pinia、i18n（用 P4-01 合并后的全部文案）、Element Plus、`retry: false` 的 `QueryClient` 和注入了假 `fetch` 的 `ApiClient`；`routes` 默认是一条渲染被测组件的通配路由，传入时用调用方给的路由表；`route` 是初始路径，返回前等待 `router.isReady()`；**不**安装登录守卫。
- 测试：
  - `src/shared/api/client.spec.ts`：`POST` 请求带上当前 Cookie 中的 `X-XSRF-TOKEN`，Cookie 变化后下一次请求带新值；`GET` 不带；401 触发 `onUnauthorized`，`GET /api/auth/me` 的 401 不触发。
  - `src/shared/api/problem.spec.ts`：`unwrap` 对 409 `FRAME_CHANGED` 抛出带 `code` 的 `ApiError`；非 JSON 的 500 也能得到 `ApiError`；`problemMessage` 的三级回退。
  - `src/shared/api/queryKeys.spec.ts`：所有项目内的键以 `project(id)` 开头；层级相关的键以 `hierarchy(id, kind)` 开头；每个带参数的键以对应的前缀键开头（`hierarchyPaths` → `hierarchyPathsAll`、`individuals` → `individualsAll`、`frame` → `frames`、`search` → `searchAll`、`revisions` → `revisionsAll`、`entityHistory` → `entityHistoryAll`、`adminUsers` → `adminUsersAll`）；不同参数得到不同的键。
  - `src/shared/testing/renderWithProviders.spec.ts`：被测组件发出的请求出现在 `requests` 中（含请求头与请求体）；传入 `routes` 与 `route` 后 `router.currentRoute` 正确；未匹配的请求让测试失败。

## 实现要点
- 类型全部从 `@wp/api-types` 的 `paths` / `components` 推导，不手写请求、响应类型（ADR-008）。`ApiError`、`MockHandler` 是客户端和测试自己的包装，不是 API 类型。
- 本卡不做任何界面；`createApiClient` 不依赖路由，401 的界面处理由 P4-25 通过 `onUnauthorized` 接入。
- Pinia 不存服务端数据。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P4-02` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend install
pnpm -C frontend --filter wp-web exec vitest run src/shared/api/client.spec.ts src/shared/api/problem.spec.ts src/shared/api/queryKeys.spec.ts src/shared/testing/renderWithProviders.spec.ts
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 代码中没有手写的请求或响应类型
- [ ] 执行记录已填写

## 不在本卡范围
- 登录页、当前用户、路由守卫、401 跳转（P4-25）
- 顶部栏、用户菜单、语言切换（P4-03）
- 移动端访问令牌（`issueAccessTokens` 等，P5）
