---
id: P6-07
title: Web 端：成员页按用户 ID 或显示名称查找并添加成员
phase: P6
module: wp-web
type: impl
size: S
work_type: web
ecc_extra: []
status: todo
depends_on: [P6-02, P6-04]
---

# P6-07 成员页查找用户（Web 端）

## 目标
在项目设置的“成员”标签页添加成员时，可以输入用户 ID 或显示名称的一部分，从下拉列表中选择用户，不必记住准确的用户 ID。对应 M4 差距清单第 2 项的 Web 部分。

## 参考
- P6-02：`GET /api/projects/{id}/member-candidates?q=&limit=`，operationId `searchMemberCandidates`，返回 `[{userId, displayName}]`，`q` 为 1 ~ 64 个字符，`limit` 最大 20，需要 `EDIT_SHARING_SETTINGS`
- P4-24：`src/features/settings/members/MembersTab.vue`、`api.ts`、`locales/`、`MembersTab.spec.ts`
- P4-02：`src/shared/api/queryKeys.ts`

## 产出
- `src/shared/api/queryKeys.ts`：新增 `memberCandidates(id, q)`，值为 `[...queryKeys.members(id), "candidates", q]`。`members(id)` 失效时它也随之失效。
- `src/features/settings/members/api.ts`：新增 `useMemberCandidates(projectId, query)`。查询词去掉首尾空白后为空时不发请求；`limit` 固定为 10；输入停止 300 毫秒后才发请求（防抖），`staleTime` 30 秒。
- `src/features/settings/members/MembersTab.vue`：把“添加成员”的用户 ID 输入框换成 `el-autocomplete`。下拉项显示“显示名称（用户 ID）”，已经是成员的项标注“已是成员”并且不可选；选中后把用户 ID 填入输入框。仍然允许直接输入完整的用户 ID 后点“添加”（原有的 404“用户 ID 不存在”提示保留）。删除 P4-24 中“后端没有按名称搜索用户”的说明文字。
- 文案：`src/features/settings/members/locales/`（zh-CN 与 en-US 同步）。
- 测试 `MembersTab.spec.ts` 补充：
  - 输入“托”并等待防抖后，请求 `GET .../member-candidates?q=托&limit=10`，下拉显示返回的用户；
  - 已在成员列表中的用户显示“已是成员”且不可选；
  - 选中候选项后点击“添加”，发出 `PUT .../members/<userId>`；
  - 只输入空白时不发请求；查找接口失败时，下拉显示错误提示，但仍然可以手工输入用户 ID 添加。

## 实现要点
- 键盘可操作：上下键选择、回车选中、Esc 关闭。保留 `el-autocomplete` 默认的 `aria` 属性，不要换成不可访问的自制弹层。
- 查询键中带项目 ID，不同项目之间不共用结果。
- 依赖 P6-04 只是为了串行修改共享文件 `src/shared/api/queryKeys.ts`（P6-04 在其中加了 `myPermissions`），避免并行卡改同一个文件。本卡不使用 P6-04 的其它产出。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-07` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend --filter wp-web exec vitest run src/features/settings/members/MembersTab.spec.ts src/shared/api/queryKeys.spec.ts
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 按邮箱邀请、邀请链接
- 管理页（`/admin/users`）的用户搜索
- 后端接口（P6-02）
