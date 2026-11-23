---
id: P6-69
title: 后端：按实体取表单接口（选择器子集与 PROPERTY 投影）
phase: P6
module: wp-app/ontology
type: impl
size: M
work_type: app-service
ecc_extra: [rea]
status: todo
depends_on: [P6-67]
---

# P6-69 按实体取表单 API

## 目标
客户端可按实体拉取适用表单及投影值（首期 TEXT/NUMBER + PROPERTY）；权限 `VIEW_PROJECT`；返回当前 head 供后续保存。

## 参考
- BS-17；`docs/01-rea/forms.md` §3.1
- P6-66/67：`ontology` 可直接注入 `form` 模块仓储/门面加载描述符与选择器（EPIC 决定第 7 条；P6-66 已登记 `ontology → form`）
- 旧出处：`GetEntityFormActionHandler`、`EntityFormManager`、`BindingValuesExtractor`

## 产出
- 选择器匹配器（`ontology` 或 `form` 包内供 ontology 调用的组件）：`EntityTypeIsOneOf`、`SubClassOf`（任意祖先）、Composite ALL/ANY；任一未支持节点 → 整条不命中。
- 命令 `GetEntityForms`（entity IRI、可选 formId 过滤）→ 适用表单 + 投影数据 + 当前 head；Handler 在 `wprebuild.app.ontology`（可访问 `ProjectRuntime`）。
- 投影：TEXT/NUMBER + PROPERTY 从本体取值；其它控件/绑定占位且不失败。
- REST 在 `wprebuild.app.web`；更新 OpenAPI 与 `@wp/api-types`。
- 测试：类型/超类命中与不命中；未支持节点不命中；TEXT 投影；无 `VIEW_PROJECT` 时 403。

## 实现要点
- 本卡**不**保存；与 P6-73 约定往返不变量的读半边。
- OpenAPI 串行：本卡在 P6-67 之后、P6-73 之前。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口；附加：`rea`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-69` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`、`code-tour`
- 开工时可用的辅助 agent：`spec-miner`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 注意：spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='GetEntityFormsApiTest,OpenApiResponseSchemasTest,ModularityTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 保存（P6-73）、Web、描述符 CRUD、完整 MatchingEngine
