---
id: P4-24
title: 项目成员管理：列出成员、添加成员、修改角色、移除成员
phase: P4
module: wp-web
type: impl
size: M
work_type: web
ecc_extra: []
status: done
depends_on: [P4-15]
---

# P4-24 成员管理（共享）

## 目标
有共享权限的用户可以在项目设置页的“成员”标签页查看成员、按用户 ID 添加成员并分配角色、修改角色、移除成员；不能移除最后一个管理者时给出明确提示。本卡由 EPIC 第 15 项拆出（设置部分见 P4-15）。

## 参考
- `tasks/P4/EPIC.md` 第 15 项
- BS-10（角色与权限）；角色取值 `CAN_VIEW`、`CAN_COMMENT`、`CAN_EDIT`、`CAN_MANAGE`
- P3-22：`GET /api/projects/{id}/members` 返回 `MemberResponse[]`（`{userId, role}`）；`PUT /api/projects/{id}/members/{userId}`（`{role}`）新增或修改；`DELETE .../members/{userId}`；三者都需要 `EDIT_SHARING_SETTINGS`（执行记录：查看成员也需要该权限）；不能移除最后一个 `CAN_MANAGE`（409 `LAST_MANAGER`）；变化后后端发布 `PermissionsChanged` 事件
- `docs/api/openapi.json` 的 operationId：`listProjectMembers`、`putProjectMember`、`deleteProjectMember`
- P4-15 的设置页标签注册表（`settingsTabs`、`*.tab.ts`、`update:dirty` 约定）；P4-25 的 `useCurrentUser`
- `backend/wp-app/src/main/java/wprebuild/app/access/AccessService.java`：`putProjectMember` 的用户 ID 不存在时返回 404

## 产出
- `src/features/settings/members/api.ts`：`useMembers(projectId)`（键 `queryKeys.members(projectId)`）、`usePutMember(projectId)`、`useDeleteMember(projectId)`（成功后让 `members` 失效）。
- `src/features/settings/members/MembersTab.vue`：
  - 表格：用户 ID、角色（行内 `el-select`，修改后立即调用 `putProjectMember`，失败时恢复原值）、操作（“移除”，`ElMessageBox.confirm`）；
  - 顶部“添加成员”：用户 ID 输入框 + 角色下拉（默认 `CAN_VIEW`）+ 按钮；用户 ID 已在列表中时提示改用行内修改；
  - 403 时显示“需要共享权限才能查看成员”，不显示表格；409 `LAST_MANAGER` 显示“项目至少需要一位管理者”；添加成员返回 404（`access/AccessService.java`：用户不存在）时显示“用户 ID 不存在”；当前用户（P4-25 的 `useCurrentUser`）修改或移除自己的管理权限前额外确认。
- `src/features/settings/members/members.tab.ts`：用 P4-15 的设置页标签注册表注册“成员”标签页（`key: "members"`、`order: 50`，即 `tab=members`），不修改 `ProjectSettingsPage.vue`；`MembersTab` 的行内修改即时提交，始终 emit `update:dirty(false)`。
- 文案：`src/features/settings/members/locales/`（顶层键 `members`，含四种角色的名称与说明）。
- 测试：
  - `src/features/settings/members/MembersTab.spec.ts`：渲染成员列表；添加成员发送 `PUT .../members/<userId>`，请求体 `{role}`；行内改角色失败（409 `LAST_MANAGER`）时下拉恢复原值并显示提示；移除需要确认；403 时显示无权限提示而不是表格；添加不存在的用户 ID（404）时显示“用户 ID 不存在”。

## 实现要点
- 后端没有“按名称搜索用户”的接口，添加成员只能输入用户 ID；在输入框旁说明这一点。
- 角色变化后的权限刷新由后端的 `PermissionsChanged` 事件驱动（P4-14），本卡只让 `members` 查询失效。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P4-24` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend --filter wp-web exec vitest run src/features/settings/members/MembersTab.spec.ts
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写

## 不在本卡范围
- 用户搜索、邀请链接、按邮箱邀请（后端不提供）
- 系统级的用户管理（P4-21）

## 执行记录
- 日期：2026-11-13
- 分支：feature/p4-24-project-members
- 测试：2026-11-13T08:29:25+08:00 通过（3 项）
- 实际改动：在项目设置页注册成员标签页：列表/按用户 ID 添加/行内改角色/移除；处理 403、404、LAST_MANAGER 与自管确认；始终 update:dirty(false)。
- 偏差：无
- 审查：vue-reviewer, typescript-reviewer, a11y-architect
