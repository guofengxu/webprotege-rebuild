---
id: P6-05
title: Web 端：没有编辑权限时，Frame 编辑器为只读
phase: P6
module: wp-web
type: impl
size: M
work_type: web
ecc_extra: []
status: todo
depends_on: [P6-04]
---

# P6-05 只读的 Frame 编辑器（Web 端）

## 目标
只能查看的成员打开实体详情时，看到的是只读界面：输入控件禁用，不显示保存栏，并说明原因；他们不会先编辑一通再在保存时收到 403。设置表单的只读模式见 P6-06。

## 参考
- P6-04：`src/features/permissions/useProjectPermissions.ts`（`permission(action)` 返回 `allowed` / `denied` / `pending`）
- P4-22：`src/features/frames/useFrameEditor.ts`、`FrameSaveBar.vue`、`useUnsavedGuard.ts`
- P4-26：`src/features/frames/AnnotationsEditor.vue`（已有 `disabled` 属性）、`EntityRefList.vue`、`EntityPicker.vue`、`LiteralValueInput.vue`、`inputs/`
- P4-09 / P4-10 / P4-23：`frames/class/ClassFrameEditor.vue`、`frames/class/PropertyValuesEditor.vue`；`frames/property/ObjectPropertyFrameEditor.vue`、`DataPropertyFrameEditor.vue`、`AnnotationPropertyFrameEditor.vue`、`IriListInput.vue`；`frames/individual/NamedIndividualFrameEditor.vue`、`DataAssertionsEditor.vue`、`ObjectAssertionsEditor.vue`
- 后端：五种 Frame 的更新都要求 `EDIT_ONTOLOGY`

## 产出
- `src/features/frames/useFrameEditor.ts`：返回值新增 `readOnly: ComputedRef<boolean>`。`EDIT_ONTOLOGY` 为 `denied` 或 `pending` 时为 `true`；此时 `save()` 直接返回，不发请求。
- `src/features/frames/FrameSaveBar.vue`：`readOnly` 时不显示“保存 / 放弃”；权限为 `denied` 时显示“只读：你在本项目中没有编辑权限”，`pending` 时不显示提示。
- 五个 Frame 编辑器把 `readOnly` 传给输入组件的 `disabled`。`EntityRefList`、`EntityPicker`、`LiteralValueInput`、`PropertyValuesEditor`、`DataAssertionsEditor`、`ObjectAssertionsEditor`、`IriListInput` 中缺少 `disabled` 属性的，补上并传到内部的 Element Plus 控件（增删按钮也要禁用）。
- `useUnsavedGuard`：只读时不提示“未保存的修改”。
- 文案：`src/features/frames/locales/`（zh-CN 与 en-US 同步）。
- 测试：
  - `FrameSaveBar.spec.ts`：`denied` 时不渲染保存按钮并显示只读提示；`pending` 时两者都不显示；
  - `class/ClassFrameEditor.spec.ts`、`property/ObjectPropertyFrameEditor.spec.ts`、`individual/NamedIndividualFrameEditor.spec.ts`：权限 `actions` 不含 `EDIT_ONTOLOGY` 时，全部输入控件禁用，且不会发出 `PUT` 请求；含 `EDIT_ONTOLOGY` 时行为不变；
  - `EntityRefList.spec.ts`、`EntityPicker.spec.ts`：`disabled` 时不能增删、不能打开选择弹层；
  - `useFrameEditor.spec.ts`：`readOnly` 时 `save()` 不发请求；
  - `useUnsavedGuard.spec.ts`：只读时不拦截离开。

## 实现要点
- 只读判断只读取 P6-04 的 `permission()`，不新增权限请求。
- 只读不等于隐藏：Frame 的内容仍然完整显示，只是不能修改。
- 后端 403 的处理保留不动。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-05` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend --filter wp-web exec vitest run src/features/frames/FrameSaveBar.spec.ts src/features/frames/useFrameEditor.spec.ts src/features/frames/useUnsavedGuard.spec.ts src/features/frames/EntityRefList.spec.ts src/features/frames/EntityPicker.spec.ts src/features/frames/class/ClassFrameEditor.spec.ts src/features/frames/property/ObjectPropertyFrameEditor.spec.ts src/features/frames/individual/NamedIndividualFrameEditor.spec.ts
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 实体新建、删除与回滚按钮（P6-04）
- 项目设置表单（P6-06）、成员标签页（P4-24、P6-07）
- “只能改注解”这类细分的编辑权限：现有角色中没有只拥有 `EDIT_ONTOLOGY_ANNOTATIONS` 的角色（BS-10）
