# ADR-014 移动端：uni-app（Vue3 版）

- 状态：已接受
- 日期：2026-10-29

## 背景

需要一个移动端，供现场人员和评审人员查阅本体、跟踪变更。技术选型要求使用 uni-app 的最新版本。

核实结果（2026-10-29）：uni-app 的 Vue3 版本 `@dcloudio/uni-app@vue3` 最新为 `3.0.0-5030420261028001`（2026-10-28 发布），其构建插件 `@dcloudio/vite-plugin-uni` 固定依赖 **Vite 5.2.8** 和 **Vue 3.4.21**。这与 Web 端的 Vue 3.5 / Vite 8 不同。

## 决策

- `wp-mobile/`：uni-app Vue3 版，使用 **CLI 工程**（`@dcloudio/uni-*` 依赖写在 `package.json` 中，命令行构建），不依赖 HBuilderX 构建。这样 Cursor 和 CI 可以直接运行构建与测试。
- 语言 TypeScript 5.9.x；组件库 wot-design-uni；状态管理 Pinia（选与 uni-app 兼容的版本）。
- **首期目标平台**：H5 与 Android App。iOS App 与微信小程序放到 P6（小程序需要合法域名备案与 HTTPS，企业微信环境另行评估）。
- **与 Web 端的关系**：两个独立的工程，各自锁定依赖版本；只共享 `packages/api-types`（由 OpenAPI 生成的纯 TypeScript 类型）。**不共享 Vue 组件和运行时代码**，因为 Vue 版本不同，而且 uni-app 的组件体系（`view`、`text`、`uni.*` API）与浏览器 DOM 不同。
- **网络层**：封装 `uni.request`，类型来自 `packages/api-types`。不使用 `fetch`：App 和小程序端不支持。
- **认证**：Bearer 访问令牌 + 刷新令牌（ADR-010），令牌保存在 `uni.setStorageSync` 中。
- **实时更新**：使用长轮询接口（ADR-007）。
- **功能范围**：以查阅和跟踪为主，见 ADR-012。

## 理由

- uni-app 一套代码覆盖 H5、App 和小程序，与 Web 端同属 Vue 技术栈。
- 不强行统一 Vue / Vite 版本：uni-app 的编译器与它固定的 Vue 版本强绑定，修改会导致编译产物不可预期。

## 风险

- 在 pnpm 工作区中，uni-app 的部分工具对严格的依赖隔离（非 hoisted 布局）兼容性不确定。P5-01 骨架卡负责验证；如果有问题，给 `wp-mobile` 单独设置 `node-linker=hoisted`，或者把它移出工作区，单独安装依赖。
- uni-app 的版本号与 HBuilderX 的发布节奏绑定，更新频繁。依赖版本一律锁定在 `pnpm-lock.yaml` 中，升级时单独开卡。
