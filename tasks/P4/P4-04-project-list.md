---
id: P4-04
title: 项目列表：新建空项目、上传导入（进度与错误展示）、回收站
phase: P4
module: wp-web
type: impl
size: M
work_type: web
ecc_extra: []
status: done
depends_on: [P4-03]
---

# P4-04 项目列表

## 目标
用户登录后看到自己能访问的项目，可以新建空项目、上传本体文件创建项目（看到上传进度和解析错误），可以把项目移入回收站、在回收站中恢复。

## 参考
- `tasks/P4/EPIC.md` 第 4 项
- P3-08（项目生命周期：列表只含有角色且不在回收站的项目，系统管理员看到全部；回收站中的项目访问返回 409 `PROJECT_TRASHED`）
- P3-09（上传：multipart 字段 `file`，查询参数 `name`、`description`；支持 owl / rdf / ttl / owx / omn / ofn 与 zip；超过 `wp.upload.max-size`（默认 200MB）返回 413 `UPLOAD_TOO_LARGE`；解析失败返回 400 `ONTOLOGY_PARSE_FAILED`，`detail` 带行号）
- `docs/api/openapi.json` 的 operationId：`listProjects`、`createProject`、`uploadProject`、`listTrashedProjects`、`trashProject`、`restoreProject`
- `@wp/api-types` 的 `ProjectResponse`、`CreateProjectRequest`

## 产出
- `src/features/projects/api.ts`：`useProjects()`（`listProjects`，键 `queryKeys.projects()`）、`useTrashedProjects()`（`listTrashedProjects`，键 `queryKeys.trashedProjects()`）、`useCreateProject()`、`useTrashProject()`、`useRestoreProject()`（成功后让 `projects()` 与 `trashedProjects()` 失效）。
- `src/features/projects/uploadProject.ts`：`uploadProject(input: { file: File; name: string; description?: string }, onProgress: (ratio: number) => void, signal?: AbortSignal): Promise<ProjectResponse>`。
  - 用 `XMLHttpRequest` 实现（`fetch` 拿不到上传进度），URL 与查询参数类型取自 `paths["/api/projects/upload"]["post"]`，响应类型取自 `ProjectResponse`，不手写类型。
  - 发送前读取 `XSRF-TOKEN` 设置 `X-XSRF-TOKEN`（复用 P4-02 的 `readCsrfToken`）；同源请求不需要 `withCredentials`。
  - 非 2xx 时把响应体解析为 `ProblemDetail` 并抛出 P4-02 的 `ApiError`；网络错误与取消分别抛出可区分的错误。
- `src/features/projects/ProjectListPage.vue`（路由 `/projects`，`/` 重定向到这里）：
  - 两个标签页“我的项目”“回收站”；
  - 项目表格列：名称（链接到 `/projects/:projectId`）、描述、所有者、最后修改时间（按界面语言格式化）、最后修改人、操作（“移入回收站”需要 `ElMessageBox.confirm`）；
  - 回收站表格的操作是“恢复”；
  - 空列表有空状态和“新建项目”入口；
  - 加载失败显示 `problemMessage`。
- `src/features/projects/CreateProjectDialog.vue`：名称（必填）、描述、本体 IRI（可选，填写时校验为绝对 IRI）；成功后关闭并跳转到新项目。
- `src/features/projects/UploadProjectDialog.vue`：文件选择（`accept` 为上面列出的扩展名与 `.zip`）、名称（默认取文件名去掉扩展名）、描述；上传中显示 `el-progress` 与“取消”按钮；413 显示大小上限提示；400 `ONTOLOGY_PARSE_FAILED` 把 `detail`（含行号）放在可复制的等宽文本框里；成功后跳转到新项目。
- `src/features/projects/routes.ts`：`layout` 路由 `/projects` 与 `{ path: "", redirect: "/projects" }`；删除 P4-01 的占位首页目录 `src/features/home/`（含 `HomePage.spec.ts`）。
- 文案：`src/features/projects/locales/`（顶层键 `projects`）。
- 测试：
  - `src/features/projects/ProjectListPage.spec.ts`：渲染 `listProjects` 的结果；“移入回收站”确认后调用 `trashProject`，列表刷新；回收站标签页“恢复”调用 `restoreProject`。
  - `src/features/projects/CreateProjectDialog.spec.ts`：名称为空不能提交；非法 IRI 显示校验错误；成功后跳转 `/projects/<projectId>`。
  - `src/features/projects/uploadProject.spec.ts`：用假的 `XMLHttpRequest` 驱动 `upload.onprogress` 回调；请求带 `X-XSRF-TOKEN` 和正确的查询参数；413 与 400 抛出带 `code` 的 `ApiError`；`abort` 后抛出取消错误。

## 实现要点
- 上传是本卡唯一不经过 `openapi-fetch` 的请求，原因写在 `uploadProject.ts` 的文件头注释里（需要上传进度）；它的类型依然全部来自 `@wp/api-types`。
- 项目列表通常不大，用 `el-table`；本卡不做分页（后端接口不分页）。
- 时间显示用 `Intl.DateTimeFormat` 跟随当前界面语言，不引入日期库。
- 是否能新建、上传由后端判断（`CREATE_EMPTY_PROJECT` / `UPLOAD_PROJECT`）；前端始终显示按钮，403 时显示 `errors.PERMISSION_DENIED`。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P4-04` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend --filter wp-web exec vitest run src/features/projects/ProjectListPage.spec.ts src/features/projects/CreateProjectDialog.spec.ts src/features/projects/uploadProject.spec.ts
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 上传错误（413、400 带行号）在界面上有对应的提示
- [x] 执行记录已填写

## 不在本卡范围
- 项目改名、改描述（P4-15）
- 下载导出（P4-19）
- 导入迁移包（P3-10 / P3-11 没有对外的 REST 接口）
- 项目工作台本身（P4-05）

## 执行记录
- 日期：2026-11-12
- 分支：feature/p4-04-project-list
- 测试：2026-11-12T08:36:03+08:00 通过（3 项）
- 实际改动：新增项目列表页（我的项目/回收站）、新建空项目与上传导入对话框；XHR 上传带进度与 413/解析错误展示；删除 home 占位；i18n glob 收窄为 zh-CN/en-US。
- 偏差：i18n.ts 的 import.meta.glob 从 *.ts 改为 {zh-CN,en-US}.ts，避免 Vite 内容哈希缓存漏掉新建 locale 文件。审查意见已处理：上传关闭竞态、onUnmounted 清理、aria-describedby、解析错误去重、row-key 与 pending 禁用。
- 审查：vue-reviewer, typescript-reviewer, a11y-architect
