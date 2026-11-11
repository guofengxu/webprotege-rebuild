---
id: P4-22
title: Frame 编辑机制：useFrameEditor（from / to 与 409 冲突）、保存栏、校验约定、标题与其它公理分区
phase: P4
module: wp-web
type: impl
size: M
work_type: web
ecc_extra: []
status: todo
depends_on: [P4-05, P4-18]
---

# P4-22 Frame 编辑机制

## 目标
所有 Frame 编辑器（类、三种属性、个体）共用一套编辑机制：编辑草稿、带 `from` 提交、收到 409 `FRAME_CHANGED` 时提示并允许刷新、有未填完的行时禁止保存；四种编辑器都有的“标题”和“其它公理”分区也在这里实现。本卡由 EPIC 第 9、10 项拆出，避免各编辑器卡各写一份；表单输入组件（实体选择器、注解编辑器等）见 P4-26。

## 参考
- `tasks/P4/EPIC.md` 第 9、10 项（本卡与 P4-26 是它们的共同前置）
- P3-15、P3-16：`AnnotationValue(property, value, lang)`；`ValueNode` 用 `type` 区分，取值 `iri`（`IriValue{entity: EntityNode}`）与 `literal`（`LiteralValue{lexical, datatype, lang}`）；更新请求 `{from, to}`，当前 Frame 不等于 `from` 时返回 409 `FRAME_CHANGED`；成功返回更新后的 Frame 和修订号；`otherAxioms` 是 Manchester 语法文本，不参与写入
- 后端约束（`backend/wp-app/src/main/java/wprebuild/app/ontology/dto/EntityNode.java`、`ontology/frame/FrameRendering.java`）：提交的每个 `EntityNode` 的 `iri`、`entityType`、`displayName` 都不能为空；不是实体的 IRI 值，后端读出时 `entityType` 为 `"IRI"`、`displayName` 为本地名，前端手工输入 IRI 时按同样方式构造
- P4-18：Frame 的读取 / 更新响应在契约中补齐类型
- 本卡不直接调用接口；`useFrameEditor` 由各编辑器传入 `get*Frame` / `update*Frame` 的调用

## 产出
- `src/features/frames/frameModel.ts`：从 `@wp/api-types` 取 `ClassFrame`、`ObjectPropertyFrame`、`DataPropertyFrame`、`AnnotationPropertyFrame`、`NamedIndividualFrame`、`AnnotationValue`、`PropertyValue`、`IriValue`、`LiteralValue`、`EntityNode` 的类型别名；辅助函数 `isIriValue(v)`、`isLiteralValue(v)`（按 `type` 判断）、`iriValue(entity)`、`literalValue(lexical, datatype, lang?)`、`rawIriNode(iri)`（构造 `entityType: "IRI"` 的 `EntityNode`）、`cloneFrame(frame)`、`framesEqual(a, b)`。
- `src/features/frames/useFrameEditor.ts`：
  ```ts
  export function useFrameEditor<F>(options: {
    queryKey: MaybeRefOrGetter<QueryKey>;
    load: () => Promise<F>;
    save: (body: { from: F; to: F }) => Promise<{ frame: F; revision: number }>;
    onSaved?: (result: { frame: F; revision: number }) => void | Promise<void>; // 编辑器在这里让受影响的列表失效
  }): {
    server: Readonly<Ref<F | undefined>>;
    draft: Ref<F | undefined>;
    isDirty: ComputedRef<boolean>;
    isSaving: Ref<boolean>;
    conflict: Ref<boolean>;        // 收到 409 FRAME_CHANGED
    serverChanged: Ref<boolean>;   // 编辑期间服务端数据被刷新（例如 P4-14 的事件）
    save(): Promise<void>;
    reset(): void;
    reloadFromServer(): Promise<void>;
  }
  ```
  - 内部保存 `base`：当前草稿所基于的那份**服务端原始对象**（未经 `normalize*Frame`）。草稿不脏时，服务端数据变化直接覆盖草稿，并把 `base` 一起换成新值；草稿已脏时保留草稿和 `base`，只把 `serverChanged` 置为真。
  - `save` 发送 `{from: base, to: 草稿}`：后端用 `current.equals(from)` 比较整条记录（`AbstractFrameUpdateGenerator`），列表顺序、`EntityNode` 的 `displayName` / `hasChildren` / `deprecated` 都参与比较，所以 `from` 必须是原样的服务端对象，不能是规范化或克隆后改过的对象。成功后用返回的 Frame 写回查询缓存（`setQueryData`），重置草稿与 `base`，再调用 `onSaved`；409 `FRAME_CHANGED` 时 `conflict = true`，不丢草稿；403 时保留草稿、`readOnly = true`（返回值中增加 `readOnly: Ref<boolean>`），由 `FrameSaveBar` 显示 `errors.PERMISSION_DENIED`；其它错误抛给调用方显示。
- `src/features/frames/useUnsavedGuard.ts`：`useUnsavedGuard(isDirty: Ref<boolean>)`：用 `onBeforeRouteUpdate` / `onBeforeRouteLeave` 在选中实体变化或离开页面且草稿已修改时，用 `ElMessageBox.confirm` 询问是否放弃修改；取消则中止导航。四种编辑器都调用它（`selectEntity` 用 `router.replace`，没有它时切换节点会静默丢掉草稿）。
- 校验约定（P4-09、P4-10、P4-23 的行编辑器都照此实现，不另设接口）：可能出现未填完行的编辑器组件额外提供 `v-model:valid`（`boolean`，有未填完的行时为 `false`）；编辑器页面把各分区的 `valid` 合并后传给 `FrameSaveBar` 的 `invalid`。
- `src/features/frames/FrameSaveBar.vue`：props `isDirty`、`isSaving`、`conflict`、`serverChanged`、`invalid?: boolean`；“保存”（未修改、保存中或 `invalid` 时禁用，`invalid` 时旁边显示“有未填完的行”）、“撤销修改”；`conflict` 时显示 `el-alert`：“该实体已被其他人修改”，按钮“刷新”（确认后丢弃草稿并调用 `reloadFromServer`）；`serverChanged` 时显示较弱的提示；emit `save`、`reset`、`reload`。
- `src/features/frames/FrameHeader.vue`：props `subject: EntityNode`；显示名称、完整 IRI（等宽，“复制”按钮用 `navigator.clipboard.writeText`）、实体类型标签、已弃用标签。
- `src/features/frames/OtherAxiomsView.vue`：props `axioms: string[]`；等宽字体逐条显示，附“只读，MVP 不支持编辑”的说明；为空时整个组件不渲染。
- 文案：`src/features/frames/locales/`（顶层键 `framesCommon`）。
- 测试：
  - `src/features/frames/useFrameEditor.spec.ts`：未修改时服务端更新覆盖草稿；修改后服务端更新只设置 `serverChanged`；`save` 发送的 `from` 是草稿所基于的服务端原始对象（与 `load` 返回值 `toStrictEqual`，没有被规范化）、`to` 是草稿；未修改的草稿被服务端新数据覆盖后，`save` 的 `from` 是新的服务端值；成功后缓存被替换，并以返回值调用 `onSaved`；409 `FRAME_CHANGED` 设置 `conflict` 且保留草稿；403 设置 `readOnly` 且保留草稿；`reloadFromServer` 丢弃草稿。
  - `src/features/frames/useUnsavedGuard.spec.ts`：草稿已修改时改变 `entity` 查询参数弹出确认，取消后路由不变；未修改时直接导航。
  - `src/features/frames/FrameSaveBar.spec.ts`：未修改或 `invalid` 时“保存”禁用；`conflict` 时显示冲突提示，点击“刷新”确认后 emit `reload`。
  - `src/features/frames/frameModel.spec.ts`：`rawIriNode` 得到 `entityType: "IRI"`、`displayName` 为本地名；`normalize*Frame` 把缺失的数组补成 `[]`；`framesEqual` 对克隆后的 Frame 为真、改动一个标签后为假。

## 实现要点
- Frame 的字段在契约里多为可选；`frameModel.ts` 里提供 `normalize*Frame` 之类的函数把缺失的数组补成 `[]`。规范化只作用于草稿，`from` 始终是原始服务端对象。
- `framesEqual` 只用于判断草稿是否修改过（`isDirty`）：按 JSON 结构比较即可，不要求忽略数组顺序。注意它与后端的冲突判断不同：`to` 由后端按公理集合处理，顺序无关；`from` 由后端整条比较，必须原样回传。
- 预计改动接近 400 行；若明显超出，把 `useUnsavedGuard` 留给 P4-09 并按 `AGENTS.md` 第 6 节停下说明。
- 不要把草稿放进 Pinia；草稿属于编辑器组件实例。
- 所有按钮都要有可读的名称，能用键盘操作；冲突提示使用 `role="alert"`。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P4-22` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend --filter wp-web exec vitest run src/features/frames/useFrameEditor.spec.ts src/features/frames/FrameSaveBar.spec.ts src/features/frames/frameModel.spec.ts src/features/frames/useUnsavedGuard.spec.ts
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 409 `FRAME_CHANGED` 时草稿不丢失，并可以刷新
- [ ] 执行记录已填写

## 不在本卡范围
- 表单输入组件：`EntityPicker`、`EntityRefList`、`LiteralValueInput`、`AnnotationsEditor`（P4-26）
- 具体的 Frame 编辑器：类（P4-09）、属性（P4-10）、个体（P4-23）
- Manchester 语法编辑（不在 MVP，见 `docs/03-plan/mvp-scope.md`）
