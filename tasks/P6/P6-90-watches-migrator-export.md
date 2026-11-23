---
id: P6-90
title: 迁移工具：导出 Mongo Watches 到交换包
phase: P6
module: wp-legacy-migrator
type: impl
size: S
work_type: migrator
ecc_extra: [rea]
status: todo
depends_on: [P6-45, P6-84]
---

# P6-90 导出 Watches

## 目标
迁移工具从旧 Mongo 导出项目的 Watches 文件到交换包；无数据时仍写出空文件。

## 参考
- BS-21；`watches.md` §4；`docs/02-architecture/interchange-format.md`
- 旧集合 `Watches`；EPIC「关注（P6-45）」决定第 10 条

## 产出
- 交换包项目目录增加 watches 文件（JSONL 或卡片选定的稳定格式：userId、entity iri/type、watchType）。
- `interchange-format.md` 增补该文件说明；**不**升 `formatVersion`。
- 导出始终写文件（可空）；SHA256SUMS 纳入。
- 测试：有/无 Watches 的样例导出。

## 实现要点
- 只读旧工程数据；不复制旧 Java 源码。
- 对 P6-84 只对齐字段语义；`wp-legacy-migrator` **禁止**依赖 `wp-app`。

## ECC 技能
工作项类型：`migrator` —— 旧数据迁移工具（wp-legacy-migrator，OWL API 4）；附加：rea。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-90` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`、`code-tour`
- 开工时可用的辅助 agent：`spec-miner`
- 提交前调用的审查 agent：`java-reviewer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。

## 验收
```bash
./mvnw -q -pl :wp-legacy-migrator -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='WatchesExportTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 后端导入（P6-91）
