---
id: P3-14
title: 创建与删除实体 API
phase: P3
module: wp-app/ontology, web
type: impl
size: S
work_type: app-service
ecc_extra: []
status: todo
depends_on: [P3-13]
---

# P3-14 创建与删除实体 API

## 目标
用户可以在层级中新建和删除各类实体。

## 参考
- BS-05、BS-06、BS-07、BS-10

## 产出
- 命令 `CreateEntities(ProjectId, EntityType, List<String> names, Optional<String> lang, Set<IRI> parents)`，按实体类型要求 `CREATE_CLASS` / `CREATE_PROPERTY` / `CREATE_INDIVIDUAL`；返回创建的 `EntityNode` 列表和修订号。
- 命令 `DeleteEntities(ProjectId, Set<IRI>)`，按实体类型要求对应的 `DELETE_*` 动作（混合类型时逐一检查）；返回修订号。
- `ProjectRuntimeFactory` 中的 `CreatePermission` 接入 `AccessService`。
- `POST /api/projects/{id}/entities`、`DELETE /api/projects/{id}/entities`（请求体 `{iris: [...]}`）。
- 测试 `EntityCreateDeleteTest`：创建两个子类后，层级查询中可见、显示名称正确；删除后不可见；CAN_VIEW 用户创建得到 403；项目的 head 修订号相应递增。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P3-14` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=EntityCreateDeleteTest
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 新实体设置的读写 API（P3-22）
