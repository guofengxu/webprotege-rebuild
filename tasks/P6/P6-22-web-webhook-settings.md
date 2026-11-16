---
id: P6-22
title: Web 端：项目设置的 webhook 标签页（列表、新建与编辑、密钥只写、最近投递记录）
phase: P6
module: wp-web
type: impl
size: M
work_type: web
ecc_extra: []
status: todo
depends_on: [P6-49]
---

# P6-22 Web 端：项目设置的 webhook 标签页

## 目标
项目管理者在项目设置中多一个“Webhook”标签页：查看本项目的 webhook 及其最近一次投递结果，新建、编辑、启用 / 停用、删除 webhook，设置或清除签名密钥（密钥只能写入，不能查看），并查看某个 webhook 最近的投递记录。

## 参考
- P6-49：`listWebhooks`、`createWebhook`、`updateWebhook`、`deleteWebhook`、`listWebhookDeliveries`，`WebhookResponse.hasSecret`、`lastDelivery`，错误码 `INVALID_WEBHOOK_URL`、`WEBHOOK_TARGET_NOT_ALLOWED`、`WEBHOOK_LIMIT_REACHED`
- P4-15：`src/features/settings/settingsTabs.ts`（`import.meta.glob` 自动注册 `*.tab.ts`，导出 `settingsTab = { key, labelKey, order, component }`）；`members/members.tab.ts`（`order` 为 50）可作参照
- P6-04、P6-06：`src/features/permissions/useProjectPermissions.ts`；没有权限时设置为只读的做法
- P4-02：`src/shared/api/queryKeys.ts`、`problem.ts`

## 产出
- `src/features/settings/webhooks/webhooks.tab.ts`：`key` 为 `"webhooks"`，`order` 为 60。
- `src/features/settings/webhooks/api.ts`：五个接口的封装，类型来自 `@wp/api-types`。
- `src/shared/api/queryKeys.ts`：增加 `webhooks.list(projectId)`、`webhooks.deliveries(projectId, webhookId)`（`queryKeys.spec.ts` 同步）。
- `src/features/settings/webhooks/WebhooksTab.vue`：表格列出回调地址（只显示协议与主机名，完整地址在编辑对话框中可见）、事件类型、是否启用、是否设置了密钥、最近一次投递（状态、HTTP 状态码、时间、错误摘要）；操作：编辑、启用 / 停用、删除（二次确认）、查看投递记录。没有 `EDIT_PROJECT_SETTINGS` 时不发请求，只显示“只有项目管理者可以查看和管理 webhook”。
- `src/features/settings/webhooks/WebhookFormDialog.vue`：回调地址、事件类型（目前只有 `PROJECT_CHANGED`，默认勾选）、启用开关、密钥（新建时可选填；编辑时显示“已设置 / 未设置”，可以选择“保持不变 / 替换 / 清除”）；提交前做基本校验（绝对的 http / https 地址、密钥至少 16 个字符），服务端的三个错误码分别显示在对应字段上。
- `src/features/settings/webhooks/WebhookDeliveriesDrawer.vue`：最近 20 条投递（状态、尝试次数、HTTP 状态码、错误、创建与完成时间），可以手动刷新。
- `src/features/settings/locales/zh-CN.ts`、`en-US.ts`：新增文案（键在两种语言中一致）。
- 测试：`WebhooksTab.spec.ts`（列表渲染、无权限时不请求、删除确认、启用 / 停用）、`WebhookFormDialog.spec.ts`（校验、三种密钥操作产生的请求体、服务端错误显示在字段上）、`WebhookDeliveriesDrawer.spec.ts`。

## 实现要点
- 界面上任何地方都不出现密钥的值；密钥输入框用 `type="password"`，提交后清空。
- 变更成功后让 `webhooks.list` 失效；投递记录不自动轮询，只在打开抽屉与点“刷新”时请求。
- 载荷格式与签名验证的说明在 P6-21 写入的 `docs/api/README.md` 中；本卡在标签页顶部放一句简短的文字提示即可，不新增路由。本卡与 P6-21 都只依赖 P6-49，可以并行。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-22` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend --filter wp-web exec vitest run src/features/settings/webhooks src/features/settings/settingsTabs.spec.ts src/shared/api/queryKeys.spec.ts
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 后端接口与投递（P6-20、P6-21）
- 手动重发、“发送测试请求”
