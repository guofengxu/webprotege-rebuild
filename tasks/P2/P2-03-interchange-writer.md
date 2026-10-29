---
id: P2-03
title: 写出迁移包：manifest、revisions.jsonl、SHA256SUMS
phase: P2
module: wp-legacy-migrator
type: impl
size: M
work_type: migrator
ecc_extra: []
status: todo
depends_on: [P2-02]
---

# P2-03 写出迁移包：manifest、revisions.jsonl、SHA256SUMS

## 目标
旧项目的修订历史可以导出为与 OWL API 版本无关的迁移包。

## 参考
- `docs/02-architecture/interchange-format.md` 第 1、3、4、5 节（IC-1 ~ IC-6）
- ADR-011（单行格式）

## 产出
- `wprebuild.migrator.export.OntologyKeyAssigner`：按 `OWLOntologyID` 首次出现的顺序分配 `o1`、`o2`……；匿名本体各自单独分配。
- `wprebuild.migrator.export.OfnLineRenderer`：用 OWL API 4 把一条变更渲染为 `<op>\t<kind>\t<key>\t<ofn>`；公理使用完整 IRI 的函数式语法（字面量之外的换行替换为空格，规则与 P1-06 相同）；`ANNOTATION`、`IMPORT` 的写法见格式规范第 4 节。
- `wprebuild.migrator.export.ProjectExporter`：输出 `revisions.jsonl` 和 `manifest.json`（本卡只填 `formatVersion`、`projectId`、`exportedAt`、`source`、`ontologies`、`stats`；`details` 等字段在 P2-04 补齐），最后写 `SHA256SUMS`。
- `export --data-dir <dir> --out <dir> [--project <id>]` 子命令。
- 测试 `ProjectExporterTest`：用 `LegacyLogWriter` 造一个项目（2 个本体，其中 1 个匿名；含公理、注解、导入的增删），导出后逐条校验 IC-2 ~ IC-6；用 OWL API 4 解析 `revisions.jsonl` 中的每个 `ofn`。
- 黄金文件测试：固定输入导出的 `revisions.jsonl` 与 `src/test/resources/export/golden-revisions.jsonl` 完全一致（`exportedAt` 用固定时钟）。

## 实现要点
- 变更行的**结构**（`op`、`kind`、key、制表符分隔、`ANNOTATION` / `IMPORT` 的写法）必须与内核 `OfnLines`（P1-06）完全一致；`ofn` 部分只要求 OWL API 5 能解析出结构相同的对象，不要求与 OWL API 5 的渲染结果逐字相同。两边不共享代码，如果 P1-06 已完成，在执行记录中写明你用它的 `decodeLine` 对照解析过本卡的黄金文件。
- `revisions.jsonl` 里的变更行以 JSON 字符串保存，**不做** ADR-011 的行转义；`head.ofn-lines.gz`（P2-05）则要做行转义。

## ECC 技能
工作项类型：`migrator` —— 旧数据迁移工具（wp-legacy-migrator，OWL API 4）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P2-03` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`
- 提交前调用的审查 agent：`java-reviewer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl wp-legacy-migrator test -Dtest=ProjectExporterTest
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- Mongo 元数据（P2-04）
- head 基准文件（P2-05）
