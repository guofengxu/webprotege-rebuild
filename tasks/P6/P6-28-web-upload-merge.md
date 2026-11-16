---
id: P6-28
title: Web 端：上传并合并到已有项目（工作台工具栏入口，选择方式、预览差异、确认应用）
phase: P6
module: wp-web
type: impl
size: M
work_type: web
ecc_extra: []
status: todo
depends_on: [P6-50, P6-22]
---

# P6-28 Web 端：上传并合并到已有项目

## 目标
项目管理者在工作台工具栏点“上传并合并”，选择文件与方式（新版本差异合并 / 只追加，只追加时选择目标本体），上传后看到预览，填写说明并确认应用；预览之后项目被别人改过时，提示并允许一键重新预览；关闭对话框时放弃暂存。

## 参考
- P6-27：`listProjectOntologies`；P6-50：`previewUploadMerge`（multipart）、`applyUploadMerge`、`discardUploadMerge`，错误码 `PROJECT_CHANGED_SINCE_PREVIEW`、`UPLOAD_NOT_FOUND`，预览结构 `UploadMergePreview`
- BS-14（两种方式的说明文字）
- P4-04：`src/features/projects/UploadProjectDialog.vue`、`uploadProject.ts`（上传进度与错误展示的写法）
- P4-05：`src/features/workspace/registry.ts`（`workspaceToolbar`）；P4-19：`src/features/download/register.ts`（工具栏入口的写法）
- P6-04：`useProjectPermissions`

## 产出
- 新功能目录 `src/features/upload-merge/`：
  - `register.ts`：`workspaceToolbar: [UploadMergeButton]`；
  - `UploadMergeButton.vue`：两个动作都没有时不显示；
  - `UploadMergeDialog.vue`：三步——① 选择文件与方式（没有 `UPLOAD_AND_MERGE_ADDITIONS` 时不提供“只追加”，反之亦然；只追加时从项目本体列表中选择目标）；② 上传（显示进度）后展示预览：每个本体的四个计数、未配对的上传本体、新增 / 删除公理文本（可折叠，等宽字体，超过 200 条时注明“只显示前 200 条”）；没有任何变化时说明“没有需要合并的内容”并只提供“关闭”；③ 填写说明并“应用”；
  - `api.ts`（multipart 上传用与 `uploadProject.ts` 相同的方式实现进度）；
  - `locales/zh-CN.ts`、`locales/en-US.ts`。
- `src/shared/api/queryKeys.ts`：增加 `projects.ontologies(projectId)`（`queryKeys.spec.ts` 同步）。
- 行为：`PROJECT_CHANGED_SINCE_PREVIEW` 时提示“预览之后项目有了新的修改”，提供“重新预览”（重新上传同一文件）；对话框在应用前被关闭时调用 `discardUploadMerge`（失败忽略）；应用成功后关闭对话框并提示新修订号。
- 测试：`UploadMergeButton.spec.ts`（权限）、`UploadMergeDialog.spec.ts`（两种方式的表单、预览渲染、空预览、409 后重新预览、关闭时放弃、应用成功）。

## 实现要点
- 新建独立的功能目录，不修改 `features/projects` 与 `features/download`；入口通过注册表加入工作台工具栏。
- 项目本体列表来自 P6-27 的 `listProjectOntologies`：显示本体 IRI（没有时显示 key）与公理数；只有一个本体时默认选中它。
- 依赖 P6-22 只是为了串行修改 `queryKeys.ts`（P6-22 → 本卡 → P6-38）。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-28` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend --filter wp-web exec vitest run src/features/upload-merge src/features/workspace/registry.spec.ts src/shared/api/queryKeys.spec.ts
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 后端（P6-27、P6-50）
- 逐条勾选公理、在预览中搜索
