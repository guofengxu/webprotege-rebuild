---
id: P4-27
title: 项目设置：新实体的 IRI 生成规则与显示语言（显示名称设置）
phase: P4
module: wp-web
type: impl
size: M
work_type: web
ecc_extra: []
status: done
depends_on: [P4-15, P4-26]
---

# P4-27 新实体设置与显示语言

## 目标
有相应权限的用户可以在项目设置页的“新实体”“显示语言”两个标签页修改新实体的 IRI 生成规则与显示名称设置；修改显示语言后，所有打开该项目的界面自动按新设置刷新显示名称。本卡由 EPIC 第 15 项拆出（页面外壳、基本信息与前缀见 P4-15）。

## 参考
- `tasks/P4/EPIC.md` 第 15 项
- BS-06（新实体 IRI：UUID、用户输入名称、OBO 三种方式）、BS-09（显示名称）
- P3-22：
  - `new-entity`：`{ iriPrefix, kit: { type: "UUID" } | { type: "SUPPLIED_NAME", whiteSpace: "CAMEL_CASE" | "UNDERSCORES" } | { type: "OBO", totalDigits, userRanges: [{ userId, start, end }] }, labelProperty, defaultLang, targetOntology }`，修改需要 `EDIT_NEW_ENTITY_SETTINGS`；
  - `display-names`：`[{ annotationProperty, lang }]`（有序，排在前面的优先），修改需要 `EDIT_PROJECT_SETTINGS`；修改后后端重建显示名称视图与搜索索引，并向客户端发送 `resync`
- `backend/wp-app/src/main/java/wprebuild/app/project/ProjectSettingsDocuments.java`：`display-names` 未设置时读到 `[]`；`backend/wp-kernel/src/main/java/wprebuild/kernel/shortform/DisplayNameView.java`：列表为空时使用内置默认 `skos:prefLabel`、`rdfs:label`、`dc:title`、`dcterms:title`（均为无语言），非空时**整体替换**默认值；语言按精确值匹配
- `backend/wp-app/src/test/java/wprebuild/app/web/BackendAcceptanceTest.java` 的 `displayNames()`：依次写入 `rdfs:label` 的 `""`、`zh`、`en` 三项（P4-17 照此操作）
- P4-15 的 `useProjectSetting` / `useSaveProjectSetting`、`settingsTabs` 注册表与 `update:dirty` 约定；P4-26 的 `EntityPicker`、`BUILTIN_ANNOTATION_PROPERTIES`；P4-22 的 `rawIriNode`
- `docs/api/openapi.json` 的 operationId：`getNewEntitySettings`、`putNewEntitySettings`、`getDisplayNameSettings`、`putDisplayNameSettings`

## 产出
- `src/features/settings/naming/NewEntitySettingsForm.vue` + `naming/newEntity.tab.ts`（`key: "new-entity"`、`order: 30`）：`iriPrefix`（绝对 IRI）、生成方式单选（UUID / 用户输入名称 / OBO）；选“用户输入名称”时显示空白处理（`CAMEL_CASE` / `UNDERSCORES`）；选 OBO 时显示位数（`totalDigits`）和按用户分配的编号区间表格（`userId`、`start`、`end`，校验 `start <= end` 且区间不重叠）；标签属性（`labelProperty`，`EntityPicker`，`types: ["annotation-property"]`、`builtins: BUILTIN_ANNOTATION_PROPERTIES`、`allowRawIri: true`，保存其 IRI；读到的 IRI 先用 `rawIriNode` 显示）、默认语言、目标本体（`targetOntology`，原样显示读取到的值，可编辑为文本）。
- `src/features/settings/naming/DisplayNameSettingsForm.vue` + `naming/displayNames.tab.ts`（`key: "display-names"`、`order: 40`）：有序列表，每项“注解属性 + 语言标签（可空，表示无语言）”，支持上移 / 下移 / 删除 / 新增；注解属性用 `EntityPicker`（`types: ["annotation-property"]`、`builtins: BUILTIN_ANNOTATION_PROPERTIES`、`allowRawIri: true`）；说明“排在前面的优先，语言按精确值匹配”；读到空列表时提示“当前使用内置默认（skos:prefLabel、rdfs:label、dc:title、dcterms:title，均为无语言）；一旦保存，列表会整体替换默认值”，并提供“以默认值开始编辑”按钮把这四项填进列表；保存后提示“所有打开该项目的页面会自动刷新显示名称”。
- 两个表单都通过 `update:dirty` 报告未保存修改；保存返回 403 时整体变为只读。
- 文案：`src/features/settings/naming/locales/`（顶层键 `settingsNaming`）。
- 测试：
  - `src/features/settings/naming/NewEntitySettingsForm.spec.ts`：从 UUID 切到 OBO 后请求体的 `kit` 为 `{type: "OBO", totalDigits, userRanges}`；区间重叠时不能保存；切回 UUID 后 `kit` 只剩 `type`；搜索返回空时标签属性仍可选 `rdfs:label`。
  - `src/features/settings/naming/DisplayNameSettingsForm.spec.ts`：读到 `[]` 时显示内置默认的提示，“以默认值开始编辑”后列表为四项；上移后保存的数组顺序改变；新增 `rdfs:label` 的 `""`、`zh`、`en` 三项后请求体为对应的三个 `{annotationProperty, lang}`；修改后 emit `update:dirty(true)`。

## 实现要点
- 显示语言修改后的界面刷新由后端的 `resync` 事件驱动（P4-14），本卡不需要手动失效层级等查询。
- `targetOntology` 是迁移包中的本体键（`OntologyKey`），MVP 不提供下拉选择。
- 本卡不修改 `ProjectSettingsPage.vue`，标签页通过 `*.tab.ts` 注册。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P4-27` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend --filter wp-web exec vitest run src/features/settings/naming/NewEntitySettingsForm.spec.ts src/features/settings/naming/DisplayNameSettingsForm.spec.ts
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 显示名称设置为空时界面说明内置默认值
- [x] 执行记录已填写

## 不在本卡范围
- 设置页外壳、基本信息、前缀（P4-15）
- 成员管理（P4-24）
- OBO 区间按用户名搜索用户（后端没有用户搜索接口，只能输入用户 ID）

## 执行记录
- 日期：2026-11-13
- 分支：feature/p4-27-entity-naming-settings
- 测试：2026-11-13T13:31:47+08:00 通过（3 项）
- 实际改动：在项目设置中注册新实体与显示语言两个标签页：支持 UUID/用户输入名/OBO 的 IRI 生成规则编辑，以及有序显示名称注解属性列表（空列表提示内置默认并支持以默认值开始编辑）；403 时只读，文案走 settingsNaming。审查已处理脏状态序列化、OBO 校验、行级 ariaLabel、fieldset、按钮命中区。
- 偏差：实现体量约 1169 行（两张大表单 SFC + locales），超过 M 卡预期 400 行；产出与卡片一致，未扩大范围。标签属性 IRI 优先匹配 BUILTIN_ANNOTATION_PROPERTIES 再退回 rawIriNode，便于显示短名。
- 审查：vue-reviewer, typescript-reviewer, a11y-architect
