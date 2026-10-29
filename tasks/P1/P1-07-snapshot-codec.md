---
id: P1-07
title: 快照编码 ofn-zip/1
phase: P1
module: wp-kernel
type: impl
size: M
work_type: kernel
ecc_extra: []
status: todo
depends_on: [P1-04]
---

# P1-07 快照编码 ofn-zip/1

## 目标
整个项目的内存本体可以编码为一个快照，冷启动时直接加载，不必重放全部历史。

## 参考
- ADR-011（快照部分）
- `docs/01-rea/README.md` 第 7 节第 3 条（旧系统没有快照）

## 产出
- `wprebuild.kernel.codec.SnapshotCodec`：`String id()`；`byte[] encode(ProjectOntologies)`；`MutableProjectOntologies decode(byte[])`。
- `wprebuild.kernel.codec.OfnZipSnapshotCodec implements SnapshotCodec`，`id()` 为 `ofn-zip/1`。zip 内容：`registry.tsv`（每行 `key\tontologyIri\tversionIri`，缺省写 `-`，按 key 编号排序）；每个本体一份 `<key>.ofn`，即完整的函数式语法文档，包含公理、本体注解和导入声明。
- 测试 `OfnZipSnapshotCodecTest`：语料 + 生成器生成的 1 万条公理的本体，编码再解码后每个 key 的公理、注解、导入都结构相等；匿名本体往返后仍然匿名，key 不变。

## 实现要点
- 解码时同样**不得访问网络**：设置 `MissingImportHandlingStrategy.SILENT`，并沿用 P1-04 中“导入不可解析 IRI 时 1 秒内完成”的测试方法。
- `.ofn` 文件中本体头部的 IRI 来自 `registry.tsv`，不要依赖 OWL API 写出的本体 ID 来恢复 key。

## ECC 技能
工作项类型：`kernel` —— 内核领域代码（wp-kernel，纯 Java + OWL API 5）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P1-07` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`、`hexagonal-architecture`
- 提交前调用的审查 agent：`java-reviewer`、`type-design-analyzer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl wp-kernel test -Dtest=OfnZipSnapshotCodecTest
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 何时生成快照（P3-03）
