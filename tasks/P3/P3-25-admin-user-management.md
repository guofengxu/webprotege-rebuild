---
id: P3-25
title: 管理员的用户管理 API
phase: P3
module: wp-app/web（admin 子包）
type: impl
size: S
work_type: app-security
ecc_extra: []
status: todo
depends_on: [P3-07]
---

# P3-25 管理员的用户管理 API

## 目标
系统管理员可以创建用户、重置密码（包括迁移过来、没有密码的用户）和分配应用级角色。

## 参考
- BS-10（应用级动作 CREATE_ACCOUNT、RESET_ANY_USER_PASSWORD、DELETE_ANY_ACCOUNT）
- `docs/01-rea/legacy-data.md` 第 3 节（迁移用户需要重置密码）

## 产出
- 命令（处理器位于 `wprebuild.app.web.admin`，因为 `access` 模块不能依赖 `bus`；处理器只调用 `access` 公开的 `UserService`，经 `CommandBus` 校验）：`ListUsers(int page, int size)`、`CreateUser(String userId, String displayName, String email, String initialPassword)`（需要 `CREATE_ACCOUNT`）、`ResetUserPassword(UserId, String newPassword)`（需要 `RESET_ANY_USER_PASSWORD`）、`SetUserAppRoles(UserId, Set<AppRole>)`（需要 `EDIT_APPLICATION_SETTINGS`）。
- 密码策略：长度至少 12 位，否则返回 400；`userId` 匹配 `[A-Za-z0-9._-]{2,64}`，重复返回 409。
- REST：`GET /api/admin/users`、`POST /api/admin/users`、`PUT /api/admin/users/{userId}/password`、`PUT /api/admin/users/{userId}/app-roles`。
- 测试 `AdminUserManagementTest`：管理员可以执行全部操作；普通用户得到 403；为迁移用户设置密码后可以登录；不能移除最后一个 `SYSTEM_ADMIN` 的管理员角色（409）。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`app-security` —— 认证、授权、令牌与其它安全相关接口。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P3-25` 查看。

- 开工前加载的 skill：`springboot-security`、`security-review`、`springboot-tdd`、`java-coding-standards`
- 提交前调用的审查 agent：`security-reviewer`、`java-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=AdminUserManagementTest
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 用户自助注册
- 删除用户（P6，需要先确定其修订记录如何展示）
