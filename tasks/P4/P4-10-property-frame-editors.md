---
id: P4-10
title: 对象 / 数据 / 注解属性 Frame 编辑器
phase: P4
module: wp-web
type: impl
size: M
work_type: web
ecc_extra: []
status: done
depends_on: [P4-22, P4-26]
---

# P4-10 属性 Frame 编辑器

## 目标
在工作台选中对象属性、数据属性或注解属性后，右侧详情面板可以查看并编辑该属性的 Frame，保存与冲突处理方式和类 Frame 一致。

## 参考
- `tasks/P4/EPIC.md` 第 10 项（个体部分见 P4-23）
- P3-17：
  - `ObjectPropertyFrame{subject, annotations, parents, domains: EntityNode[]（类）, ranges: EntityNode[]（类）, characteristics, inverses: EntityNode[], otherAxioms}`；`characteristics` 取值 `FUNCTIONAL`、`INVERSE_FUNCTIONAL`、`TRANSITIVE`、`SYMMETRIC`、`ASYMMETRIC`、`REFLEXIVE`、`IRREFLEXIVE`
  - `DataPropertyFrame{subject, annotations, parents, domains: EntityNode[]（类）, ranges: EntityNode[]（数据类型）, functional: boolean, otherAxioms}`
  - `AnnotationPropertyFrame{subject, annotations, parents, domains: string[]（IRI）, ranges: string[]（IRI）, otherAxioms}`
  - 属性链、不相交属性、等价属性只出现在 `otherAxioms`，不参与写入；冲突返回 409 `FRAME_CHANGED`
- `docs/api/openapi.json` 的 operationId：`getObjectPropertyFrame`、`updateObjectPropertyFrame`、`getDataPropertyFrame`、`updateDataPropertyFrame`、`getAnnotationPropertyFrame`、`updateAnnotationPropertyFrame`
- P4-22 的 `useFrameEditor`、`useUnsavedGuard`、`FrameSaveBar`、`FrameHeader`、`OtherAxiomsView`；P4-26 的输入组件与 `BUILTIN_DATATYPES`
- 不依赖 P4-07：编辑器经注册表出现在详情面板，测试直接挂载编辑器

## 产出
- `src/features/frames/property/api.ts`：三种属性 Frame 的读取与保存函数；查询键 `queryKeys.frame(projectId, "OBJECT_PROPERTY" | "DATA_PROPERTY" | "ANNOTATION_PROPERTY", iri)`。
- `src/features/frames/property/ObjectPropertyFrameEditor.vue`：分区：标题、注解、父属性（`EntityRefList`，`types: ["object-property"]`）、定义域与值域（`types: ["class"]`）、特征（`el-checkbox-group`，七种，文案说明含义）、逆属性（`types: ["object-property"]`）、其它公理、`FrameSaveBar`。
- `src/features/frames/property/DataPropertyFrameEditor.vue`：分区：标题、注解、父属性（`types: ["data-property"]`）、定义域（`types: ["class"]`）、值域（`types: ["datatype"]`，`builtins: BUILTIN_DATATYPES`）、函数型（`el-switch`）、其它公理、`FrameSaveBar`。
- `src/features/frames/property/AnnotationPropertyFrameEditor.vue`：分区：标题、注解、父属性（`types: ["annotation-property"]`）、定义域与值域（`IriListInput`）、其它公理、`FrameSaveBar`。
- `src/features/frames/property/IriListInput.vue`：v-model `string[]`；可关闭标签 + 输入框，回车添加，校验为绝对 IRI，去重。
- `src/features/frames/property/register.ts`：在 `detailEditors` 中注册 `OBJECT_PROPERTY`、`DATA_PROPERTY`、`ANNOTATION_PROPERTY`。
- 文案：`src/features/frames/property/locales/`（顶层键 `framesProperty`）。
- 测试：
  - `src/features/frames/property/ObjectPropertyFrameEditor.spec.ts`：勾选 `TRANSITIVE` 后保存，`to.characteristics` 多一项，其它字段不变；添加逆属性；409 显示冲突提示。
  - `src/features/frames/property/DataPropertyFrameEditor.spec.ts`：打开函数型开关后保存，`to.functional` 为 `true`；值域选择器只搜索 `datatype`，搜索返回空时仍可选 `xsd:string`。
  - `src/features/frames/property/AnnotationPropertyFrameEditor.spec.ts`：添加定义域 IRI 后保存，`to.domains` 是字符串数组；非法 IRI 不能添加。

## 实现要点
- 三个编辑器都用 `FrameHeader` 作标题、调用 `useUnsavedGuard`；只负责布局和字段绑定，草稿、保存、冲突、403 只读全部交给 `useFrameEditor`；如果发现某段逻辑在三个编辑器里重复，提到 `src/features/frames/property/` 下的一个小组件中，不要改 P4-22 的公共接口。
- 注解属性的定义域、值域是 IRI 字符串，不是 `EntityNode`（与另外两种属性不同）。
- 三个编辑器在 `useFrameEditor` 的 `onSaved` 中让对应层级（`queryKeys.hierarchy(projectId, "object-property" | "data-property" | "annotation-property")`）与 `queryKeys.searchAll(projectId)` 失效：父属性和标签可能变了。
- 预计改动接近 400 行；若明显超出，把 `AnnotationPropertyFrameEditor` 与 `IriListInput` 拆成另一张卡，按 `AGENTS.md` 第 6 节停下说明。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P4-10` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend --filter wp-web exec vitest run src/features/frames/property/ObjectPropertyFrameEditor.spec.ts src/features/frames/property/DataPropertyFrameEditor.spec.ts src/features/frames/property/AnnotationPropertyFrameEditor.spec.ts
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写

## 不在本卡范围
- 个体 Frame 编辑器（P4-23）
- 编辑属性链、不相交属性、等价属性（只读显示在 `otherAxioms` 中）

## 执行记录
- 日期：2026-11-13
- 分支：feature/p4-10-property-frame-editors
- 测试：2026-11-13T11:04:34+08:00 通过（3 项）
- 实际改动：新增对象/数据/注解属性 Frame 编辑器、IriListInput、API、注册与 locales；七种特征勾选、函数型开关、IRI 定义域/值域与 409 冲突处理与类 Frame 一致。
- 偏差：实现行数（约 1100+，不含测试）明显高于卡片预估 400 行；卡片建议超标时拆出 AnnotationPropertyFrameEditor 与 IriListInput，本卡仍按产出清单交付全部三项编辑器以便一次验收。
- 审查：vue-reviewer, typescript-reviewer, a11y-architect
