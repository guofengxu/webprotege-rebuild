---
id: P3-04
title: 用户账户、会话登录与首个管理员
phase: P3
module: wp-app/access, web
type: impl
size: M
work_type: app-security
ecc_extra: []
status: todo
depends_on: [P3-01]
---

# P3-04 用户账户、会话登录与首个管理员

## 目标
用户可以用账户密码登录，前端以会话方式访问 API。

## 参考
- ADR-010

## 产出
- `wprebuild.app.access.UserAccount`（record）、`JdbcUserRepository`、`UserService`：`create`、`findById`、`setPassword`、`verifyPassword`；密码用 `DelegatingPasswordEncoder`（默认 BCrypt）；`password_hash` 为空的用户无法登录，错误码 `PASSWORD_RESET_REQUIRED`。
- `wprebuild.app.access.BootstrapAdmin`：`users` 表为空且配置了 `wp.bootstrap.admin.username` / `password` / `email` 时，在启动时创建拥有 `SYSTEM_ADMIN` 的用户；否则什么都不做。
- `wprebuild.app.web.security.SecurityConfig`：`/api/**` 需要认证（`/api/auth/login` 除外）；会话 Cookie；CSRF 使用 `CookieCsrfTokenRepository.withHttpOnlyFalse()`；未认证返回 401 `ProblemDetail`（不重定向）；登录后的 `Authentication` 中，`app_roles` 映射为同名的 `GrantedAuthority`。
- `wprebuild.app.web.AuthController`：`POST /api/auth/login`（JSON `{username, password}`）、`POST /api/auth/logout`、`GET /api/auth/me`（返回 `{userId, displayName, email, appRoles}`）。
- 测试 `AuthFlowTest`（MockMvc + `@DatabaseTest`）：登录成功后 `me` 返回用户；密码错误返回 401；未登录访问 `/api/auth/me` 返回 401 且响应体为 ProblemDetail；需要重置密码的用户登录得到对应错误码；`BootstrapAdmin` 只在表为空时生效。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`app-security` —— 认证、授权、令牌与其它安全相关接口。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P3-04` 查看。

- 开工前加载的 skill：`springboot-security`、`security-review`、`springboot-tdd`、`java-coding-standards`
- 提交前调用的审查 agent：`security-reviewer`、`java-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=AuthFlowTest
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- API Key（P3-05）
- 管理员的用户管理 API（P3-25）
- OIDC（P6）
