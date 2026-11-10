---
id: P3-26
title: 移动端访问令牌与 CORS
phase: P3
module: wp-app/access, web
type: impl
size: M
work_type: app-security
ecc_extra: []
status: done
depends_on: [P3-05]
---

# P3-26 移动端访问令牌与 CORS

## 目标
uni-app 客户端可以不依赖 Cookie，用 Bearer 令牌安全地调用 API。

## 参考
- ADR-010（移动端令牌）
- ADR-014

## 产出
- Flyway `V3__access_tokens.sql`：`access_tokens(token_id uuid primary key, user_id text not null references users, device_name text not null, access_sha256 bytea not null unique, refresh_sha256 bytea not null unique, access_expires_at timestamptz not null, refresh_expires_at timestamptz not null, created_at timestamptz not null default now(), last_used_at timestamptz, revoked_at timestamptz)`。
- `wprebuild.app.access.AccessTokenService`：`IssuedTokens issue(UserId, String deviceName)`；`Optional<UserId> authenticate(String accessToken)`；`IssuedTokens refresh(String refreshToken)`（旧的刷新令牌立即失效，轮换）；`void revoke(UserId, UUID tokenId)`；`List<DeviceSession> list(UserId)`。令牌为 32 字节随机数的 base62，前缀分别为 `wpa_`、`wpr_`；库中只存 SHA-256；有效期由 `wp.tokens.access-ttl`（默认 12h）、`wp.tokens.refresh-ttl`（默认 30d）配置。
- **刷新令牌重放检测**：已被轮换掉的刷新令牌再次出现时，吊销该设备会话的全部令牌，返回 401，错误码 `REFRESH_TOKEN_REUSED`。
- `wprebuild.app.web.security.BearerTokenAuthenticationFilter`：读取 `Authorization: Bearer`；带令牌的请求免 CSRF 检查。
- REST：`POST /api/auth/tokens`（JSON `{username, password, deviceName}`，返回 `{accessToken, accessExpiresAt, refreshToken, refreshExpiresAt}`）、`POST /api/auth/tokens/refresh`、`GET /api/me/devices`、`DELETE /api/me/devices/{tokenId}`。
- CORS：`wp.cors.allowed-origins`（默认为空，即不开放）；配置后只对这些来源开放 `/api/**`，允许 `Authorization` 请求头。
- 测试 `AccessTokenTest`：签发、访问、过期（用可控的 `Clock`）、刷新与轮换、重放检测、吊销、无法吊销他人的设备；`CorsTest`：未配置时预检请求被拒绝，配置后放行。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`app-security` —— 认证、授权、令牌与其它安全相关接口。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P3-26` 查看。

- 开工前加载的 skill：`springboot-security`、`security-review`、`springboot-tdd`、`java-coding-standards`
- 提交前调用的审查 agent：`security-reviewer`、`java-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='AccessTokenTest,CorsTest'
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写

## 不在本卡范围
- OIDC / 企业微信登录（P6）
- 推送通知

## 执行记录
- 日期：2026-11-10
- 分支：feature/p3-26-mobile-access-tokens
- 测试：2026-11-10T21:17:16+08:00 通过（3 项）
- 实际改动：移动端可以用账号密码换取不透明的访问令牌和刷新令牌。Authorization: Bearer 免 CSRF，并且不会把这次登录写进会话。刷新会轮换令牌；重放已轮换的刷新令牌会吊销该设备并返回 REFRESH_TOKEN_REUSED。CORS 默认关闭，只对配置的来源开放 /api/**。
- 偏差：access_tokens 只保存当前摘要，因此另加 rotated_refresh_tokens 记住每一次被轮换掉的刷新令牌，多次轮换后仍能识别重放。必须重置密码的账户不能签发或轮换令牌，与会话登录和 API Key 一致。生产代码约 980 行，超过 400 行预期，因为服务、仓储、过滤器和四个接口都是卡片要求的产出。
- 审查：security-reviewer, java-reviewer
