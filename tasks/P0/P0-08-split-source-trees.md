---
id: P0-08
title: 按运行时拆分源码目录
phase: P0
module: root
type: impl
size: S
work_type: build
ecc_extra: []
status: done
depends_on: [P0-02]
---

# P0-08 按运行时拆分源码目录

## 目标
后端、Web 前端和移动端的源码分属不同目录：Java 模块在 `backend/`，两个前端工程在 `frontend/`。

## 参考
- `docs/02-architecture/overview.md` 第 1 节
- ADR-008、ADR-014（工程名仍是 wp-web、wp-mobile）

## 产出
- `backend/wp-kernel`、`backend/wp-app`、`backend/wp-legacy-migrator`：从仓库根移入。三个子 POM 的 parent `relativePath` 为 `../../pom.xml`。根 `pom.xml` 的 `<modules>` 使用这些相对路径。
- `docs/02-architecture/overview.md` 第 1 节与 `README.md` 的目录说明改为上述布局，并写明 Web 在 `frontend/wp-web`、移动端在 `frontend/wp-mobile`。本卡不创建这两个工程。
- 尚未开工的任务卡中，仓库内文件路径改为新位置：P0-06、P1-08、P1-20、P2-01、P2-05、P3-01、P3-27、P4-00、P5-00，以及 `tasks/P4/EPIC.md`、`tasks/P5/EPIC.md`、`tasks/TEMPLATE.md`。
- P0-06 中 `TestOntologies.dir()` 的默认相对路径改为 `../../testdata/ontologies`（模块比原来深一层）。`${maven.multiModuleProjectDirectory}` 不变。
- Cursor 规则 glob：`.cursor/rules/30-vue-web.mdc` 匹配 `frontend/wp-web/**`；`.cursor/rules/31-uniapp.mdc` 匹配 `frontend/wp-mobile/**`。
- `tools/task.py` 的模块越界检查把 `backend/<模块>`、`frontend/<模块>` 视为对应模块。
- 尚未完成的任务卡里，Maven 模块选择从 `-pl wp-kernel` 改为 `-pl :wp-kernel`（`wp-app`、`wp-legacy-migrator` 同理）。已完成的 P0-01、P0-02 不改。

## 实现要点
- 不修改已接受的 ADR。ADR-008、ADR-014 仍写 `wp-web/`、`wp-mobile/`，指工程名；物理目录是 `frontend/wp-web`、`frontend/wp-mobile`。
- 不改 Maven artifactId。模块选择用 `-pl :wp-kernel`，或用目录 `-pl backend/wp-kernel`。不带冒号的 `-pl wp-kernel` 会被 Maven 当成仓库根下的目录，搬迁后找不到模块。
- 已完成的 P0-01、P0-02 保留当时的路径，作为历史记录。
- `frontend/` 下不要新建 shared 或共用 Vue 组件目录。
- 本卡当时还不在主分支上，`python tools/task.py start` 无法建分支。分支 `feature/p0-08-split-source-trees` 从 `origin/main` 按 task.py 的命名规则创建，没有推送。

## ECC 技能
工作项类型：`build` —— 工程构建与配置（Maven、POM、Wrapper、质量门禁）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P0-08` 查看。

- 开工前加载的 skill：`java-coding-standards`
- 提交前调用的审查 agent：`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q verify
test -d backend/wp-kernel && test -d backend/wp-app && test -d backend/wp-legacy-migrator
test ! -d wp-kernel && test ! -d wp-app && test ! -d wp-legacy-migrator
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写

## 不在本卡范围
- 创建 wp-web、wp-mobile 工程（P4、P5）
- 修改 ADR
- 质量门禁、数据库、测试语料（P0-03、P0-04、P0-06）

## 执行记录
- 日期：2026-11-01
- 分支：feature/p0-08-split-source-trees
- 测试：2026-11-01T11:31:49+08:00 通过（5 项）
- 实际改动：把 wp-kernel、wp-app、wp-legacy-migrator 移到 backend/，并更新 overview、README、前端规则 glob，以及尚未完成任务卡中的文件路径和 Maven -pl 选择器。
- 偏差：分支按 task.py 的命名从 origin/main 创建，因为当时主分支上没有这张卡，没有推送。未修改 ADR-008 与 ADR-014；物理目录是 frontend/wp-web 与 frontend/wp-mobile。已完成的 P0-01、P0-02 保留当时的路径和 -pl 写法。
- 审查：code-reviewer
