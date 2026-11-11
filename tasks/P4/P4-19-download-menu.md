---
id: P4-19
title: 下载导出菜单：选择格式与修订号，下载项目本体
phase: P4
module: wp-web
type: impl
size: S
work_type: web
ecc_extra: []
status: todo
depends_on: [P4-05]
---

# P4-19 下载导出

## 目标
在项目工作台和修订历史页可以下载项目本体：选择格式（RDF/XML、Turtle、OWL/XML、Manchester、函数式语法）和修订号（默认最新），浏览器保存后端给出的文件名；下载失败时显示原因。本卡对应 `docs/03-plan/mvp-scope.md` 中 Web 端的“下载菜单”，EPIC 未单列。

## 参考
- `docs/03-plan/mvp-scope.md`（“下载导出（任意修订）| P3-12 | 下载菜单”）
- P3-12：`GET /api/projects/{id}/download?format=<owl|ttl|owx|omn|ofn>&revision=<n>`；不带 `revision` 时为最新修订；多个本体时返回 zip；`Content-Disposition` 文件名为 `<项目名>-r<修订号>.<扩展名或 zip>`；需要 `DOWNLOAD_PROJECT`（`CAN_VIEW` 即可），无角色 403，修订号不存在时返回错误 `ProblemDetail`
- `backend/wp-app/src/main/java/wprebuild/app/ontology/DownloadFormat.java`（格式取值以它为准）
- `docs/api/openapi.json` 的 operationId：`downloadProject`

## 产出
- `src/features/download/downloadProject.ts`：`downloadProject(client, projectId, { format, revision? }): Promise<{ blob: Blob; fileName: string }>`：用 `openapi-fetch` 的 `parseAs: "blob"` 调用 `downloadProject`；非 2xx 时把响应体解析为 `ProblemDetail` 并抛出 `ApiError`；文件名从 `Content-Disposition` 解析（同时支持 `filename*=UTF-8''…` 与 `filename="…"`），解析不到时用 `<projectId>.<扩展名>`。
- `src/features/download/saveBlob.ts`：`saveBlob(blob, fileName)`：`URL.createObjectURL` + 临时 `<a download>`，之后 `revokeObjectURL`。
- `src/features/download/DownloadMenu.vue`：props `projectId`、`revision?: number`；`el-dropdown` 按钮“下载”，菜单项为五种格式（显示格式名与扩展名）；点击后下载，过程中按钮显示加载状态；失败时 `ElMessage` 显示 `problemMessage`。
- `src/features/download/register.ts`：把 `DownloadMenu` 注册到 `workspaceToolbar`（P4-05 的 `WorkspacePage` 传入 `projectId`，不带修订号，即最新修订）和 `revisionActions`（P4-13 的差异视图传入 `projectId` 与修订号）。本卡不修改 `WorkspacePage.vue` 和 `RevisionHistoryPage.vue`，可以与 P4-13 并行。
- 文案：`src/features/download/locales/`（顶层键 `download`）。
- 测试：
  - `src/features/download/downloadProject.spec.ts`：请求的 `format` 与 `revision` 查询参数正确；从 `filename*=UTF-8''%E4%BB%93%E5%BA%93-r3.ttl` 解析出中文文件名；404 / 403 抛出带状态码的 `ApiError`。
  - `src/features/download/DownloadMenu.spec.ts`：选择 Turtle 后调用 `downloadProject` 并把结果交给 `saveBlob`（`saveBlob` 在测试中替换为假函数）；带 `revision` prop 时请求带修订号；失败时显示错误消息。

## 实现要点
- 下载内容一次性读成 `Blob`，换来统一的错误处理；MVP 的本体大小在可接受范围内。超大文件的流式下载不在本卡范围。
- 下载是 `GET`，不需要 CSRF 头。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P4-19` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend --filter wp-web exec vitest run src/features/download/downloadProject.spec.ts src/features/download/DownloadMenu.spec.ts
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 修订历史页本身（P4-13）
- 流式下载、断点续传
- 上传导入（P4-04）
