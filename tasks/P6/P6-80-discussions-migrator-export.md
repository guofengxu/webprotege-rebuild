---
id: P6-80
title: 迁移工具：从 Mongo 导出 EntityDiscussionThreads 到迁移包
phase: P6
module: wp-legacy-migrator
type: impl
size: M
work_type: migrator
ecc_extra: [rea]
status: todo
depends_on: [P6-74]
---

# P6-80 讨论数据导出

## 目标
`wp-legacy-migrator` 从旧 Mongo 导出每个项目的实体讨论线程；`interchange-format.md` 记载生产者侧格式（不升 `formatVersion`）。

## 参考
- `docs/01-rea/discussions.md` §4；`legacy-data.md`；BS-19
- `docs/02-architecture/interchange-format.md`（本卡扩展，P6-81 消费）
- 旧集合：`EntityDiscussionThreads`
- EPIC 决定第 7 条

## 产出
- 每个 `projects/<projectId>/` **始终**写入（无数据则为空文件）：`discussion-threads.jsonl`，每行一个线程（含 `threadId`、`entity`、`status`、`comments[]` 字段与旧 Morphia/JSON 对齐处写清）。
- `SHA256SUMS` 纳入该文件；`migration-report.json` 记录条数。
- 更新 `interchange-format.md`：目录、字段、**不升 formatVersion**、IC 增补；旧包无此文件时由 P6-81 兼容。
- 测试：fixture 导出内容断言。

## 实现要点
- 只读旧库；保留 `body` 与已有 `renderedBody`（导入方可选择重渲染，由 P6-81 决定并写清）。
- 不实现导入。

## ECC 技能
工作项类型：`migrator` —— 旧数据迁移工具（wp-legacy-migrator，OWL API 4）；附加：`rea`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-80` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`、`code-tour`
- 开工时可用的辅助 agent：`spec-miner`
- 提交前调用的审查 agent：`java-reviewer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 注意：spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-legacy-migrator test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='DiscussionsExportTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- wp-app 导入、运行时 API、Web
