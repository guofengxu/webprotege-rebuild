---
id: P6-75
title: 访问控制：填实 CAN_COMMENT 的讨论动作（VIEW/CREATE/EDIT_OWN/SET_STATUS）
phase: P6
module: wp-app/access
type: impl
size: S
work_type: app-security
ecc_extra: []
status: todo
depends_on: [P6-44, P6-67]
---

# P6-75 讨论权限动作

## 目标
`CAN_COMMENT` 不再是空占位：拥有该角色（及更高角色）的用户具备 BS-10 / BS-19 规定的四类讨论动作。

## 参考
- BS-10、BS-19；`docs/01-rea/discussions.md` §1
- 旧出处：`BuiltInAction` / `BuiltInRole.OBJECT_COMMENTER`、`CAN_COMMENT`
- EPIC「讨论（P6-44）」决定第 1、9 条
- 现有 `Action.java`、`ProjectRole.java`、`BS10_AccessServiceTest`

## 产出
- `Action` 增加项目作用域动作：`VIEW_OBJECT_COMMENT`、`CREATE_OBJECT_COMMENT`、`EDIT_OWN_OBJECT_COMMENT`、`SET_OBJECT_COMMENT_STATUS`。
- `ProjectRole.CAN_COMMENT` 纳入上述四类（`CAN_EDIT` / `CAN_MANAGE` 经继承自动获得）。
- 更新 `BS10_AccessServiceTest`（及任何枚举穷尽测试）断言角色包含关系。
- **不**增加 `EDIT_ANY_OBJECT_COMMENT` 或 Issue 相关动作。

## 实现要点
- 依赖 P6-67 **只为**与 `EDIT_FORMS` 串行修改同一 `Action` / `ProjectRole` 文件；若 P6-67 已合并可立即开工。
- 作者校验在命令处理器（P6-76），本卡只声明动作与角色映射。

## ECC 技能
工作项类型：`app-security` —— 认证、授权、令牌与其它安全相关接口。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-75` 查看。

- 开工前加载的 skill：`springboot-security`、`security-review`、`springboot-tdd`、`java-coding-standards`
- 提交前调用的审查 agent：`security-reviewer`、`java-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='BS10_AccessServiceTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 讨论 REST、Handler 内作者校验、OpenAPI
