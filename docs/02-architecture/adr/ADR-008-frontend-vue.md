# ADR-008 Web 前端：Vue 3.5 + Vite 8 + Element Plus

- 状态：已接受
- 日期：2026-10-28
- 修订：2026-10-29 —— 由 React 改为 Vue（原文件 `ADR-008-frontend-react.md` 已被本文件替代）。

## 决策
- `wp-web/`：Vue 3.5.x（Composition API + `<script setup>`）、TypeScript strict、Vite 8、Vue Router、Pinia（界面状态）、TanStack Vue Query（服务端数据）。版本见 ADR-002。
- 组件库：Element Plus。本体层级可能有上万个节点，层级树使用虚拟滚动的 `el-tree-v2`，子节点懒加载。
- API 类型由 OpenAPI 契约生成到 `packages/api-types`，Web 端用 `openapi-fetch` 调用，**禁止手写请求和响应类型**。
- 国际化：`vue-i18n`，至少提供 zh-CN 与 en-US 两种界面语言。本体内容本身的多语言显示由后端的显示名称设置决定（BS-09），与界面语言无关。
- 生产构建产物由 `wp-app` 作为静态资源提供，单个可执行包部署。

## 理由
- 团队与移动端（uni-app 基于 Vue）统一技术栈，Agent 和人都只需维护一套前端知识。
- Element Plus 提供成熟的虚拟树、表格和表单组件，适合编辑器这种信息密度高的后台界面。

## 后果
- 旧 GWT 前端约 9.2 万行，没有复用价值，从 MVP 功能集开始重写（`docs/03-plan/mvp-scope.md`）。
- Vue 3.6 正式发布后，单独开一张升级卡，不在功能卡中顺带升级。
