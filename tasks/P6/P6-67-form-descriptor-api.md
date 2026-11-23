---
id: P6-67
title: 后端：EDIT_FORMS 权限与表单描述符、选择器的增删改查接口
phase: P6
module: wp-app/form
type: impl
size: M
work_type: app-security
ecc_extra: []
status: todo
depends_on: [P6-66, P6-51]
---

# P6-67 表单描述符管理 API

## 目标
项目管理者可以经 REST 管理表单描述符与选择器；所有写操作校验 `EDIT_FORMS`（CAN_MANAGE）；OpenAPI 与 `@wp/api-types` 已更新。

## 参考
- BS-10（`EDIT_FORMS`）、BS-16；`docs/01-rea/forms.md` §2.2、§3.3
- P6-66 仓储；EPIC：本卡独占 `Action` / `ProjectRole`；OpenAPI 串行接在 P6-51 之后
- 旧出处：`SetEntityFormDescriptorActionHandler`、`DeleteFormActionHandler`、`GetProjectFormDescriptorsActionHandler`、`FormsResource`

## 产出
- `Action.EDIT_FORMS`；`ProjectRole.CAN_MANAGE` 包含它；`BS10_AccessServiceTest` 断言。
- 命令与处理器（`wprebuild.app.form`，经命令总线）：列出项目表单（描述符+选择器）、获取单个、创建/更新（upsert 描述符与可选 criteria）、删除（级联选择器）、申请新 `formId`（UUID）。
- REST 控制器放在 `wprebuild.app.web`（例如 `FormDescriptorController`），调用 form 模块命令；路径建议 `/api/projects/{id}/forms`。列表与描述符读写均要求 `EDIT_FORMS`（实体运行时走 P6-69，只读成员不依赖本管理面）。
- 更新 `docs/api/openapi.json`、生成 `@wp/api-types`；`OpenApiResponseSchemasTest` 覆盖新 operationId。
- 测试：无权限 403；有权限往返；删除后 get 404；非法 JSON 形状 400。

## 实现要点
- 修正旧系统漏洞：禁止无权限的整表替换 / 更新。
- `depends_on` 含 P6-51 **只为** OpenAPI / api-types 串行，本卡不实现 OIDC。
- 无 `EDIT_FORMS` 的编辑者只使用 P6-69，不调用本卡 REST。

## ECC 技能
工作项类型：`app-security` —— 认证、授权、令牌与其它安全相关接口。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-67` 查看。

- 开工前加载的 skill：`springboot-security`、`security-review`、`springboot-tdd`、`java-coding-standards`
- 提交前调用的审查 agent：`security-reviewer`、`java-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='BS10_AccessServiceTest,FormDescriptorApiTest,OpenApiResponseSchemasTest,ModularityTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 按实体取表单 / 保存、Web 设计器、迁移
