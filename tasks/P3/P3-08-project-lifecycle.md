---
id: P3-08
title: 项目生命周期：创建空项目、列表、详情、回收站
phase: P3
module: wp-app/project, ontology, web
type: impl
size: M
work_type: app-service
ecc_extra: []
status: todo
depends_on: [P3-07, P3-03]
---

# P3-08 项目生命周期：创建空项目、列表、详情、回收站

## 目标
用户可以创建、查看、整理自己的项目。

## 参考
- BS-03、BS-10
- 旧实现：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/project/CreateNewProjectActionHandler.java`、`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/project/ProjectDetailsManagerImpl.java`、`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/project/DefaultOntologyIdManagerImpl.java`

## 产出
- 模块分工（受 Modulith 规则约束）：只读写元数据的命令放在 `project` 模块；需要操作 `ProjectRuntime` 的命令（创建空项目）和订阅 `RevisionCommittedEvent` 的监听器放在 `ontology` 模块（`ontology` 可以依赖 `project`，反之不行）。
- `wprebuild.app.project.JdbcProjectRepository`、`ProjectDetails`（record）；`ProjectExistenceCheck` 的实现。
- 命令与处理器（处理器位于 `wprebuild.app.ontology`）：`CreateEmptyProject(String name, String description, Optional<IRI> ontologyIri)` → 返回 `ProjectId`：写入 `projects`，调用 `AccessService.grantCreator`，再通过 `ProjectRuntimeRegistry` 提交修订 1，内容为一条 `DeclareOntology(o1, 本体IRI)`；未指定本体 IRI 时用 `${wp.project.ontology-iri-base}<projectId>`（配置默认 `http://example.org/wp/`）。需要 `CREATE_EMPTY_PROJECT`。
- `ListMyProjects` → 当前用户有任意角色且不在回收站的项目，系统管理员看到全部；`ListTrashedProjects`；`GetProjectDetails(ProjectId)`（需要 `VIEW_PROJECT`）；`UpdateProjectDetails(ProjectId, name, description)`（需要 `EDIT_PROJECT_SETTINGS`）；`MoveProjectToTrash`、`RestoreProjectFromTrash`（需要 `MOVE_ANY_PROJECT_TO_TRASH`，后者 `allowsTrashedProject = true`）。
- `wprebuild.app.web.ProjectController`：`POST /api/projects`、`GET /api/projects`、`GET /api/projects/trash`、`GET /api/projects/{id}`、`PATCH /api/projects/{id}`、`POST /api/projects/{id}/trash`、`DELETE /api/projects/{id}/trash`。
- `wprebuild.app.ontology.ProjectModifiedUpdater`：订阅 `RevisionCommittedEvent`，更新 `projects.modified_at` / `modified_by`（通过 `project` 模块公开的仓储接口）。
- 测试 `ProjectLifecycleTest`（MockMvc）：创建后在列表中可见，head 为 1；其他用户看不到；只有 CAN_VIEW 的用户不能改名；进回收站后从列表消失，再访问返回 409；恢复后可以访问。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P3-08` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ProjectLifecycleTest
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 彻底删除项目
- 上传导入（P3-09）
