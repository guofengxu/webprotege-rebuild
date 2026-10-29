---
id: P3-13
title: 层级查询 API
phase: P3
module: wp-app/ontology, web
type: impl
size: M
work_type: app-service
ecc_extra: [rea]
status: todo
depends_on: [P3-08]
---

# P3-13 层级查询 API

## 目标
前端可以懒加载浏览四种层级。

## 参考
- BS-08、BS-09
- 旧实现：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/hierarchy/GetHierarchyRootsActionHandler.java`、`GetEntityHierarchyChildrenActionHandler.java`、`GetHierarchyPathsToRootActionHandler.java`

## 产出
- `wprebuild.app.ontology.dto.EntityNode`：`record EntityNode(String iri, String entityType, String displayName, boolean hasChildren, boolean deprecated)`；`deprecated` 取自 `owl:deprecated true` 注解。
- `HierarchyKind`：`CLASS`、`OBJECT_PROPERTY`、`DATA_PROPERTY`、`ANNOTATION_PROPERTY`。
- 命令 `GetHierarchyRoots(ProjectId, HierarchyKind)`、`GetHierarchyChildren(ProjectId, HierarchyKind, IRI parent)`、`GetHierarchyPaths(ProjectId, HierarchyKind, IRI entity)`；都需要 `VIEW_PROJECT`，都在读锁内执行；子节点按显示名称排序（不区分大小写）。
- `GET /api/projects/{id}/hierarchies/{kind}/roots`、`.../children?parent=<iri>`、`.../paths?entity=<iri>`（`kind` 使用小写连字符形式，如 `object-property`）。
- 测试 `HierarchyQueryTest`：基于 `warehouse-sample.ttl` 验证根、子节点、路径与排序；不存在的 IRI 返回空列表而不是 404；未授权用户得到 403。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口；附加：`rea`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P3-13` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`、`code-tour`
- 开工时可用的辅助 agent：`spec-miner`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。

## 验收
```bash
./mvnw -q -pl wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=HierarchyQueryTest
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 移动节点（拖拽改父类）——放到 P3-16 的 Frame 编辑中
