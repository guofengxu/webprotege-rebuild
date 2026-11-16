---
id: P6-51
title: 后端：通用 OpenID Connect 登录（授权码流程、与账号密码登录相同的会话、登录方式列表接口）
phase: P6
module: wp-app
type: impl
size: M
work_type: app-security
ecc_extra: [security]
status: todo
depends_on: [P6-37, P6-52]
---

# P6-51 后端：通用 OIDC 登录

## 目标
配置了企业身份提供方（Keycloak、Entra ID、Authing 等任何标准 OIDC 提供方）之后，用户可以选择“企业账号登录”，经授权码流程登录，得到与账号密码登录完全相同的会话。（P6-19 评审后从原 P6-37 拆出。）

## 参考
- ADR-010：Web 端会话登录（会话 Cookie + CSRF Cookie）
- P6-37：`access/ExternalIdentityService`、`ExternalIdentity`、`ExternalLoginPolicy`
- P6-52：`AuthController#establishSession` 中的 `SessionRegistry` 登记、`DeletedAccountFilter`
- `web/security/SecurityConfig.java`：`apiSecurity`（`@Order(0)`）、`mcpSecurity`（`@Order(1)`）、`staticContent`（`@Order(2)`，无状态，其余路径全部放行）
- Spring Security 7 参考文档：OAuth2 Login、`OidcUserService`、`AuthenticationSuccessHandler`

## 产出
- 依赖：`backend/wp-app/pom.xml` 增加 Spring Boot 4 的 OAuth2 客户端 starter `spring-boot-starter-security-oauth2-client`（版本由 `spring-boot-dependencies` 管理；以该 BOM 中的实际名称为准，旧名 `spring-boot-starter-oauth2-client`）。不引入其它第三方库。
- `web/security/OidcProperties.java`：`wp.oidc.enabled`（默认 `false`）；每个提供方 `wp.oidc.providers.<registrationId>.display-name`、`.link-by-verified-email`（默认 `false`）、`.auto-provision`（默认 `false`）。客户端注册使用标准的 `spring.security.oauth2.client.*`。
- `web/security/SessionLogin.java`：把 `AuthController#establishSession` 的逻辑（更换会话 ID、写入 `UsernamePasswordAuthenticationToken(userId, null, 应用角色)`、保存安全上下文、登记 `SessionRegistry`）提成一个组件，`AuthController` 与 OIDC 共用，行为不变。
- `web/security/OidcLoginSuccessHandler.java`：从 `OidcUser` 取 `iss`、`sub`、`email`、`email_verified`、`preferred_username`、`name` 构造 `ExternalIdentity`，按该提供方的配置构造 `ExternalLoginPolicy`，调用 `ExternalIdentityService.resolve`；成功时用 `SessionLogin` 建立与账号密码登录**相同类型**的会话（不把 `OAuth2AuthenticationToken` 留在会话里），重定向到 `/`；失败时清空会话，重定向到 `/login?error=oidc_unlinked`。失败处理器重定向到 `/login?error=oidc_failed`，URL 中不带提供方返回的错误详情。
- `web/security/SecurityConfig.java`：`wp.oidc.enabled=true` 时新增外部登录过滤链（`securityMatcher("/oauth2/**", "/login/oauth2/**")`，`@Order(2)`，使用会话，`oauth2Login` 使用上面的处理器）；`staticContent` 改为 `@Order(3)`。未开启时不注册该链，也不要求存在任何客户端注册。
- `web/AuthController.java`：`GET /api/auth/providers`（无需登录，operationId `listLoginProviders`），返回 `[{ id, displayName, loginUrl }]`，`loginUrl` 为 `/oauth2/authorization/{id}`；未开启时返回空数组。
- `docs/api/openapi.json` 更新快照；`OpenApiResponseSchemasTest` 增加该响应；`pnpm -C frontend gen` 重新生成类型。
- `docs/api/oidc.md`：配置示例（Keycloak 与通用提供方各一例，只写占位值，不写真实凭据）；回调地址 `{baseUrl}/login/oauth2/code/{registrationId}`；绑定规则与每个提供方的两个开关（Entra ID 等提供方不发送 `email_verified`，按邮箱绑定只应对可信的提供方开启；系统管理员账户永远不自动绑定，需要时由运维手工插入 `user_identities`）；自动建号的用户没有密码，账号密码登录得到 `PASSWORD_RESET_REQUIRED`；反向代理下需要正确的 `X-Forwarded-*`。
- 测试 `web/OidcLoginTest.java`（MockMvc，测试配置中用静态的 `ClientRegistration`：显式写出 authorization / token / jwk-set URI，不访问网络）：`GET /oauth2/authorization/test` 重定向到授权地址且带 `state` 与 `nonce`；直接调用成功处理器（构造 `OidcUser`）后，会话中的认证主体是 `userId` 字符串、会话已登记到注册表，`GET /api/auth/me` 返回该用户；未绑定时重定向到 `/login?error=oidc_unlinked` 且没有登录态；`/api/auth/providers` 在开启与未开启时的返回；未开启时 `/oauth2/authorization/test` 不触发 OAuth2 流程。回归：`AuthFlowTest`、`DeletedUserSessionTest` 不变通过。

## 实现要点
- 安全：`state`、`nonce`、PKCE 用 Spring Security 默认实现；只信任 ID Token 中的 `iss` 与 `sub`；重定向目标固定为站内路径，不接受外部传入的回跳地址（防开放重定向）。
- 会话统一：OIDC 登录之后，系统其余部分看到的认证对象与账号密码登录完全相同，所以命令总线、权限、SSE、`/api/auth/me` 都不需要修改。
- 登出只结束本系统的会话，不做 RP-Initiated Logout。
- CI 中没有真实的身份提供方：端到端换取令牌不在自动测试中覆盖。在执行记录中写明一次用本地 Keycloak（Docker）手工冒烟的结果。
- 依赖 P6-52 是为了串行修改 `SecurityConfig.java`、`AuthController.java`（并复用其会话登记），OpenAPI 生成物在本轮串行链中排在 P6-29 之后。

## ECC 技能
工作项类型：`app-security` —— 认证、授权、令牌与其它安全相关接口；附加：`security`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-51` 查看。

- 开工前加载的 skill：`springboot-security`、`security-review`、`springboot-tdd`、`java-coding-standards`
- 提交前调用的审查 agent：`security-reviewer`、`java-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='OidcLoginTest,AuthFlowTest,DeletedUserSessionTest,OpenApiContractTest,OpenApiResponseSchemasTest,ModularityTest'
pnpm -C frontend install
pnpm -C frontend --filter @wp/api-types run check
pnpm -C frontend --filter wp-web run typecheck
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写（含本地 Keycloak 手工冒烟结果）

## 不在本卡范围
- 登录页入口（P6-38）；企业微信（P6-39）
- 移动端（P5 暂停）用 OIDC 换取访问令牌
- 单点登出、按身份提供方的组映射角色、解绑外部身份的管理界面
