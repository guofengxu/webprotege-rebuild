---
id: P2-05
title: head 基准文件、verify 子命令与迁移报告（里程碑 M2）
phase: P2
module: wp-legacy-migrator
type: impl
size: M
work_type: migrator
ecc_extra: [milestone]
status: todo
depends_on: [P2-04]
---

# P2-05 head 基准文件、verify 子命令与迁移报告（里程碑 M2）

## 目标
迁移包自带比对基准，迁移工具可以自检，并输出人能读懂的迁移报告。

## 参考
- `docs/02-architecture/interchange-format.md` 第 1、5 节（`head.ofn-lines.gz`、IC-1 ~ IC-8）
- `docs/03-plan/roadmap.md`（M2 条件）

## 产出
- 导出时在 OWL API 4 下重放全部修订，写出 `head.ofn-lines.gz`（每个物理行一条变更行，`op` 固定为 `+`，按 ADR-011 做行转义，按字符串排序），并把它加入 `SHA256SUMS`。
- `migration-report.json`：每个项目的状态（成功 / 失败及原因）、修订数、变更数、每个本体按 `AxiomType` 统计的公理数、警告列表。
- `verify --package <dir>` 子命令：对每个项目执行 IC-1 ~ IC-6，有失败时退出码非 0，并打印失败规则与位置。
- 测试 `EndToEndExportTest`：用 `LegacyLogWriter` 造的项目跑完整的 `export` → `verify`；人为篡改 `revisions.jsonl` 一个字节后 `verify` 报 IC-1 失败；删掉一行后报 IC-3 失败。
- 把测试中导出的迁移包复制到 `testdata/migration/sample-package/`，供 P3-10 使用。

## 实现要点
- **真实数据验证**：请人提供一份旧 WebProtégé 数据目录的副本和对应的 mongoexport 文件，运行 `export` 与 `verify`，把 `migration-report.json` 的摘要（项目数、成功数、警告数、耗时）写进执行记录。拿不到真实数据时，相应的完成判据标为“未完成：等待数据”，状态保持 `in_progress` 并告诉人。

## ECC 技能
工作项类型：`migrator` —— 旧数据迁移工具（wp-legacy-migrator，OWL API 4）；附加：`milestone`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P2-05` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`、`production-audit`、`brownfield-retrospective`
- 提交前调用的审查 agent：`java-reviewer`、`silent-failure-hunter`
- 可用命令：`/review-pr`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：里程碑 PR 合并前跑一次 /review-pr 多代理审查；brownfield-retrospective 的差距清单写入卡片执行记录，不新建文档目录。

## 验收
```bash
./mvnw -q -pl wp-legacy-migrator test -Dtest=EndToEndExportTest
./mvnw -q -pl wp-legacy-migrator package && java -jar wp-legacy-migrator/target/wp-legacy-migrator.jar verify --package testdata/migration/sample-package
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 至少一个真实旧项目导出并通过 `verify`（M2 条件）
- [ ] 执行记录已填写

## 不在本卡范围
- 导入新系统（P3-10、P3-11）
