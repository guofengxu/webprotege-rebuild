---
id: P3-05
title: API Key 认证
phase: P3
module: wp-app/access, web
type: impl
size: S
work_type: app-security
ecc_extra: []
status: done
depends_on: [P3-04]
---

# P3-05 API Key 认证

## 目标
脚本和外部系统可以用 API Key 调用 REST 接口。

## 参考
- ADR-010
- 旧 REST 认证：`../webprotege/webprotege-server/src/main/java/edu/stanford/bmir/protege/web/server/api/`（`AuthenticationFilter`）

## 产出
- `wprebuild.app.access.ApiKeyService`：`IssuedKey issue(UserId, String name)`（明文只在这里返回一次，格式 `wpk_` + 32 字节随机数的 base62）；`Optional<UserId> authenticate(String plaintext)`（按 SHA-256 查找，并更新 `last_used_at`）；`List<ApiKeyInfo> list(UserId)`；`void revoke(UserId, UUID keyId)`。
- `wprebuild.app.web.security.ApiKeyAuthenticationFilter`：读取请求头 `X-API-Key`；带 API Key 的请求免 CSRF 检查。
- `wprebuild.app.web.ApiKeyController`：`POST /api/me/api-keys`、`GET /api/me/api-keys`、`DELETE /api/me/api-keys/{keyId}`。
- 测试 `ApiKeyTest`：签发的 key 可以访问 `/api/auth/me`；撤销后返回 401；数据库中只有摘要，没有明文；不能撤销别人的 key。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`app-security` —— 认证、授权、令牌与其它安全相关接口。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P3-05` 查看。

- 开工前加载的 skill：`springboot-security`、`security-review`、`springboot-tdd`、`java-coding-standards`
- 提交前调用的审查 agent：`security-reviewer`、`java-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ApiKeyTest
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写

## 不在本卡范围
- key 的权限范围（scope）

## 执行记录
- 日期：2026-11-08
- 分支：feature/p3-05-api-keys
- 测试：2026-11-08T17:56:01+08:00 通过（3 项）
- 实际改动：用户可以签发、列出和吊销自己的 API Key。请求头 X-API-Key 按 SHA-256 认证，明文只在签发时返回一次；带 key 的请求免 CSRF，并且不会把这次登录写进会话。
- 偏差：生产代码超过 S 卡 200 行的预期，因为服务、仓储、过滤器和控制器都是卡片要求的产出。必须重置密码的账户即使持有 key 也不能通过它登录，与会话登录一致。
- 审查：security-reviewer, java-reviewer
