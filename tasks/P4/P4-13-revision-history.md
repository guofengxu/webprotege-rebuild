---
id: P4-13
title: 修订历史：项目历史列表、修订差异、实体历史、回滚（二次确认）
phase: P4
module: wp-web
type: impl
size: M
work_type: web
ecc_extra: []
status: todo
depends_on: [P4-05, P4-18]
---

# P4-13 修订历史

## 目标
用户可以浏览项目的修订历史（分页、倒序），查看某个修订按实体分组的变更，在实体详情中查看该实体的历史，并在二次确认后回滚某个修订。

## 参考
- `tasks/P4/EPIC.md` 第 13 项
- BS-04（回滚）、P3-20：
  - `listRevisions` 按修订号倒序分页，每项 `{revNo, userId, ts, description, changeCount}`；
  - `getRevisionDiff` 返回 `{revNo, changes: {op, subject: EntityNode, text}[], limit, total}`，`text` 是 Manchester 语法，最多返回 `limit` 条（默认 200）；
  - `listEntityRevisions` 只返回涉及该实体的修订；
  - `revertRevision` 返回新修订号；回滚含 `DeclareOntology` 的修订 1 返回 409；查看需要 `VIEW_CHANGES`，回滚需要 `REVERT_CHANGES`
- P4-18：这些响应在契约中补齐为 `RevisionPage`、`RevisionSummary`、`RevisionDiff`、`RevisionChange`、`RevisionResponse`
- `docs/api/openapi.json` 的 operationId：`listRevisions`、`getRevisionDiff`、`listEntityRevisions`、`revertRevision`
- P4-05 的 URL 约定（`/projects/:projectId/history`）与注册表（`detailTabs`、`revisionActions`）

## 产出
- `src/features/history/api.ts`：`useRevisions(projectId, page, size)`、`useRevisionDiff(projectId, rev, limit)`、`useEntityRevisions(projectId, iri, page, size)`、`useRevertRevision(projectId)`（成功后让 `queryKeys.project(projectId)` 下的全部查询失效，因为回滚可能改变任何数据）；查询键取自 `queryKeys`。
- `src/features/history/RevisionHistoryPage.vue`（`src/features/history/routes.ts` 以 `project` 路由 `history` 导出，在 `ProjectShell` 之下）：左侧修订表格（修订号、时间、用户、描述、变更数，以及操作列中的 `RevertRevisionButton`，传入该行的 `RevisionSummary`；`el-pagination`，每页 50，页码从 1 开始，请求时 `page = currentPage - 1`），右侧选中修订的 `RevisionDiffView`；选中的修订号保存在查询参数 `rev` 中，刷新后恢复。
- `src/features/history/RevisionDiffView.vue`：props `projectId`、`rev`；按 `subject.iri` 分组（组标题为显示名称；P4-05 的 `linkTo` 返回非 `null` 时点击跳转到工作台中的该实体，`ONTOLOGY`、`DATATYPE`、`IRI` 只显示文字）；每条变更显示 `op`（添加 / 删除，用语义色与图标区分，不只靠颜色）与等宽的 `text`；`total > limit` 时提示“共 total 条，仅显示前 limit 条”；标题旁渲染注册表 `revisionActions` 中的组件（传入 `projectId`、`revision`；P4-19 的下载按钮通过它出现，本卡不依赖 P4-19）。
- `src/features/history/RevertRevisionButton.vue`：props `projectId`、`revision: RevisionSummary`；点击后弹出确认对话框，显示修订号、描述、变更数，以及“回滚会生成一个新的修订，不会删除历史”的说明；用户需在对话框中输入该修订号才能点击“确认回滚”（二次确认）；成功后提示新修订号；409 显示回滚专用的冲突文案，403 显示无权限。
- `src/features/history/EntityHistoryTab.vue`：props `projectId`、`iri`；实体相关修订列表（同样的列与分页），每行有“查看差异”链接（跳到历史页并带 `rev`）；在 `src/features/history/register.ts` 中注册为 `detailTabs` 的“历史”。
- 文案：`src/features/history/locales/`（顶层键 `history`）。
- 测试：
  - `src/features/history/RevisionHistoryPage.spec.ts`：第一页请求带 `page=0`；渲染倒序列表，每行有回滚按钮；点击一行后 URL 出现 `rev`，并请求 `getRevisionDiff`；带 `?rev=` 打开时直接显示差异。
  - `src/features/history/RevisionDiffView.spec.ts`：变更按主语分组；`total > limit` 时显示截断提示；`op` 同时以文字和图标区分。
  - `src/features/history/RevertRevisionButton.spec.ts`：未输入正确的修订号时确认按钮禁用；确认后调用 `revertRevision` 并让项目查询失效；409 显示回滚失败的说明。
  - `src/features/history/EntityHistoryTab.spec.ts`：请求带 `iri`；点击“查看差异”跳转到 `/projects/:id/history?rev=<n>`。

## 实现要点
- 时间按界面语言用 `Intl.DateTimeFormat` 格式化；`userId` 原样显示（后端不提供用户显示名称的查询）。
- `op` 的取值以后端返回为准（`RevisionChange.op`），前端只对已知取值做本地化，未知取值原样显示。
- 回滚是写操作，按钮在无权限时仍然显示，由后端返回 403；不要在前端猜测权限。
- 预计改动接近 400 行；若明显超出，把 `EntityHistoryTab` 与它的注册拆成另一张卡，按 `AGENTS.md` 第 6 节停下说明。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P4-13` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend --filter wp-web exec vitest run src/features/history/RevisionHistoryPage.spec.ts src/features/history/RevisionDiffView.spec.ts src/features/history/RevertRevisionButton.spec.ts src/features/history/EntityHistoryTab.spec.ts
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 回滚必须经过二次确认
- [ ] 执行记录已填写

## 不在本卡范围
- 下载某个修订的本体文件（P4-19 通过 `revisionActions` 注册，本卡只负责渲染这个扩展点）
- 修订之间的任意比较（后端只提供单个修订的差异）
