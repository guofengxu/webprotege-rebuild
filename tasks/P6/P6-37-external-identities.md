---
id: P6-37
title: 后端：外部身份绑定（user_identities 表，按 issuer + subject 解析本地账户的规则），供 OIDC 与企业微信登录使用
phase: P6
module: wp-app
type: impl
size: M
work_type: app-security
ecc_extra: [security]
status: todo
depends_on: [P6-29]
---

# P6-37 后端：外部身份绑定

## 目标
外部身份提供方（OIDC、企业微信）登录成功之后，系统需要把 `(issuer, subject)` 解析为本地账户。本卡提供表结构与解析规则，默认不自动建号、不按邮箱自动绑定。OIDC 登录流程在 P6-51，企业微信在 P6-39。（P6-19 评审后从原 P6-37 拆出。）

## 参考
- ADR-010：“OIDC（企业单点登录）放到 P6”
- `access/UserService.java`（`findById`、`create`）；P6-29：`UserAccount.deletedAt`；`V2__core_schema.sql`（`users.user_id` 即用户名；`password_hash` 为空表示“必须重置密码”）

## 产出
- `db/migration/V8__user_identities.sql`：`user_identities(issuer text not null, subject text not null, user_id text not null references users, created_at timestamptz not null default now(), last_login_at timestamptz, primary key (issuer, subject))`，`user_id` 上建索引。`CoreSchemaTest` 的表清单增加 `user_identities`。
- `access/ExternalIdentity.java`：`record ExternalIdentity(String issuer, String subject, String email, boolean emailVerified, String preferredUsername, String displayName)`。
- `access/ExternalLoginPolicy.java`：`record ExternalLoginPolicy(boolean linkByVerifiedEmail, boolean autoProvision)`，由调用方按身份提供方给出（P6-51 从每个提供方的配置读取，默认都为 `false`）。
- `access/ExternalIdentityService.java`：`Optional<UserId> resolve(ExternalIdentity identity, ExternalLoginPolicy policy)`，按顺序：
  1. 已有绑定 → 返回该用户（已删除的用户返回空）并更新 `last_login_at`；
  2. `policy.linkByVerifiedEmail()` 且 `emailVerified`，且恰好一个未删除用户的 `email` 相同（忽略大小写）、该用户**不是** `SYSTEM_ADMIN` → 建立绑定；系统管理员账户永远不自动绑定（需要时由运维在数据库中手工绑定，写进 P6-51 的文档）；
  3. `policy.autoProvision()` → 以规范化的 `preferredUsername` 为 `user_id` 新建用户（无密码、无应用角色）。规范化：转小写（`Locale.ROOT`）；形如邮箱时取 `@` 之前的部分；只保留 `[a-z0-9._-]`；结果长度须在 3 ~ 64 之间。不满足长度，或该 ID 已存在（包括已删除的用户）时返回空并记录告警；
  4. 否则返回空。
- `access/JdbcUserIdentityRepository.java`：按 `(issuer, subject)` 读取、插入（主键冲突时重新读取，处理并发首次登录）、更新 `last_login_at`。
- 测试 `access/ExternalIdentityServiceTest.java`（Testcontainers）：四条规则各一例；邮箱未验证时不按邮箱绑定；两个用户邮箱相同时不绑定；系统管理员不按邮箱绑定；已删除用户的绑定不生效；规范化（`Admin` 与已有的 `admin` 冲突、`John.Doe@corp.example` → `john.doe`、全部为非 ASCII 字符时被拒绝）；自动建号的用户没有密码、没有应用角色；同一身份并发首次登录只产生一个绑定。

## 实现要点
- `issuer` 按字符串精确比较；`email` 比较忽略大小写。
- 自动建号的用户没有密码，用账号密码登录时得到现有的 `PASSWORD_RESET_REQUIRED`，这是有意的（管理员可以为其重置密码）；P6-51 在 `docs/api/oidc.md` 中写明。
- 依赖 P6-29 是为了 Flyway 版本串行（V7 → V8）并使用它的 `deletedAt`。本卡不改 OpenAPI 与 `SecurityConfig`。

## ECC 技能
工作项类型：`app-security` —— 认证、授权、令牌与其它安全相关接口；附加：`security`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-37` 查看。

- 开工前加载的 skill：`springboot-security`、`security-review`、`springboot-tdd`、`java-coding-standards`
- 提交前调用的审查 agent：`security-reviewer`、`java-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='ExternalIdentityServiceTest,CoreSchemaTest,ModularityTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- OIDC 登录流程、登录方式列表接口（P6-51）；企业微信（P6-39）；登录页（P6-38）
- 查看或解绑外部身份的管理界面
