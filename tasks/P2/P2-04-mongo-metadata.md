---
id: P2-04
title: 读取 mongoexport 元数据并映射角色
phase: P2
module: wp-legacy-migrator
type: impl
size: M
work_type: migrator
ecc_extra: [rea]
status: todo
depends_on: [P2-03]
---

# P2-04 读取 mongoexport 元数据并映射角色

## 目标
迁移包带上项目元数据、用户（不含密码）、角色分配、前缀和新实体设置。

## 参考
- `docs/01-rea/legacy-data.md` 第 3 节
- BS-10（新角色）
- 旧角色定义：`../webprotege/webprotege-shared-core/src/main/java/edu/stanford/bmir/protege/web/shared/access/BuiltInRole.java`
- 旧角色分配：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/access/RoleAssignment.java`
- 旧项目元数据：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/project/ProjectDetailsRepository.java`

## 产出
- `--mongo-export-dir <dir>` 选项：目录中为 `mongoexport --jsonArray` 导出的 `ProjectDetails.json`、`Users.json`、`RoleAssignments.json`、`PrefixDeclarations.json`、`EntityCrudKitSettings.json`。缺少某个文件时给出警告，继续执行。
- `wprebuild.migrator.mongo.*Reader`：每个集合一个读取器，字段名以旧代码为准（先读上面列出的旧类，再写读取器）。
- `wprebuild.migrator.mongo.RoleMapping`：`CAN_VIEW`、`CAN_COMMENT`、`CAN_EDIT`、`CAN_MANAGE` 原样保留；`PROJECT_VIEWER → CAN_VIEW`；`OBJECT_COMMENTER`、`ISSUE_*` → `CAN_COMMENT`；`PROJECT_EDITOR → CAN_EDIT`；`PROJECT_MANAGER → CAN_MANAGE`；`LAYOUT_EDITOR`、`PROJECT_DOWNLOADER` 并入 `CAN_VIEW`；其它角色写入迁移报告的警告。一个用户有多个角色时取最高的一个。
- 补全 `manifest.json` 的 `details`、`prefixes`、`entityCrudKitSettings.raw`、`displayNameSettings.raw`（取自 `ProjectDetails.defaultDisplayNameSettings`）、`roleAssignments`；输出 `users.json`（不含盐值和摘要）。
- 测试 `MongoMetadataTest`：按旧类的字段手写的 JSON 样例；角色映射表逐行验证；未知角色产生警告；`users.json` 中没有任何与密码相关的字段。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`migrator` —— 旧数据迁移工具（wp-legacy-migrator，OWL API 4）；附加：`rea`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P2-04` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`、`code-tour`
- 开工时可用的辅助 agent：`spec-miner`
- 提交前调用的审查 agent：`java-reviewer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。

## 验收
```bash
./mvnw -q -pl wp-legacy-migrator test -Dtest=MongoMetadataTest
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 直接连接 MongoDB
- 迁移密码
