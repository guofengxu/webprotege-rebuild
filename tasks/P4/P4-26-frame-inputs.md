---
id: P4-26
title: Frame 输入组件：实体选择器（含内置实体）、实体引用列表、字面量输入、注解编辑器
phase: P4
module: wp-web
type: impl
size: M
work_type: web
ecc_extra: []
status: todo
depends_on: [P4-22]
---

# P4-26 Frame 输入组件

## 目标
Frame 编辑器和设置页共用的表单输入组件：通过搜索选择实体（新项目里搜不到的内置数据类型与注解属性也能选），以标签列表维护实体引用，输入带数据类型和语言的字面量，编辑多语言注解。本卡由 P4-22 拆出（P4-22 负责编辑机制）。

## 参考
- `tasks/P4/EPIC.md` 第 9、10 项
- P3-19：`searchEntities` 的 `types` 取值 `class`、`object-property`、`data-property`、`annotation-property`、`named-individual`、`datatype`，多个取值用逗号拼接成一个查询参数；`limit` 默认 20
- `backend/wp-app/src/main/java/wprebuild/app/search/ProjectSearchIndex.java`：只索引本体签名中的实体，新项目里 `xsd:*` 数据类型和 `rdfs:label` 等内置注解属性多半搜不到
- `backend/wp-app/src/main/java/wprebuild/app/ontology/frame/ClassFrameAxioms.java`：`DATA_SOME` 的填充值必须是 `DATATYPE`、注解的属性必须是 `ANNOTATION_PROPERTY`，`entityType: "IRI"` 的原始 IRI 在这些位置会得到 400
- P4-22 的 `frameModel`（`rawIriNode`、`iriValue`、`literalValue`）
- P4-05 的 `SearchType`、`linkTo`（不可跳转的实体类型返回 `null`）；P4-02 的 `queryKeys.search`
- `docs/api/openapi.json` 的 operationId：`searchEntities`

## 产出
- `src/features/frames/builtins.ts`：`BUILTIN_DATATYPES: EntityNode[]`（`xsd:string`、`rdf:langString`、`xsd:boolean`、`xsd:integer`、`xsd:decimal`、`xsd:double`、`xsd:dateTime`、`xsd:anyURI`，`entityType: "DATATYPE"`，完整 IRI）与 `BUILTIN_ANNOTATION_PROPERTIES: EntityNode[]`（`rdfs:label`、`rdfs:comment`、`rdfs:seeAlso`、`rdfs:isDefinedBy`、`owl:deprecated`、`skos:prefLabel`、`skos:altLabel`、`skos:definition`，`entityType: "ANNOTATION_PROPERTY"`）；`displayName` 用带前缀的短名（例如 `rdfs:label`）。
- `src/features/frames/EntityPicker.vue`：props `projectId`、`types: SearchType[]`、`modelValue: EntityNode | null`、`builtins?: EntityNode[]`、`allowRawIri?: boolean`、`excludeIris?: string[]`、`ariaLabel: string`；`el-select`（`filterable`、`remote`）调用 `searchEntities`（`types` 逗号拼接、`limit: 20`，输入防抖 250ms，键 `queryKeys.search(projectId, q, types, 20)`）；`builtins` 中按显示名称或 IRI 包含输入文字的项排在搜索结果之前，按 `iri` 去重；`excludeIris` 中的实体不出现在选项中；选项显示显示名称和实体类型标签；`allowRawIri` 为真且输入是绝对 IRI 时多出一项“使用 IRI：…”，选中后得到 `rawIriNode`。`allowRawIri` 只用在后端接受任意 IRI 的位置（注解的 IRI 值、设置中的 IRI 字段）。
- `src/features/frames/EntityRefList.vue`：v-model `EntityNode[]`；props 透传 `projectId`、`types`、`builtins`、`allowRawIri`、`excludeIris`、`ariaLabel` 给内部的 `EntityPicker`；每项是可关闭的 `el-tag`（已弃用的用删除线）；`linkTo` 返回非 `null` 时点击标签跳转到该实体，返回 `null`（`DATATYPE`、`IRI`、`ONTOLOGY`）时只显示文字；末尾一个 `EntityPicker` 用于添加；按 `iri` 去重。
- `src/features/frames/LiteralValueInput.vue`：v-model `LiteralValue`；词法值输入框（多行）、数据类型下拉（选项取 `BUILTIN_DATATYPES`，存完整 IRI）、语言标签输入（只在 `rdf:langString` 时可编辑；填了语言就自动切到 `rdf:langString`）。
- `src/features/frames/AnnotationsEditor.vue`：v-model `AnnotationValue[]`；每行：注解属性（`EntityPicker`，`types: ["annotation-property"]`，`builtins: BUILTIN_ANNOTATION_PROPERTIES`）、值类型切换（字面量 / IRI）、值（`LiteralValueInput` 或 `allowRawIri` 的 `EntityPicker`）、删除按钮；“添加标签”快捷按钮新增一行 `rdfs:label`。按注解属性分组显示，同一属性的多语言值排在一起。
- 文案：`src/features/frames/inputs/locales/`（顶层键 `framesInputs`）。
- 测试：
  - `src/features/frames/EntityPicker.spec.ts`：输入后防抖调用 `searchEntities`，`types` 以逗号拼接；`builtins` 中匹配的项排在前面且与搜索结果去重；`excludeIris` 中的实体不出现在选项中；`allowRawIri` 时可选“使用 IRI”，得到 `entityType: "IRI"` 的节点；不允许时没有这一项。
  - `src/features/frames/EntityRefList.spec.ts`：添加重复 IRI 不产生重复项；`DATATYPE` 标签不可点击跳转。
  - `src/features/frames/AnnotationsEditor.spec.ts`：新增中文标签得到 `{property: rdfs:label, value: {type: "literal", lexical, datatype: rdf:langString, lang: "zh"}}`；把值切换为 IRI 得到 `{type: "iri", entity}`；删除一行后 v-model 少一项；新项目（搜索返回空）中也能选到 `rdfs:comment`。

## 实现要点
- `value.type` 的字面量（`"iri"` / `"literal"`）以 P4-18 修正后的生成类型为准，不强转、不手写。
- 所有输入组件都要有可读的标签（`ariaLabel` 或关联的 `<label>`），能用键盘完成添加和删除。
- 本卡不做 Frame 编辑器本身，只做输入组件；组件不依赖具体 Frame 类型。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P4-26` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend --filter wp-web exec vitest run src/features/frames/EntityPicker.spec.ts src/features/frames/EntityRefList.spec.ts src/features/frames/AnnotationsEditor.spec.ts
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 内置数据类型与注解属性在搜索无结果时仍可选择
- [ ] 执行记录已填写

## 不在本卡范围
- 编辑机制（`useFrameEditor`、`FrameSaveBar`，P4-22）
- 具体的 Frame 编辑器（P4-09、P4-10、P4-23）
- 顶部全局搜索框（P4-12）；本卡的 `EntityPicker` 只是表单内的选择器
