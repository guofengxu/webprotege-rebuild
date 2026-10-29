---
id: P3-20
title: 修订历史、实体历史与回滚 API
phase: P3
module: wp-app/revision, ontology, web
type: impl
size: M
work_type: app-service
ecc_extra: []
status: todo
depends_on: [P3-08, P1-15]
---

# P3-20 修订历史、实体历史与回滚 API

## 目标
用户能看到谁在什么时候改了什么，并能撤销某次修订。

## 参考
- BS-04
- `docs/01-rea/README.md` 第 7 节第 2 条（旧系统按实体查历史要扫描全部修订）
- 旧实现：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/revision/ProjectChangesManager.java`、`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/diff/`

## 产出
- 模块分工：`revision` 模块提供只读查询接口 `RevisionHistoryQueries`（基于 `revisions`、`revision_subjects` 表的元数据查询）；全部命令处理器放在 `ontology` 模块，因为渲染差异需要显示名称视图、回滚需要运行时。
- 命令 `ListRevisions(ProjectId, int page, int size)`：按修订号倒序分页，返回 `{revNo, userId, ts, description, changeCount}`，只查 `revisions` 表的元数据列，不解码 payload。
- 命令 `GetRevisionDiff(ProjectId, RevisionNumber, int limit)`：解码该修订，按主语实体分组，每条变更渲染为 `{op, subject(EntityNode), text}`，`text` 用 Manchester 语法渲染；每个修订最多返回 `limit` 条（默认 200），同时返回总数。
- 命令 `ListEntityRevisions(ProjectId, IRI, int page, int size)`：通过 `revision_subjects` 查询，不扫描全部修订。
- 命令 `RevertRevision(ProjectId, RevisionNumber)`（需要 `REVERT_CHANGES`），使用 `RevertRevisionGenerator`。
- 以上命令都需要 `VIEW_CHANGES`（回滚除外）；REST：`GET /api/projects/{id}/revisions`、`GET /api/projects/{id}/revisions/{rev}`、`GET /api/projects/{id}/entities/history?iri=...`、`POST /api/projects/{id}/revisions/{rev}/revert`。
- 测试 `RevisionHistoryTest`：分页与倒序；差异按主语分组；实体历史只包含相关修订；回滚后 head + 1，内容恢复；回滚含 `DeclareOntology` 的修订 1 返回 409。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P3-20` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=RevisionHistoryTest
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 两个任意修订之间的比较
