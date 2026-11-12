---
id: P4-03
title: 主布局与顶部栏、Element Plus 主题与设计令牌、zh-CN / en-US 界面语言切换
phase: P4
module: wp-web
type: impl
size: M
work_type: web
ecc_extra: []
status: done
depends_on: [P4-25]
---

# P4-03 布局、主题与国际化

## 目标
登录后的页面都套在统一的主布局里：顶部栏有产品名、当前项目名、搜索框占位、语言切换和用户菜单；界面颜色、间距、字号来自一套设计令牌；界面文字可以在中文和英文之间切换并记住选择。

## 参考
- `tasks/P4/EPIC.md` 第 3 项
- ADR-008（`vue-i18n` 至少 zh-CN 与 en-US；本体内容的显示名称由后端决定，不随界面语言变化）
- `.cursor/rules/30-vue-web.mdc`
- P4-25 的 `useCurrentUser` / `useLogout` / `isSystemAdmin`
- `docs/api/openapi.json` 的 operationId：`getCurrentUser`、`logout`、`getProject`（只用于顶部栏显示项目名）

## 产出
- `src/app/theme/tokens.css`：设计令牌，全部是 `:root` 上的 CSS 自定义属性，前缀 `--wp-`：颜色（品牌主色、语义色 success / warning / danger / info、文字三级、边框、背景两级）、间距刻度（4 的倍数，`--wp-space-1` … `--wp-space-8`）、圆角、字号刻度、等宽字体（用于 IRI 与 Manchester 文本）、顶部栏高度。
- `src/app/theme/element-overrides.css`：把 Element Plus 的 `--el-color-primary` 等变量映射到 `--wp-*` 令牌；不得在组件里直接写十六进制颜色。
- `src/app/theme/index.ts`：在 `main.ts` 中按顺序引入 Element Plus 样式、`tokens.css`、`element-overrides.css`；同时删除 P4-01 在 `main.ts` 中直接引入的 `element-plus/dist/index.css`，避免重复引入。
- `src/app/i18n.ts` 扩展：
  - `export const SUPPORTED_LOCALES: readonly AppLocale[]`；
  - `export function useAppLocale(): { locale: Ref<AppLocale>; setLocale(l: AppLocale): void }`：切换 `vue-i18n` 语言、Element Plus 的 `ElConfigProvider` 语言（`element-plus/es/locale/lang/zh-cn` 与 `en`）、`<html lang>`，并写入 `localStorage` 的 `wp.locale`；
  - 初始语言：`localStorage` 有效值 → `navigator.language` 以 `zh` 开头取 `zh-CN` → 否则 `en-US`。
- 文案：沿用 P4-01 的按目录收集方式（每个目录的 `locales/zh-CN.ts`、`en-US.ts`，顶层键唯一，`en-US` 用 `satisfies` 对齐 `zh-CN`）；本卡的文案放在 `src/app/layout/locales/`（顶层键 `layout`）。
- `src/App.vue`：用 `ElConfigProvider` 包裹 `<RouterView />`（登录页不在主布局里，也要跟随语言切换）。
- `src/app/layout/AppLayout.vue`：顶部 `AppTopBar` + 主体 `<RouterView />`。
- `src/app/layout/AppTopBar.vue`：
  - 左侧：产品名（链接到 `/`），在 `/projects/:projectId/**` 路由下显示当前项目名（`useQuery` 调 `getProject`，键 `queryKeys.projectDetails(id)`）。
  - 中间：`<slot name="search" />` 的固定位置，P4-12 放入搜索框；本卡留空。
  - 右侧：`LocaleSwitcher.vue`（下拉，显示“中文 / English”）；用户菜单（显示 `displayName`；菜单项“个人设置”→ `/me`、“用户管理”→ `/admin/users`（仅 `SYSTEM_ADMIN` 可见）、“退出登录”调 `useLogout`）。`/me` 与 `/admin/users` 页面分别由 P4-20、P4-21 实现，本卡只放菜单项。
- `src/app/router.ts`：把各功能 `routes.ts` 导出的 `layout` 路由挂为 `AppLayout` 的子路由（父路由 `path: "/"`、`meta.requiresAuth: true`）；`root` 路由（如 `/login`）仍在顶层；`src/features/home/routes.ts` 的首页从 `root` 改为 `layout`。
- 测试：
  - `src/app/locales.spec.ts`：对合并后的 `zh-CN` 与 `en-US` 递归比较键集合，完全一致；所有叶子值都是非空字符串（以后每张卡新增的文案都受这个测试约束）。
  - `src/app/i18n.spec.ts`：`setLocale("en-US")` 后文案、`<html lang>` 和 `localStorage` 都更新；`localStorage` 中的非法值被忽略。
  - `src/app/layout/AppTopBar.spec.ts`：普通用户看不到“用户管理”，`SYSTEM_ADMIN` 能看到；点击“退出登录”调用 `logout` 并跳转 `/login`；在项目路由下显示 `getProject` 返回的 `displayName`（`AppTopBar` 从 `route.params.projectId` 读取项目 ID；项目路由由 P4-05 提供，测试中用 `renderWithProviders` 的 `routes` 注册一条桩路由 `/projects/:projectId`）。

## 实现要点
- 设计令牌用 `design-system` skill 产出（EPIC 第 3 项的要求；它不在 `tools/ecc-routing.json` 的 `web` 路由中，是本卡额外要加载的 skill），结果写成上面的 CSS 文件，不新建设计系统包或文档目录；视觉方向参考 `frontend-design-direction`：这是信息密度高的编辑器，默认字号 14px、紧凑间距，不做营销页风格。
- 颜色对比度至少满足 WCAG 2.2 AA（`accessibility` skill）；语言切换、用户菜单都要能用键盘操作，并有可读的 `aria-label`。
- 本体实体的显示名称、IRI、Manchester 文本都来自后端，不进 `vue-i18n`。
- 不引入 `unplugin-vue-components` / `unplugin-auto-import`；Element Plus 采用 P4-01 的全量注册。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P4-03` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend --filter wp-web exec vitest run src/app/locales.spec.ts src/app/i18n.spec.ts src/app/layout/AppTopBar.spec.ts
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 组件中没有硬编码的颜色值和界面文字
- [x] 执行记录已填写

## 不在本卡范围
- 搜索框本身（P4-12）、个人设置页（P4-20）、用户管理页（P4-21）
- 深色模式
- 第三种界面语言

## 执行记录
- 日期：2026-11-11
- 分支：feature/p4-03-layout-theme-i18n
- 测试：2026-11-11T22:51:41+08:00 通过（3 项）
- 实际改动：Added AppLayout/AppTopBar/LocaleSwitcher, --wp-* design tokens with Element Plus overrides, useAppLocale with localStorage persistence, and routed home under the authenticated layout.
- 偏差：Left document.title and 320px top-bar reflow as follow-ups; auth guard still uses a separate i18n composer for warning copy (no longer writes html lang).
- 审查：vue-reviewer, typescript-reviewer, a11y-architect, (HIGH, fixes:, html, lang, side, effect, EP, light/dark, scales, Label-in-Name, aria-labels, readonly, locale, via, setLocale, non-colliding, project, query, key)
