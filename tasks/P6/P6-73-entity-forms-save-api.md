---
id: P6-73
title: 后端：保存实体表单接口（ChangePipeline、expectedHead 与 pristine 锚定）
phase: P6
module: wp-app/ontology
type: impl
size: M
work_type: app-service
ecc_extra: [rea, concurrency]
status: todo
depends_on: [P6-68, P6-69]
---

# P6-73 保存实体表单 API

## 目标
客户端可提交 pristine/edited 表单数据与期望 head，将 TEXT/NUMBER（PROPERTY）变更经 `ChangePipeline` 写入修订；冲突时返回可区分的 409。

## 参考
- BS-18；`docs/01-rea/forms.md` §3.2；P6-68 生成器；P6-69 投影形状
- Frame 更新 409 语义；`StaleHeadException`
- 旧出处：`SetEntityFormsDataActionHandler`

## 产出
- 命令 `SetEntityFormsData`（pristine、edited、expectedHead）→ 新 head；Handler 在 `wprebuild.app.ontology`，调用 P6-68 生成器 + `ChangePipeline.apply(..., expectedHead)`。
- 错误映射：`STALE_HEAD`、`FORM_DATA_CHANGED`、校验失败 400；无 `EDIT_ONTOLOGY` → 403。
- REST 在 `wprebuild.app.web`；更新 OpenAPI 与 `@wp/api-types`。
- 测试：成功改 TEXT；head 冲突；pristine 过期；改 GRID 值 400；往返不改不落修订；与直接 Frame 更新同一 PROPERTY 绑定结果一致。

## 实现要点
- 必须在生成锁内比较 head（三参数 `apply`），不得先比较再生成。
- 与 P6-69 共享投影/数据 DTO，避免读写漂移。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口；附加：`rea`、`concurrency`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-73` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`、`code-tour`
- 开工时可用的辅助 agent：`spec-miner`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 注意：spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。
- 注意：审查重点：锁顺序（ADR-006）、WP-1 ~ WP-8（docs/01-rea/write-path.md）。
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='SetEntityFormsApiTest,OpenApiResponseSchemasTest,ModularityTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 取表单（P6-69）、Web、描述符 CRUD、迁移
