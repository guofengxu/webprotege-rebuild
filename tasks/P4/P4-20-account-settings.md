---
id: P4-20
title: 个人设置页：API Key 的创建与撤销、已登录设备的查看与撤销
phase: P4
module: wp-web
type: impl
size: M
work_type: web
ecc_extra: []
status: todo
depends_on: [P4-03]
---

# P4-20 个人设置

## 目标
用户在“个人设置”页可以看到自己的账户信息，创建 API Key（明文只显示一次，可复制）、撤销 API Key，查看用移动端令牌登录过的设备并撤销。本卡对应 `docs/03-plan/mvp-scope.md` 中 Web 端的“个人设置”，EPIC 未单列。

## 参考
- `docs/03-plan/mvp-scope.md`（“API Key / 设备会话 | P3-05、P3-26 | 个人设置”）
- ADR-010（认证方式）
- P3-05：`createApiKey` 返回 `IssuedApiKeyResponse{keyId, name, apiKey, createdAt}`，明文 `apiKey` 只在这里返回一次；`listApiKeys` 返回 `ApiKeyInfo{keyId, name, createdAt, lastUsedAt}`；`revokeApiKey` 返回 204
- P3-26：`listDevices` 返回 `DeviceSession{tokenId, deviceName, createdAt, lastUsedAt, accessExpiresAt, refreshExpiresAt}`；`revokeDevice` 返回 204
- `docs/api/openapi.json` 的 operationId：`getCurrentUser`、`listApiKeys`、`createApiKey`、`revokeApiKey`、`listDevices`、`revokeDevice`
- P4-03 的用户菜单（“个人设置”→ `/me`）

## 产出
- `src/features/account/api.ts`：`useApiKeys()`（键 `queryKeys.apiKeys()`）、`useCreateApiKey()`、`useRevokeApiKey()`、`useDevices()`（键 `queryKeys.devices()`）、`useRevokeDevice()`；写操作成功后让对应列表失效。
- `src/features/account/AccountSettingsPage.vue`（`src/features/account/routes.ts` 以 `layout` 路由 `/me` 导出）：顶部显示用户 ID、显示名称、邮箱、应用角色；下方两个标签页“API Key”“设备”。
- `src/features/account/ApiKeysTab.vue`：表格（名称、创建时间、最后使用时间、撤销）；“新建”对话框输入名称（必填）；创建成功后用单独的对话框显示明文 key：等宽只读输入框 + “复制”按钮（`navigator.clipboard.writeText`，失败时提示手动复制）+ “此 key 只显示这一次”的警告；关闭对话框后明文从组件状态中清除，不进入查询缓存。
- `src/features/account/DevicesTab.vue`：表格（设备名、创建时间、最后使用时间、访问令牌与刷新令牌的过期时间、撤销）；撤销前确认。
- 文案：`src/features/account/locales/`（顶层键 `account`）。
- 测试：
  - `src/features/account/ApiKeysTab.spec.ts`：创建后显示明文与复制按钮；关闭对话框后页面中不再有明文，`queryClient` 缓存中也没有；撤销前确认并调用 `revokeApiKey`。
  - `src/features/account/DevicesTab.spec.ts`：渲染设备列表与过期时间；撤销确认后调用 `revokeDevice` 并刷新列表。

## 实现要点
- 明文 API Key 属于敏感数据：只存在于创建结果对话框的本地状态中，不写入 Pinia、`localStorage` 或 Vue Query 缓存（`useCreateApiKey` 的 `onSuccess` 只让列表失效，不把返回值写进列表缓存），也不记录到控制台。
- 时间按界面语言用 `Intl.DateTimeFormat` 显示；`lastUsedAt` 为空时显示“从未使用”。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P4-20` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend --filter wp-web exec vitest run src/features/account/ApiKeysTab.spec.ts src/features/account/DevicesTab.spec.ts
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 明文 API Key 关闭对话框后不再存在于页面和缓存中
- [ ] 执行记录已填写

## 不在本卡范围
- 修改自己的密码、显示名称、邮箱（后端没有对应接口）
- 签发移动端令牌（由移动端登录完成，P5）
