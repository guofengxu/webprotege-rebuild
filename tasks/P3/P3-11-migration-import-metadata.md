---
id: P3-11
title: 导入迁移包：用户、角色、前缀与设置
phase: P3
module: wp-app/migration
type: impl
size: S
work_type: app-service
ecc_extra: [security]
status: done
depends_on: [P3-10, P3-06]
---

# P3-11 导入迁移包：用户、角色、前缀与设置

## 目标
迁移后的项目在权限、前缀和新实体规则上与旧系统一致。

## 参考
- `docs/02-architecture/interchange-format.md` 第 2、3 节
- `docs/01-rea/legacy-data.md` 第 3 节（密码不迁移）
- 旧新实体设置：`../webprotege/webprotege-shared/src/main/java/edu/stanford/bmir/protege/web/shared/crud/`（`EntityCrudKitSettings` 及各 kit 的设置类）

## 产出
- 导入 `users.json`：已存在的用户跳过，新用户 `password_hash` 为空。
- `manifest.roleAssignments` → `role_assignments`；`details` → `projects` 行。
- `prefixes` → `project_settings(key='prefixes')`。
- `wprebuild.app.migration.LegacySettingsConverter`：把 `entityCrudKitSettings.raw` 转为 `NewEntitySettings` 的 JSON（UUID / Supplied name / OBO 三种，字段对照旧设置类），把 `displayNameSettings.raw` 转为 `List<DisplayNameSetting>` 的 JSON；无法识别时使用默认值，并在迁移摘要中给出警告。
- 测试 `MigrationMetadataTest`：样例包导入后，角色、前缀、设置都正确；未知的 kit 类型产生警告并回落到 UUID。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口；附加：`security`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P3-11` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`、`security-review`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`、`security-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=MigrationMetadataTest
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写

## 不在本卡范围
- 表单、标签、关注等 P6 数据

## 执行记录
- 日期：2026-11-09
- 分支：feature/p3-11-migration-import-metadata
- 测试：2026-11-09T14:41:54+08:00 通过（3 项）
- 实际改动：导入 users.json、角色、前缀和旧的新实体与显示名称设置。已有用户保持不变，新用户密码哈希为空；无法识别的 kit 回落到 UUID，并写入迁移摘要。
- 偏差：生产代码约 900 行，超过 S 号卡 200 行预期，因为用户、角色、前缀和三种 kit 的转换都在本卡产出里。project_settings 的 new-entity 与 display-names 使用显式 JSON（kit.type 为 UUID、SUPPLIED_NAME 或 OBO，IRI 和本体 key 写成字符串），不是内核 record 的默认 Jackson 形态，因为内核不能依赖 Jackson。P3-22 需要按这个形状读取。
- 审查：java-reviewer, code-reviewer, security-reviewer
