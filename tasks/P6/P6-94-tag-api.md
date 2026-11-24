---
id: P6-94
title: 标签 REST：项目标签目录 get/set、实体显式标签 get/update（OpenAPI）
phase: P6
module: wp-app/tag
type: impl
size: M
work_type: app-service
ecc_extra: []
status: todo
depends_on: [P6-46, P6-92, P6-93, P6-87]
---

# P6-94 标签 REST API

## 目标
登录用户可查询/整表替换项目标签目录，并按 from/to 差分更新某实体的显式标签；命令总线校验对应权限。

## 参考
- BS-23；旧 `GetProjectTags` / `SetProjectTags` / `GetEntityTags` / `UpdateEntityTags`
- EPIC「标签（P6-46）」；OpenAPI 串行接在 P6-87 之后

## 产出
- 命令与 Handler（`tag` 模块）：获取/设置项目标签目录；获取/更新实体显式标签（from/to 差分）。
- Controller 放在 `wprebuild.app.web`：REST 路径挂到项目下；Handler 留在 `tag`。
- 设置目录：删除项从全部实体剥离；label 空或项目内冲突 → 4xx。
- 持久化成功后发 Spring 域事件（供 P6-96）：目录有变 → `ProjectTagsChanged` 域事件；因剥离导致某实体显式集合变化 → 对该实体发 `EntityTagsChanged` 域事件（可批量）。本卡**不**修改密封 `ProjectEvent`。
- 更新 `docs/api/openapi.json`、`@wp/api-types`、`OpenApiResponseSchemasTest`。
- 测试：无 `EDIT_PROJECT_TAGS` / `EDIT_ENTITY_TAGS` → 403；整表替换剥离并发出对应域事件；差分更新；读仅返回显式标签；criteria 原样回传但不影响命中。

## 实现要点
- **新系统修正**：实体标签写接口声明 `EDIT_ENTITY_TAGS`（旧未声明）。
- 首期不跑 MatchingEngine；`criteria` 可读写 jsonb。
- 依赖 P6-87 **只为** OpenAPI 串行。
- 标签**不**产生修订。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-94` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='TagApiTest,OpenApiResponseSchemasTest,ModularityTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- SSE 翻译、EntityNode 填充、Web UI、迁移、MatchingEngine
