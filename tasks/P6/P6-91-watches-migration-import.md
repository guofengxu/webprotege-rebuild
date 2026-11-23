---
id: P6-91
title: 后端：导入交换包中的 Watches
phase: P6
module: wp-app/migration
type: impl
size: S
work_type: app-service
ecc_extra: [rea]
status: todo
depends_on: [P6-45, P6-84, P6-90]
---

# P6-91 导入 Watches

## 目标
导入迁移包时把 Watches 写入 `entity_watch`；重复导入幂等（按唯一键 upsert）。

## 参考
- BS-21；P6-90 文件格式；`interchange-format.md`；对齐讨论导入卡 P6-81 的模式

## 产出
- `migration` 模块读取 watches 文件并调用 `WatchRepository` upsert。
- 非法 `watchType` / 缺字段 / **匿名个体** / 无法解析的实体：记录警告、跳过该行并计数；**整文件不失败**；空文件成功。
- 更新 `interchange-format.md` 导入侧说明（若 P6-90 未写全）。
- 测试：导出样例往返；空文件成功；幂等二次导入。

## 实现要点
- 不升 `formatVersion`。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口；附加：rea。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-91` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`、`code-tour`
- 开工时可用的辅助 agent：`spec-miner`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='WatchesImportTest,ModularityTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 导出工具；邮件；Web
