---
id: P6-95
title: EntityNode 显式标签字段与层级/搜索响应填充
phase: P6
module: wp-app/web
type: impl
size: M
work_type: app-service
ecc_extra: []
status: todo
depends_on: [P6-46, P6-94]
---

# P6-95 EntityNode 附带显式标签

## 目标
层级与搜索返回的 `EntityNode` 携带该实体的显式标签摘要，供 Web chips / 显示过滤使用（对齐旧 `EntityNodeRenderer`；**不做**服务端 search-by-tag）。

## 参考
- BS-23；`tags.md` §3.4 / §7；EPIC「标签（P6-46）」决定第 4、9 条
- 现有 `EntityNode`、`ResponseSchemasCustomizer`、层级/搜索 Controller

## 产出
- `EntityNode`（`ontology.dto`）增加 `tags` 字段：元素为摘要（至少 `tagId`、`label`、`color`、`backgroundColor`）；ontology / search **生产者默认填空列表**，不查库。
- **web Controller 后处理**：`ProjectController`（及同类 REST）在 `bus.execute` 拿到层级 roots/children/paths 或搜索命中后，调用 `tag` 模块批量查询并写回 `tags`；**禁止**在 `HierarchyQuery` / search Handler 内依赖 `tag`，也禁止 `ontology`→`tag`。
- Tag 摘要类型放在 `ontology.dto` 或 shared DTO（勿让 ontology 引用 `wprebuild.app.tag`）。
- 更新 OpenAPI / `@wp/api-types` / `OpenApiResponseSchemasTest`（OpenAPI 链：P6-94 → **本卡** → P6-96）。
- 测试：有显式标签的实体出现在 children 响应的 `tags`；无标签为空数组；未求值的 criteria 不产生额外标签；Frame/MCP 路径可保持空 `tags`（首期可不 enrich）。

## 实现要点
- 依赖 P6-94 的查询 API；批量按 `(iri,type)` 查询避免 N+1。
- 本卡不实现 Web 可见性 UI（P6-97）。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-95` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='EntityNodeTagsEnrichmentTest,OpenApiResponseSchemasTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 服务端按 Tag 过滤搜索命中集、条件自动打标、前端 UI
