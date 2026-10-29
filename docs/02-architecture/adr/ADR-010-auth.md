# ADR-010 认证与授权

- 状态：已接受
- 日期：2026-10-28
- 修订：2026-10-29 —— 增加移动端访问令牌。

## 决策
- Spring Security 7：
  - **Web 端**：会话登录（JSON 登录接口 + 会话 Cookie + CSRF Cookie）。
  - **移动端**：访问令牌。`POST /api/auth/tokens` 用账号密码换取**不透明**访问令牌（默认 12 小时）和刷新令牌（默认 30 天，使用一次即轮换）；请求头 `Authorization: Bearer <令牌>`；库中只存 SHA-256 摘要；可以在“我的设备”中吊销。不使用 JWT：吊销和轮换都需要查库，不透明令牌更简单。
  - **自动化调用**：API Key（请求头 `X-API-Key`，库中只存 SHA-256 摘要）。
  - 带 Bearer 令牌或 API Key 的请求免 CSRF 检查。
- 密码使用 `DelegatingPasswordEncoder`，默认 BCrypt。
- 授权模型采用 `docs/01-rea/behavior-specs.md` BS-10 的 MVP 子集；权限检查只在命令总线的校验链中执行（ADR-007）。
- 首个管理员通过配置项 `wp.bootstrap.admin.*` 在首次启动时创建，之后忽略该配置。
- CORS：只对配置项 `wp.cors.allowed-origins` 中列出的来源开放（用于移动端 H5 开发调试），默认不开放。
- OIDC（企业单点登录）放到 P6。

## 后果
旧系统的加盐 MD5 密码不迁移（`docs/01-rea/legacy-data.md` 第 3 节）。
