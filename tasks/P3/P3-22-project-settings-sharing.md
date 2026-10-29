---
id: P3-22
title: 项目设置与共享（角色分配）API
phase: P3
module: wp-app/project, ontology, access, web
type: impl
size: M
work_type: app-service
ecc_extra: [security]
status: todo
depends_on: [P3-14, P3-06]
---

# P3-22 项目设置与共享（角色分配）API

## 目标
管理员可以配置前缀、新实体规则、显示语言，并管理项目成员。

## 参考
- BS-06、BS-09、BS-10
- 旧实现：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/projectsettings/`、`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/sharing/`

## 产出
- `project_settings` 的三个键：`prefixes`（`Map<String,String>`）、`new-entity`（`NewEntitySettings`）、`display-names`（`List<DisplayNameSetting>`），用 Jackson 序列化。
- `project` 模块：`JdbcProjectSettingsRepository`；设置变化时发布 Spring 事件 `ProjectSettingsChanged(ProjectId, String key)`。
- `ontology` 模块：`ProjectSettingsProvider` 的数据库实现（替换 P3-03 中的默认实现，读取 `project` 模块的仓储）；监听 `ProjectSettingsChanged`，显示语言变化时让该项目的显示名称视图重建，并通知 `events` 模块发送 `resync`。
- 命令与 REST：`GET/PUT /api/projects/{id}/settings/prefixes`、`/settings/new-entity`、`/settings/display-names`（读取需要 `VIEW_PROJECT`，修改分别需要 `EDIT_PROJECT_PREFIXES`、`EDIT_NEW_ENTITY_SETTINGS`、`EDIT_PROJECT_SETTINGS`）。
- 共享相关的命令处理器放在 `project` 模块（它可以依赖 `bus` 和 `access`）。共享：`GET /api/projects/{id}/members`、`PUT /api/projects/{id}/members/{userId}`（请求体 `{role}`）、`DELETE /api/projects/{id}/members/{userId}`（需要 `EDIT_SHARING_SETTINGS`）；不能移除最后一个 CAN_MANAGE；变化后由 `access` 模块发布 Spring 事件 `RoleAssignmentsChanged(ProjectId)`。
- 测试 `ProjectSettingsTest`、`SharingTest`。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口；附加：`security`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P3-22` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`、`security-review`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`、`security-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='ProjectSettingsTest,SharingTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 用户邀请邮件
