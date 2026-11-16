---
id: P6-38
title: Web 端：登录页显示“企业账号登录”入口，并提示外部账号未绑定等错误
phase: P6
module: wp-web
type: impl
size: S
work_type: web
ecc_extra: []
status: todo
depends_on: [P6-51, P6-28]
---

# P6-38 Web 端：登录页企业账号登录入口

## 目标
服务端配置了 OIDC 提供方时，登录页在账号密码表单下方为每个提供方显示一个“使用 <提供方名称> 登录”按钮；从提供方回到登录页且带错误参数时，显示可以理解的提示。没有配置提供方时，登录页与现在完全一样。

## 参考
- P6-51：`GET /api/auth/providers`（operationId `listLoginProviders`，返回 `{ id, displayName, loginUrl }[]`，无需登录）；失败时重定向到 `/login?error=oidc_unlinked` 或 `/login?error=oidc_failed`
- P4-25：`src/features/auth/LoginPage.vue`、`useAuth.ts`、`locales/`；P4-02：`src/shared/api/queryKeys.ts`

## 产出
- `src/features/auth/api.ts`（新建）：`listLoginProviders()`，类型来自 `@wp/api-types`。
- `src/shared/api/queryKeys.ts`：增加 `auth.providers`（`queryKeys.spec.ts` 同步）。
- `src/features/auth/LoginPage.vue`：
  - 用 Vue Query 读取提供方列表（失败时静默降级为不显示按钮，不阻挡账号密码登录）；
  - 每个提供方一个按钮，点击时整页跳转到 `loginUrl`（`window.location.assign`，不是路由跳转）；
  - 读取路由查询参数 `error`：`oidc_unlinked` 提示“该企业账号尚未关联本系统的用户，请联系管理员”，`oidc_failed` 提示“企业账号登录失败，请重试”；其它值忽略。
- `src/features/auth/locales/zh-CN.ts`、`en-US.ts`：新增文案。
- 测试 `src/features/auth/LoginPage.spec.ts`：没有提供方时没有按钮；两个提供方时有两个按钮且点击后跳转到对应地址；两种错误参数显示对应提示；提供方接口失败时账号密码表单仍可用。

## 实现要点
- 按钮是普通的导航，不带 CSRF 头，也不经过 `openapi-fetch` 客户端。
- 依赖 P6-28 只是为了串行修改 `queryKeys.ts`（P6-22 → P6-28 → 本卡）（见 EPIC「共享文件与迁移号（第二轮）」）。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-38` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend --filter wp-web exec vitest run src/features/auth/LoginPage.spec.ts src/shared/api/queryKeys.spec.ts
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 后端（P6-37、P6-51）
- 个人设置中查看或解绑外部身份
- 企业微信扫码（P6-39）
