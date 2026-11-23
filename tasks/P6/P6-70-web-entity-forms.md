---
id: P6-70
title: Web 端：实体表单面板（TEXT/NUMBER 可编辑，其它控件只读占位）
phase: P6
module: wp-web
type: impl
size: M
work_type: web
ecc_extra: []
status: todo
depends_on: [P6-73]
---

# P6-70 实体表单 Web 面板

## 目标
工作区选中实体后可打开表单面板：展示适用表单（多 tab）；TEXT/NUMBER 在具备 `EDIT_ONTOLOGY` 时可编辑并保存；其它控件只读占位；`STALE_HEAD` 与 `FORM_DATA_CHANGED` 时提示重新加载。

## 参考
- BS-17、BS-18；P6-69 / P6-73 API 与 `@wp/api-types`
- 旧出处：`EntityFormStackPresenter`、`FormPortletPresenter`
- 现有：`useFrameEditor`、`LiteralValueInput`、`useProjectPermissions`、P6-05 只读模式

## 产出
- `frontend/wp-web` 功能目录（如 `features/forms/`）：拉取实体表单、tab、字段渲染、Edit/Apply/Cancel、保存时带 `expectedHead`。
- TEXT/NUMBER 编辑器复用或薄封装现有字面量/数字输入；无权限时整表只读。
- 未实现控件显示简短只读占位（控件类型名），不假装可编辑。
- i18n 键（中英）；单元测试：只读门禁、保存带 revision、两种 409 处理。
- 入口：实体详情区或工具栏可发现的入口（与现有工作区注册方式一致）。

## 实现要点
- 类型一律来自 `@wp/api-types`。
- 不实现表单设计器、不调用 P6-67 管理 REST（无 `EDIT_FORMS` 的编辑者只走实体表单 API）。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-70` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend --filter wp-web exec vitest run src/features/forms
pnpm -C frontend --filter wp-web run typecheck
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 表单设计器、描述符管理页、迁移、后端选择器扩展
