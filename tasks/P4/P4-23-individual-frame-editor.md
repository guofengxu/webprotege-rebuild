---
id: P4-23
title: 个体 Frame 编辑器：类型、对象属性断言、数据属性断言、同一个体
phase: P4
module: wp-web
type: impl
size: M
work_type: web
ecc_extra: []
status: todo
depends_on: [P4-22, P4-26]
---

# P4-23 个体 Frame 编辑器

## 目标
在个体列表中选中一个个体后，右侧详情面板可以查看并编辑它的注解、类型、对象属性断言、数据属性断言和同一个体，保存与冲突处理方式和类 Frame 一致。本卡由 EPIC 第 10 项拆出（第 10 项的属性部分见 P4-10）。

## 参考
- `tasks/P4/EPIC.md` 第 10 项
- P3-18：`NamedIndividualFrame{subject, annotations, types: EntityNode[]（类）, objectPropertyAssertions: {property, individual}[], dataPropertyAssertions: {property, value: LiteralValue}[], sameIndividuals: EntityNode[], otherAxioms}`；三个及以上个体的 `SameIndividual` 只出现在 `otherAxioms`；不读取推理得到的类型；冲突返回 409 `FRAME_CHANGED`
- `docs/api/openapi.json` 的 operationId：`getNamedIndividualFrame`、`updateNamedIndividualFrame`
- P4-22 的 `useFrameEditor`、`useUnsavedGuard`、`FrameSaveBar`、`FrameHeader`、`OtherAxiomsView`；P4-26 的输入组件
- 不依赖 P4-08：编辑器经注册表出现在详情面板，测试直接挂载编辑器；个体列表的失效只用 P4-02 的 `queryKeys.individualsAll`

## 产出
- `src/features/frames/individual/api.ts`：读取与保存函数；查询键 `queryKeys.frame(projectId, "NAMED_INDIVIDUAL", iri)`。
- `src/features/frames/individual/NamedIndividualFrameEditor.vue`：调用 `useUnsavedGuard`；分区：标题（`FrameHeader`）、注解（`AnnotationsEditor`）、类型（`EntityRefList`，`types: ["class"]`）、对象属性断言、数据属性断言、同一个体（`EntityRefList`，`types: ["named-individual"]`，`excludeIris: [iri]`，不能选自己）、其它公理、`FrameSaveBar`（`invalid` 由两个断言编辑器的 `valid` 合并得到）。
- `src/features/frames/individual/ObjectAssertionsEditor.vue`：v-model `ObjectPropertyAssertion[]` 与 `v-model:valid`（P4-22 的校验约定）；每行：属性（`types: ["object-property"]`）+ 个体（`types: ["named-individual"]`）+ 删除。
- `src/features/frames/individual/DataAssertionsEditor.vue`：v-model `DataPropertyAssertion[]` 与 `v-model:valid`；每行：属性（`types: ["data-property"]`）+ `LiteralValueInput` + 删除。
- 断言的元素类型（`ObjectPropertyAssertion`、`DataPropertyAssertion`）用 `NamedIndividualFrame["objectPropertyAssertions"][number]` 这种写法从 `@wp/api-types` 推导，不手写。
- `src/features/frames/individual/register.ts`：注册 `detailEditors.NAMED_INDIVIDUAL = NamedIndividualFrameEditor`。
- 文案：`src/features/frames/individual/locales/`（顶层键 `framesIndividual`）。
- 测试：
  - `src/features/frames/individual/NamedIndividualFrameEditor.spec.ts`：加载后显示类型和两类断言；新增一条对象属性断言并保存，`to.objectPropertyAssertions` 多一项，`from` 是原 Frame；409 显示冲突提示；同一个体选择器排除当前个体。
  - `src/features/frames/individual/DataAssertionsEditor.spec.ts`：新增 `xsd:integer` 类型的断言得到 `{property, value: {type: "literal", lexical: "42", datatype: xsd:integer}}`；有未填属性的行时 `update:valid` 发出 `false`。

## 实现要点
- 保存成功后，个体的类型可能变化：除了 `useFrameEditor` 写回 Frame 缓存，还要在 `onSaved` 中让 `queryKeys.individualsAll(projectId)` 与 `queryKeys.searchAll(projectId)` 失效，使个体列表反映新的类型。
- 不在前端做任何推理或类型推断。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P4-23` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend --filter wp-web exec vitest run src/features/frames/individual/NamedIndividualFrameEditor.spec.ts src/features/frames/individual/DataAssertionsEditor.spec.ts
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 个体列表（P4-08）、新建 / 删除个体（P4-11）
- 负属性断言、`DifferentIndividuals` 等其它个体公理（只读显示在 `otherAxioms` 中）
