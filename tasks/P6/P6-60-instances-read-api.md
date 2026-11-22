---
id: P6-60
title: 实例库服务：REST 读取与多跳追溯查询
phase: P6
module: wp-instances-app
type: impl
size: M
work_type: app-service
ecc_extra: []
status: todo
depends_on: [P6-59]
---

# P6-60 实例库读取 API

## 目标
经 API Key 认证后，可按时间点读取实例，并调用 `TraceQueries` 做有界多跳 / 路径追溯；强制单租户 `projectId`。

## 参考
- P6-34 / EPIC；ADR-013 第 4 节
- P6-31：`InstanceStore.find`、`TraceQueries.reachable` / `follow`、`PathStep`、`Direction`
- P6-58 / P6-59：认证、单租户、`docs/api/instances.md`（串行改文档）

## 产出
- `GET /api/v1/projects/{projectId}/instances/{iri}?asOf=` → `InstanceRecord`；`projectId` 不匹配 → 403；不存在 → 404。
- `POST /api/v1/projects/{projectId}/trace/reachable`：`startIri`、`propertyIris`、`direction`、`maxHops`（上限例如 5）、`asOf`。
- `POST /api/v1/projects/{projectId}/trace/follow`：`startIri`、`path`（长度上限例如 8）、`asOf`。
- 超限 → 400；未认证 → 401。
- 测试覆盖上述状态码与一条三跳 `follow`。
- 更新 `docs/api/instances.md`（不改 OpenAPI；P6-64 统一出初稿）。

## 实现要点
- **必须**校验 `projectId == wp.instances.project-id`；数据表仍无 `project_id` 列（首期单租户），不得“忽略 projectId”。
- 查询只读。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-60` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-instances-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='InstancesReadApiTest,InstancesApiKeyTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 写入 / 幂等（P6-59、P6-64）、AGE、SPARQL、多项目表隔离
