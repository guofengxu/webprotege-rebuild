---
id: P6-79
title: 后端：合并实体（DELETE）后将讨论线程转移到目标实体
phase: P6
module: wp-app/discussion
type: impl
size: S
work_type: app-service
ecc_extra: [rea]
status: todo
depends_on: [P6-74, P6-24]
---

# P6-79 合并后转移讨论线程

## 目标
`DELETE_MERGED_ENTITY` 合并成功后，源实体上的讨论线程改挂到目标实体；弃用合并不转移（BS-13）。

## 参考
- BS-13（P6-44 修订段）；`docs/01-rea/discussions.md` §3.6
- 旧出处：`MergeEntitiesChangeListGenerator` 中 `replaceEntity`
- EPIC「讨论（P6-44）」决定第 6、8 条
- 现有 `MergeEntitiesHandler`

## 产出
- **允许最小改动 `ontology`**：在 `MergeEntitiesHandler`（或紧邻的提交成功路径）发布 Spring 事件，载荷含 `projectId`、源实体集合、目标实体、`MergedEntityTreatment`。事件类型可放在 `ontology` 或双方都已依赖的包，避免 `ontology → discussion` 编译依赖。
- `discussion` 模块监听：仅当 treatment 为删除源实体时，对每个源调用仓储 `replaceEntity`。
- **不**改 `wp-kernel` 生成器。
- 测试：删除合并后线程挂在目标；弃用合并后仍挂在源；无讨论时合并仍成功。

## 实现要点
- 本卡 `module` 记为 discussion，但**明确允许**改动 `MergeEntitiesHandler` 一处发布逻辑（与表单卡改 overview 类似的跨包最小触点）。
- 转移失败应记日志并视为合并后的补偿问题（写清测试期望：合并修订已成功）。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口；附加：`rea`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-79` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`、`code-tour`
- 开工时可用的辅助 agent：`spec-miner`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 注意：spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='DiscussionMergeTransferTest,MergeEntitiesTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 讨论 REST UI、迁移、OpenAPI
