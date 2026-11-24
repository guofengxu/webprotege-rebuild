---
id: P6-85
title: 访问控制：CAN_VIEW 增加 WATCH_CHANGES
phase: P6
module: wp-app/access
type: impl
size: S
work_type: app-security
ecc_extra: []
status: todo
depends_on: [P6-45, P6-75, P6-77]
---

# P6-85 关注权限动作

## 目标
拥有 `CAN_VIEW`（及更高角色）的用户具备 `WATCH_CHANGES`，可读写实体关注。

## 参考
- BS-10、BS-21；旧 `BuiltInRole.PROJECT_VIEWER` 含 `WATCH_CHANGES`
- EPIC「关注（P6-45）」决定第 8 条
- 现有 `Action.java`、`ProjectRole.java`、`BS10_AccessServiceTest`

## 产出
- `Action` 增加项目作用域动作 `WATCH_CHANGES`。
- `ProjectRole.CAN_VIEW` 纳入该动作（更高角色经继承获得）。
- 更新 `BS10_AccessServiceTest`（及枚举穷尽测试）。
- `docs/api/openapi.json` 与 `@wp/api-types`：`GET /api/projects/{id}/permissions/me` 响应的 `actions` 枚举已发布，增加 `WATCH_CHANGES`；更新 `OpenApiResponseSchemasTest`。

## 实现要点
- 依赖 P6-75 **只为**与讨论权限串行修改同一 `Action` / `ProjectRole` 文件。
- 依赖 P6-77 **只为** OpenAPI 串行（`… → P6-77 → P6-85 → P6-86`）。

## ECC 技能
工作项类型：`app-security` —— 认证、授权、令牌与其它安全相关接口。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-85` 查看。

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
- 关注 REST、Handler
