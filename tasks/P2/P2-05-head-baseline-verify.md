---
id: P2-05
title: head 基准文件、verify 子命令与迁移报告（里程碑 M2）
phase: P2
module: wp-legacy-migrator
type: impl
size: M
work_type: migrator
ecc_extra: [milestone]
status: done
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
./mvnw -q -pl :wp-legacy-migrator test -Dtest=EndToEndExportTest
./mvnw -q -pl :wp-legacy-migrator package && java -jar backend/wp-legacy-migrator/target/wp-legacy-migrator.jar verify --package testdata/migration/sample-package
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 至少一个真实旧项目导出并通过 `verify`（M2 条件）
- [x] 执行记录已填写

## 执行记录
- 日期：2026-11-07
- 分支：`feature/p2-05-head-baseline-verify`
- 测试：`python3 tools/task.py test P2-05` 通过（`EndToEndExportTest`、样例包 `verify`、全量 `./mvnw -q verify`）
- 实际改动：导出写入 `head.ofn-lines.gz`（OWL API 4 重放后的公理、本体注解和导入，`op` 固定为 `+`，ADR-011 行转义，按字符串排序）并计入 `SHA256SUMS`。`migration-report.json` 记录每个项目的成功或失败、修订数、变更数、按 `AxiomType.getName()` 统计的公理数和警告。`verify --package` 检查 IC-1 到 IC-6，失败时退出码为 1，并打印规则和位置。样例包在 `testdata/migration/sample-package/`。
- 偏差：BinaryOWL 把匿名本体都写成同一个标记，旧编辑器重放时也把它们当成一个本体。导出前先把一个项目里的匿名本体并成同一个 id，因此跨修订的删除能作用到先前添加的公理上；同一项目里无法再区分两个匿名本体。样例包 `manifest.json` 的 `legacyDataDir` 改成了仓库相对路径 `testdata/migration/sample-source`，并重算了 `SHA256SUMS`，避免把本机绝对路径写进语料。生产代码超过卡片预期的 400 行，因为 head 重放、迁移报告和 IC-1 到 IC-6 是同一份里程碑产出。
- 审查：`java-reviewer`、`silent-failure-hunter`。已处理匿名本体跨修订对不上、校验中途 I/O 失败丢掉已发现的规则、以及缺失的 `headRevision` / `changeCount` 被报成哨兵值。本 JDK 的 gzip 头时间戳固定为 0，没有再改。
- 差距（brownfield，不另建文档）：IC-7、IC-8 仍由 P3-10 用 OWL API 5 做；行转义不能引用内核，所以在迁移工具里另写了一份；`SetOntologyID` 仍然拒绝。语言标签里含冒号的字面量无法写成函数式语法。
- 真实数据：数据目录 `/home/guofengxu/00.workspace/webprotege/.protegedata/protege`，mongoexport 从容器 `webprotege-mongodb` 的库 `webprotege` 现导出（本机原来没有 mongoexport 目录）。5 个项目，成功 4，失败 1，警告 11，导出耗时 7.9 秒。写出的包 `verify` 退出码 0。失败项目 `0a3eaa4c-be4d-443b-9a43-99d853612b3c` 的修订 124 有一条语言标签为 `xsd:datetime` 的字面量，函数式语法无法书写。成功项目的修订数 / 变更数：`45d5985f-f280-4c9b-8cb5-a703023224e1` 为 1 / 361，`c193cbb4-76e8-4a77-8505-f5f55070536f` 为 1 / 2，`e9ea2dbd-0f86-4d59-bb48-2be4ec5bfc3d` 为 1 / 10779，`58eb4e67-6f40-45fa-a40e-d1b6e94b762e` 为 0 / 0。警告是没有用户名的角色分配，以及无法映射的 `AccountCreator`、`ProjectCreator`、`ProjectUploader`、`SystemAdmin`。导出的 `users.json` 只有 username、realName、email，没有盐和口令摘要。

## 不在本卡范围
- 导入新系统（P3-10、P3-11）
- 日期：2026-11-08
- 分支：feature/p2-05-head-baseline-verify
- 测试：2026-11-08T09:19:10+08:00 通过（4 项）
- 实际改动：导出写入 head.ofn-lines.gz 和带项目状态的 migration-report.json，verify 检查 IC-1 到 IC-6。真实数据 5 个项目，成功 4，失败 1，警告 11，导出 7.9 秒，写出的包 verify 退出码 0。
- 偏差：匿名本体在导出前并成同一个 id，与旧编辑器重放一致。样例包 legacyDataDir 改成仓库相对路径并重算校验和。生产代码超过 400 行。项目 0a3eaa4c-be4d-443b-9a43-99d853612b3c 因语言标签 xsd:datetime 无法写成函数式语法而导出失败，其余项目已通过 verify。mongoexport 是从容器 webprotege-mongodb 现导出的，不是事先放好的目录。
- 审查：java-reviewer, silent-failure-hunter
