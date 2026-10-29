---
id: P2-02
title: 读取旧 BinaryOWL 修订日志
phase: P2
module: wp-legacy-migrator
type: impl
size: M
work_type: migrator
ecc_extra: [rea]
status: todo
depends_on: [P2-01]
---

# P2-02 读取旧 BinaryOWL 修订日志

## 目标
能逐个修订读出旧项目的完整历史，遇到任何错误都立即失败并给出位置。

## 参考
- `docs/01-rea/legacy-data.md` 第 1、2 节
- 旧读取逻辑：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/revision/RevisionStoreImpl.java`（`load`）
- 旧写入逻辑：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/revision/RevisionSerializationTask.java`、`RevisionSerializationVocabulary.java`

## 产出
- `wprebuild.migrator.legacy.LegacyRevision`：`record LegacyRevision(long revision, String username, long timestamp, String description, List<OWLOntologyChangeRecord> records)`。
- `wprebuild.migrator.legacy.LegacyRevisionReader`：`void read(Path changeDataBinary, Consumer<LegacyRevision> consumer)`；任何异常都包装为 `LegacyReadException`，消息中包含项目 ID、已成功读取的最后一个修订号；遇到 `SetOntologyIDData` 抛 `UnsupportedLegacyChangeException`，并带上修订号和变更序号。
- `wprebuild.migrator.legacy.LegacyDataDir`：给定 `data.directory`，列出 `data-store/project-data/` 下所有项目 ID 及其日志路径。
- `inspect --data-dir <dir>` 子命令：每个项目输出一行：项目 ID、修订数、head 修订号、变更总数、涉及的本体 ID 数。
- 测试支持类 `LegacyLogWriter`（仅测试用）：照旧系统 `RevisionSerializationTask` 的写法，用 `BinaryOWLOntologyChangeLog.appendChanges` 写出带同样元数据属性的日志。
- 测试 `LegacyRevisionReaderTest`：写出 3 个修订再读回，字段全部一致；文件截断时抛 `LegacyReadException`，并报告最后一个成功的修订号；含 `SetOntologyIDData` 时抛 `UnsupportedLegacyChangeException`。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`migrator` —— 旧数据迁移工具（wp-legacy-migrator，OWL API 4）；附加：`rea`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P2-02` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`、`code-tour`
- 开工时可用的辅助 agent：`spec-miner`
- 提交前调用的审查 agent：`java-reviewer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。

## 验收
```bash
./mvnw -q -pl wp-legacy-migrator test -Dtest=LegacyRevisionReaderTest
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 输出迁移包（P2-03）
