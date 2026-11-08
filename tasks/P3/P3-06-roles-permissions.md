---
id: P3-06
title: 角色、动作与权限判断（BS-10）
phase: P3
module: wp-app/access
type: impl
size: M
work_type: app-security
ecc_extra: []
status: done
depends_on: [P3-04]
---

# P3-06 角色、动作与权限判断（BS-10）

## 目标
系统能回答“某用户能否在某项目上执行某动作”。

## 参考
- BS-10
- 旧定义：`../webprotege/webprotege-shared-core/src/main/java/edu/stanford/bmir/protege/web/shared/access/BuiltInAction.java`、`BuiltInRole.java`

## 产出
- `wprebuild.app.access.Action`：枚举，只包含 BS-10 表中出现的动作。
- `wprebuild.app.access.ProjectRole`：`CAN_VIEW`、`CAN_COMMENT`、`CAN_EDIT`、`CAN_MANAGE`，每个角色包含前一个角色的全部动作；`AppRole`：`PROJECT_CREATOR`、`SYSTEM_ADMIN`。
- `wprebuild.app.access.JdbcRoleAssignmentRepository`：`Optional<ProjectRole> find(ProjectId, UserId)`、`void assign(ProjectId, UserId, ProjectRole)`、`void remove(ProjectId, UserId)`、`List<RoleAssignment> list(ProjectId)`。
- `wprebuild.app.access.AccessService`：`boolean can(UserId, Action)`（应用级）、`boolean can(UserId, ProjectId, Action)`（项目级）、`void require(...)`（不满足时抛 `wprebuild.app.shared.PermissionDeniedException`）、`void grantCreator(ProjectId, UserId)`（授予 CAN_MANAGE）；`SYSTEM_ADMIN` 对所有项目视为 CAN_MANAGE。
- `wprebuild.app.shared` 中新增 `PermissionDeniedException`、`NotFoundException`、`ConflictException`。
- 测试 `BS10_AccessServiceTest`：表驱动，枚举 4 个项目角色 × 全部项目动作，期望值与 BS-10 表一致；系统管理员；未分配角色的用户无任何项目权限。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`app-security` —— 认证、授权、令牌与其它安全相关接口。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P3-06` 查看。

- 开工前加载的 skill：`springboot-security`、`security-review`、`springboot-tdd`、`java-coding-standards`
- 提交前调用的审查 agent：`security-reviewer`、`java-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=BS10_AccessServiceTest
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写

## 不在本卡范围
- 共享设置 API（P3-22）

## 执行记录
- 日期：2026-11-08
- 分支：feature/p3-06-roles-permissions
- 测试：2026-11-08T18:26:23+08:00 通过（3 项）
- 实际改动：新增 BS-10 的动作、四级累积项目角色和两个应用角色；JDBC 保存项目角色分配；AccessService 判断 can/require，创建者获得 CAN_MANAGE，系统管理员对所有项目视为 CAN_MANAGE。
- 偏差：三个公共异常共用包内 sealed 基类 CodedException。权限拒绝消息固定为 permission denied，用户和动作只写入日志。权限检查只查询 app_roles。无法识别的项目角色按无权限处理。
- 审查：security-reviewer, java-reviewer
