---
id: P6-71
title: 迁移工具：从 Mongo 导出 Forms 与 FormSelectors 到迁移包
phase: P6
module: wp-legacy-migrator
type: impl
size: M
work_type: migrator
ecc_extra: [rea]
status: todo
depends_on: [P6-66]
---

# P6-71 表单元数据导出

## 目标
`wp-legacy-migrator` 从旧 Mongo 导出每个项目的表单描述符与选择器；`interchange-format.md` 记载生产者侧格式（不升 `formatVersion`）。

## 参考
- `docs/01-rea/forms.md` §4；`legacy-data.md`；BS-16、BS-17（选择器警告）
- `docs/02-architecture/interchange-format.md`（本卡扩展，P6-72 消费）
- 旧集合：`Forms`、`FormSelectors`
- EPIC 决定第 5 条

## 产出
- 每个 `projects/<projectId>/` **始终**写入（无数据则为空文件）：
  - `forms.jsonl`：每行 `{ "formId", "ordinal", "formDescriptor": {…} }`，按 ordinal 升序；
  - `form-selectors.jsonl`：每行 `{ "formId", "criteria": {…} }`。
- `SHA256SUMS` 纳入上述文件；`migration-report.json` 记录条数，并对 criteria 含非 `EntityTypeIsOneOf`/`SubClassOf` 节点的选择器打 **warning**（说明首期运行时不会命中）。
- 更新 `interchange-format.md`：目录、字段、**不升 formatVersion**、IC 增补（formId UUID、ordinal、selector 的 formId 应出现在 forms、SHA256 覆盖）；旧包无此二文件时由 P6-72 兼容。
- 测试：fixture 导出内容与警告断言。

## 实现要点
- 只读旧库；descriptor JSON 尽量原样保留多态 `type`。
- **不**导出表单值；不实现导入。

## ECC 技能
工作项类型：`migrator` —— 旧数据迁移工具（wp-legacy-migrator，OWL API 4）；附加：`rea`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-71` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`、`code-tour`
- 开工时可用的辅助 agent：`spec-miner`
- 提交前调用的审查 agent：`java-reviewer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 注意：spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-legacy-migrator test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='FormsExportTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- wp-app 导入、运行时匹配、Web
