---
id: P6-99
title: 后端：从交换包导入 Tags 与 EntityTags
phase: P6
module: wp-app/migration
type: impl
size: M
work_type: app-service
ecc_extra: []
status: todo
depends_on: [P6-46, P6-92, P6-98]
---

# P6-99 导入 Tags / EntityTags

## 目标
迁移导入路径能把交换包中的 Tags / EntityTags 写入编辑器库；criteria 原样入库且不触发自动打标。

## 参考
- BS-23；`tags.md` §4；`interchange-format.md`（P6-98）；EPIC「标签（P6-46）」

## 产出
- `migration` 模块读取交换包 tags / entity-tags 文件，写入 `project_tag` / `entity_tag`（经 `tag` 仓储或本模块 JDBC，遵守 Modulith）。
- 空文件 = 无操作；坏行失败策略与现有迁移一致。
- 测试：导出样例往返导入后目录与实体显式标签一致；criteria jsonb 保留；getTags 仍仅显式。

## 实现要点
- 依赖 P6-98 的文件格式；不升 `formatVersion`。
- 不调用 MatchingEngine。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-99` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='TagsMigrationImportTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 条件自动打标、Web
