---
id: P6-93
title: 访问控制：CAN_MANAGE 增加 EDIT_PROJECT_TAGS，CAN_COMMENT 增加 EDIT_ENTITY_TAGS
phase: P6
module: wp-app/access
type: impl
size: S
work_type: app-security
ecc_extra: []
status: todo
depends_on: [P6-46, P6-85, P6-86]
---

# P6-93 标签权限动作

## 目标
拥有 `CAN_MANAGE` 的用户具备 `EDIT_PROJECT_TAGS`；拥有 `CAN_COMMENT`（及更高）的用户具备 `EDIT_ENTITY_TAGS`。

## 参考
- BS-10、BS-23；旧 `BuiltInRole.PROJECT_MANAGER` / `OBJECT_COMMENTER`
- EPIC「标签（P6-46）」决定第 5 条
- 现有 `Action.java`、`ProjectRole.java`、`BS10_AccessServiceTest`

## 产出
- `Action` 增加项目作用域动作 `EDIT_PROJECT_TAGS`、`EDIT_ENTITY_TAGS`。
- `ProjectRole.CAN_MANAGE` 纳入 `EDIT_PROJECT_TAGS`；`ProjectRole.CAN_COMMENT` 纳入 `EDIT_ENTITY_TAGS`（更高角色经继承获得）。
- 更新 `BS10_AccessServiceTest`（及枚举穷尽测试）。
- `docs/api/openapi.json` 与 `@wp/api-types`：`permissions/me` 响应的 `actions` 枚举增加两个动作；更新 `OpenApiResponseSchemasTest`。

## 实现要点
- 依赖 P6-85 **只为**与关注权限串行修改同一 `Action` / `ProjectRole` 文件。
- 依赖 P6-86 **只为** OpenAPI 串行（`… → P6-86 → P6-93 → P6-94`；2026-11-24 关注审查修订时发现动作枚举已发布）。

## ECC 技能
工作项类型：`app-security` —— 认证、授权、令牌与其它安全相关接口。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-93` 查看。

- 开工前加载的 skill：`springboot-security`、`security-review`、`springboot-tdd`、`java-coding-standards`
- 提交前调用的审查 agent：`security-reviewer`、`java-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='BS10_AccessServiceTest,OpenApiResponseSchemasTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 标签 REST、Handler
