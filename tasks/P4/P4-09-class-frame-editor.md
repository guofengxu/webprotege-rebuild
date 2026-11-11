---
id: P4-09
title: 类 Frame 编辑器：多语言注解、父类、三种属性值、只读的其它公理、冲突处理
phase: P4
module: wp-web
type: impl
size: M
work_type: web
ecc_extra: []
status: todo
depends_on: [P4-22, P4-26]
---

# P4-09 类 Frame 编辑器

## 目标
在工作台选中一个类后，右侧详情面板可以查看并编辑它的注解（多语言）、父类和三种属性值；其它公理以只读文本显示；保存时带 `from`，收到 409 `FRAME_CHANGED` 时提示并允许刷新。

## 参考
- `tasks/P4/EPIC.md` 第 9 项
- P3-15（类 Frame 读取）：`ClassFrame{subject, annotations, parents, propertyValues, otherAxioms}`；`PropertyValue{property, kind, filler}`，`kind` 取值：`SOME`（`ObjectSomeValuesFrom(p 类)`，filler 为类的 `IriValue`）、`HAS_VALUE`（`ObjectHasValue` 的 filler 为个体的 `IriValue`，`DataHasValue` 的 filler 为 `LiteralValue`）、`DATA_SOME`（`DataSomeValuesFrom(p 数据类型)`，filler 为数据类型的 `IriValue`）；其它公理在 `otherAxioms` 中，是 Manchester 语法文本
- P3-16（类 Frame 更新）：`otherAxioms` 不参与写入，更新后仍然保留；冲突返回 409 `FRAME_CHANGED`
- `backend/wp-app/src/main/java/wprebuild/app/ontology/frame/ClassFrameAxioms.java`：`DATA_SOME` 的 filler 必须是 `entityType: "DATATYPE"`，原始 IRI 会被拒绝
- `docs/api/openapi.json` 的 operationId：`getClassFrame`、`updateClassFrame`
- P4-22 的 `useFrameEditor`、`useUnsavedGuard`、`FrameSaveBar`、`FrameHeader`、`OtherAxiomsView` 与校验约定；P4-26 的 `AnnotationsEditor`、`EntityRefList`、`EntityPicker`、`LiteralValueInput`、`BUILTIN_DATATYPES`
- P4-05 的注册表（`detailEditors`，由 `EntityDetailPanel` 以 `:key="iri"` 渲染）

## 产出
- `src/features/frames/class/api.ts`：`loadClassFrame(client, projectId, iri)`、`saveClassFrame(client, projectId, body)`；查询键 `queryKeys.frame(projectId, "CLASS", iri)`。
- `src/features/frames/class/ClassFrameEditor.vue`：props `projectId`、`iri`；用 `useFrameEditor` 与 `useUnsavedGuard` 组织以下分区（`el-collapse`，默认全部展开）：
  - 标题：`FrameHeader`；
  - 注解：`AnnotationsEditor`；
  - 父类：`EntityRefList`（`types: ["class"]`）；
  - 属性值：`PropertyValuesEditor`；
  - 其它公理：`OtherAxiomsView`；
  - 底部 `FrameSaveBar`（`invalid` 取自 `PropertyValuesEditor` 的 `valid`）；保存成功后用 `ElMessage` 提示修订号；`onSaved` 中让 `queryKeys.hierarchy(projectId, "class")` 与 `queryKeys.searchAll(projectId)` 失效（父类和标签可能变了，树和搜索立即反映，不必等 P4-14 的事件）；`readOnly` 时所有输入禁用。
- `src/features/frames/class/PropertyValuesEditor.vue`：v-model `PropertyValue[]` 与 `v-model:valid`；每行：
  - `kind` 下拉（三种，文案分别说明含义）；
  - 属性：`SOME` 只能选对象属性，`DATA_SOME` 只能选数据属性，`HAS_VALUE` 两种都可以；
  - 值：`SOME` → 类（`EntityPicker`，`types: ["class"]`）；`HAS_VALUE` + 对象属性 → 个体（`types: ["named-individual"]`）；`HAS_VALUE` + 数据属性 → `LiteralValueInput`；`DATA_SOME` → 数据类型（`types: ["datatype"]`，`builtins: BUILTIN_DATATYPES`，不允许原始 IRI）；
  - 改变 `kind` 或属性后，与新组合不兼容的值被清空；有未填完的行时 `valid` 为 `false`（P4-22 的校验约定），保存按钮因此禁用并提示。
- `src/features/frames/class/register.ts`：注册 `detailEditors.CLASS = ClassFrameEditor`。
- 文案：`src/features/frames/class/locales/`（顶层键 `framesClass`）。
- 测试：
  - `src/features/frames/class/ClassFrameEditor.spec.ts`：加载后各分区显示 `getClassFrame` 返回的内容；修改中文标签后保存，`updateClassFrame` 的请求体 `from` 等于原 Frame、`to` 只有该标签不同，`otherAxioms` 原样保留；返回 409 `FRAME_CHANGED` 时显示冲突提示，点击“刷新”重新请求 `getClassFrame`；保存成功后类层级查询被失效。
  - `src/features/frames/class/PropertyValuesEditor.spec.ts`：三种 `kind` 分别得到正确形状的 `PropertyValue`（`filler.type` 为 `iri` 或 `literal`）；`DATA_SOME` 在搜索返回空时仍可选 `xsd:integer`，filler 的 `entity.entityType` 为 `DATATYPE`；`HAS_VALUE` 从对象属性换成数据属性时值被清空、输入控件变为字面量；有未填完的行时 `update:valid` 发出 `false`，填完后发出 `true`。

## 实现要点
- 属性值的合法组合只有上面列出的几种；前端不去推断其它限制类型，读到的其它限制都在 `otherAxioms` 中。
- 选择属性时，`HAS_VALUE` 需要知道所选属性是对象属性还是数据属性：用 `EntityNode.entityType`（`OBJECT_PROPERTY` / `DATA_PROPERTY`）判断。
- 切换选中实体时由 P4-05 的 `EntityDetailPanel` 按 `:key="iri"` 重新创建编辑器，不复用上一个实体的草稿；草稿已修改时由 `useUnsavedGuard` 先确认。
- 无编辑权限的用户也能看到编辑控件（后端没有“我的权限”接口，见 P4-17 的差距清单）；以保存时的 403 为准，之后编辑器变为只读。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P4-09` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend --filter wp-web exec vitest run src/features/frames/class/ClassFrameEditor.spec.ts src/features/frames/class/PropertyValuesEditor.spec.ts
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 保存请求带 `from`，409 `FRAME_CHANGED` 时有提示并可刷新
- [ ] 执行记录已填写

## 不在本卡范围
- 属性与个体的 Frame 编辑器（P4-10、P4-23）
- 编辑 `otherAxioms`、Manchester 语法输入
- 实体历史标签页（P4-13）
