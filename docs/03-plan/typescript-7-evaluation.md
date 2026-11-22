# TypeScript 7 评估（wp-web）

评估日期：2026-11-22。任务卡：P6-42。

## 结论

**保持 TypeScript 6.0.3。** 将 `typescript` 直接升到 7.0.2 后，`vue-tsc --noEmit` 与 `eslint .` 均失败；按卡片要求撤回依赖改动，不做旁路（不关类型检查 / lint，也不改成 `@typescript/typescript6` 双安装）。

## 环境与版本

| 项 | 值 |
|---|---|
| Node | v22.23.4 |
| pnpm | 12.18.0 |
| 评估前（基线） | `typescript` 6.0.3、`vue-tsc` 3.3.14、`@vue/eslint-config-typescript` 14.9.0、`eslint` 10.14.0 |
| 试升级 | `typescript` 7.0.2（npm `latest`） |
| 未改动 | `vue-tsc` 3.3.14；`@wp/api-types` 仍用 TypeScript 5.9.3（不在本卡范围） |
| `tsconfig` | 未改；7.0.2 下未跑到需要改编译选项的阶段 |

TypeScript 7.0 为 Go 原生编译器，**不提供**稳定的 JavaScript 编程接口（`createProgram` 等为 `undefined`，且不导出 `typescript/lib/tsc`）。`vue-tsc` 与 `typescript-eslint` 仍依赖该 API。

## 逐步结果

### 基线（TypeScript 6.0.3）

| 步骤 | 结果 | 耗时（wall） |
|---|---|---|
| `vue-tsc --noEmit` | 通过 | **27.57 s** |
| `tsc --version` | `Version 6.0.3` | — |

### 试升级（TypeScript 7.0.2）

| 步骤 | 结果 | 耗时（wall） | 说明 |
|---|---|---|---|
| `vue-tsc --noEmit` | **失败** | 0.50 s | 见下方摘录；未完成类型检查，故无法与 6.0.3 比有效耗时 |
| `eslint .` | **失败** | 2.58 s | `typescript-eslint` 拒绝 TS 7.0 |
| `vitest run` | **失败** | 49.65 s | 340 测中 1 失败（见下）；其余 339 通过 |
| `vite build` | 通过 | 5.07 s | 构建不依赖 `vue-tsc` / typed ESLint |

#### `vue-tsc` 错误摘录

```text
Error [ERR_PACKAGE_PATH_NOT_EXPORTED]: Package subpath './lib/tsc' is not defined by "exports"
  in .../vue-tsc@3.3.14_typescript@7.0.2/node_modules/typescript/package.json
    at resolveTscPath (.../vue-tsc/index.js:73:43)
```

`vue-tsc@3.3.14` 仍解析 `typescript/lib/tsc`；TS 7.0.2 不导出该子路径。`peerDependencies` 写的 `typescript >= 5.0.0` 不能代表原生编译器可用。

#### `eslint` 错误摘录

```text
typescript-eslint does not support TS 7.0.
Please see https://devblogs.microsoft.com/typescript/announcing-typescript-7-0/#running-side-by-side-with-typescript-6.0
See also https://github.com/typescript-eslint/typescript-eslint/issues/10940
```

解析到的 `typescript-eslint` 为 8.72.2（经 `@vue/eslint-config-typescript`）。

#### `vitest` 失败摘录

```text
FAIL  src/features/workspace/WorkspacePage.spec.ts
  > opening with ?entity= shows the same IRI in the detail panel
AssertionError: expected '...' to contain 'http://example.org/ont#Pizza'
```

在缺少 TypeScript JS API 的环境下出现；**不**据此改测试或实现。撤回 7.0.2 后以 `pnpm --filter wp-web run verify` 为准。

## 耗时对比

| 命令 | TS 6.0.3 | TS 7.0.2 |
|---|---|---|
| `vue-tsc --noEmit` | 27.57 s（成功） | 0.50 s（启动即失败，无有效对比） |
| `eslint .` | （基线未单独计时；由 verify 覆盖） | 2.58 s（启动即失败） |
| `vitest run` | — | 49.65 s（有失败） |
| `vite build` | — | 5.07 s（成功） |

在 `vue-tsc` / `eslint` 能跑通之前，**无法**衡量原生编译器对 wp-web 类型检查的加速。

## 需要关注的上游

| 上游 | 现状（评估时） | 跟踪 |
|---|---|---|
| TypeScript | 7.0.2 无稳定 JS API；7.1 计划提供新 API | [Announcing TypeScript 7.0](https://devblogs.microsoft.com/typescript/announcing-typescript-7-0/)（含 side-by-side / Vue 说明） |
| `vue-tsc` / Vue Language Tools | 发布包仍依赖 TS 6 JS API；3.3.14 仍解析 `lib/tsc` | [vuejs/language-tools#5381](https://github.com/vuejs/language-tools/issues/5381)、[#6167](https://github.com/vuejs/language-tools/issues/6167) |
| `typescript-eslint` | 明确不支持 TS 7.0；7.1 支持进行中 | [#10940](https://github.com/typescript-eslint/typescript-eslint/issues/10940) |

本卡**不做** Microsoft 公告中的双安装（`typescript` → `@typescript/typescript6`，`@typescript/native` → `typescript@7`）：那不是把 wp-web 的 `typescript` 升到原生 7，且会绕过本卡要验证的兼容性结论。

## 重新评估条件

同时满足后再开卡试升（仍只动 wp-web）：

1. `vue-tsc` 正式版可在**仅安装** `typescript@7.x`（非 typescript6 别名）下完成 `vue-tsc --noEmit`；或官方文档明确支持该组合。
2. `typescript-eslint`（及 `@vue/eslint-config-typescript` 所拉版本）正式支持 `typescript@7.x`，`eslint .` 不再因版本拒绝退出。
3. 上述版本下 `vitest run` 与 `vite build` 通过；再比较 `vue-tsc` 与基线 6.0.3 的耗时。
