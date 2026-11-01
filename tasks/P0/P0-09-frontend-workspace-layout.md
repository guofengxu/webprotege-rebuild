---
id: P0-09
title: 前端工作区收进 frontend/，补齐跨端构建约定
phase: P0
module: root
type: impl
size: S
work_type: build
ecc_extra: []
status: done
depends_on: [P0-08]
---

# P0-09 前端工作区收进 frontend/，补齐跨端构建约定

## 目标
仓库根目录只保留 Maven 一套构建工具；前端的 pnpm 工作区、共享类型包都在 `frontend/` 下。尚未开工的卡片写清 uni-app 的安装退路、wp-web 产物进入 wp-app 的方式、CI 按改动范围运行。

## 参考
- `docs/02-architecture/overview.md` 第 1 节
- ADR-008、ADR-014（`packages/api-types` 指包，物理目录改为 `frontend/packages/api-types`）
- P0-05、P0-08、P3-27、P4-00、P5-00，`tasks/P4/EPIC.md`、`tasks/P5/EPIC.md`

## 产出
- 布局：pnpm 工作区根改为 `frontend/`（`frontend/package.json`、`frontend/pnpm-workspace.yaml`、`frontend/.npmrc`、`frontend/.nvmrc`），共享类型包改为 `frontend/packages/api-types`。修改 `docs/02-architecture/overview.md` 第 1 节、`README.md` 目录、`docs/03-plan/roadmap.md`、`.cursor/rules/10-architecture-guardrails.mdc`、`tasks/TEMPLATE.md` 的物理目录注释。
- `tools/task.py`：前端全量检查改为 `pnpm -C frontend --filter <包> run …`；`module_prefixes` 对写成 `backend/<模块>`、`frontend/<模块>` 的 module 去掉父目录，不再静默跳过越界检查。`docs/04-dev-workflow.md` 中的命令同步修改。
- `.cursor/rules/30-vue-web.mdc` 的 glob 去掉 `packages/**`，只匹配 `frontend/wp-web/**`。
- P3-27：路径与验收命令改为新位置；先验证 uni-app CLI 模板在工作区内能否安装与构建，再决定 `wp-mobile` 是否作为工作区成员；CI 的前端任务按 `ci-gate` 方式接入。
- P5 EPIC 第 1 项、P5-00：写明 ADR-014 的第一种退路（只给 wp-mobile 设 `node-linker=hoisted`）不可行；`@wp/api-types` 用 `link:` 引用，在工作区内外都能用。
- P4 EPIC、P4-00：新增第 16 项“单包打包”（module `wp-app`），用默认关闭的 Maven profile `with-web` 打包 `frontend/wp-web/dist`，默认 `./mvnw verify` 不需要 Node；原第 16 项端到端验收顺延为第 17 项。第 1 项补充开发时 Vite 代理 `/api`。验收命令改为 `pnpm -C frontend …`。
- P0-05：CI 改为“改动检测 + 按端运行 + `ci-gate` 汇总”，`required_status_checks` 只写 `ci-gate`。
- `.gitignore` 去掉重复行，补前端产物；`.editorconfig` 补前端文件的 2 空格缩进。

## 实现要点
- 不修改已接受的 ADR。ADR-008、ADR-014 里的 `packages/api-types` 指共享类型包，物理目录是 `frontend/packages/api-types`；在 overview 里写明，做法同 P0-08。
- `frontend/packages/` 只放零运行时依赖的纯类型包，不放 Vue 组件或运行时代码；P0-08 “不要在 frontend/ 下新建共用组件目录”的约束不变。
- 不创建任何前端工程或 `package.json`，只改文档、卡片与流程脚本。
- 已完成的 P0-01、P0-02、P0-08 不改。
- 本卡不在主分支上，`python tools/task.py start` 无法建分支；分支 `feature/p0-09-frontend-workspace-layout` 按 task.py 的命名规则从 `origin/main` 创建。

## ECC 技能
工作项类型：`build` —— 工程构建与配置（Maven、POM、Wrapper、质量门禁）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P0-09` 查看。

- 开工前加载的 skill：`java-coding-standards`
- 提交前调用的审查 agent：`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
python tools/plan_lint.py --check
python tools/test_check_ecc.py
! grep -rn "^packages/\|成员：frontend/wp-web\|pnpm --filter" README.md docs/02-architecture/overview.md docs/04-dev-workflow.md tools/task.py tasks/P3/P3-27-frontend-workspace-api-types.md tasks/P4/P4-00-web-frontend-planning.md tasks/P5/P5-00-mobile-planning.md
grep -q "frontend/wp-web/\*\*\"\]" .cursor/rules/30-vue-web.mdc
python -c "import sys; sys.path.insert(0, 'tools'); import task, plan_lint; from pathlib import Path; card = lambda m: plan_lint.Card(Path('x.md'), {'module': m}, ''); assert task.module_prefixes(card('frontend/wp-web')) == {'wp-web'}; assert task.module_prefixes(card('wp-kernel')) == {'wp-kernel'}; assert task.module_prefixes(card('root')) is None"
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写

## 不在本卡范围
- 创建前端工程、`package.json` 或 CI 工作流文件（P0-05、P3-27、P4、P5）
- 修改 ADR
- 把 Maven 构建根移进 `backend/`（改动面大、收益小，不做）

## 执行记录
- 日期：2026-11-01
- 分支：feature/p0-09-frontend-workspace-layout
- 测试：2026-11-01T14:24:35+08:00 通过（7 项）
- 实际改动：pnpm 工作区与共享类型包的位置改为 frontend/ 与 frontend/packages/api-types；task.py 的前端全量检查改为 pnpm -C，module_prefixes 去掉 backend/、frontend/ 父目录；P3-27、P4、P5 卡片补充 uni-app 安装验证与单独安装方式、wp-web 单包打包（profile with-web，新增 P4 EPIC 第 16 项）；P0-05 CI 改为改动检测 + ci-gate 汇总；.gitignore 与 .editorconfig 补前端条目。
- 偏差：本卡不在主分支上，分支按 task.py 命名规则从 origin/main 手工创建；本机原无 JDK 25，下载 Temurin 25.0.5 到 D:\00.workspace\98.IDE 后仅在测试命令中设置 JAVA_HOME。按 code-reviewer 意见修正了 P4 EPIC 第 16 项（enforcer 检查 dist、-am、BOOT-INF 路径）、P0-05 的 changes job（fetch-depth 0、workflow 文件计入后端）以及 wp-mobile 单独安装需自带 pnpm-workspace.yaml。未修改 ADR。
- 审查：code-reviewer
