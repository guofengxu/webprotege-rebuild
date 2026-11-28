---
id: P11-02
title: 后端：/permissions/me 的动作枚举补齐 UPLOAD_AND_MERGE 两项，重新生成 OpenAPI 与 @wp/api-types，并增加守护测试
phase: P11
module: wp-app, packages/api-types
type: impl
size: S
work_type: api-contract
ecc_extra: []
status: todo
depends_on: [P11-01]
---

# P11-02 后端：权限动作枚举与契约补齐

## 目标
`GET /api/projects/{id}/permissions/me` 文档中的动作枚举与运行时可能返回的动作一致：`MyProjectPermissionsResponse` 的 `allowableValues`、`docs/api/openapi.json` 与 `@wp/api-types` 都包含 `UPLOAD_AND_MERGE`、`UPLOAD_AND_MERGE_ADDITIONS`；以后新增项目级动作时漏改，测试会失败。

## 参考
- P6-28「阻塞」：P6-27 在 `access/Action.java` 中新增了这两个动作，`/permissions/me` 运行时也会返回它们，但枚举没有更新，`useProjectPermissions('UPLOAD_AND_MERGE')` 类型对不上。
- `backend/wp-app/src/main/java/wprebuild/app/web/MyProjectPermissionsResponse.java`：`actions` 的 `@Schema(allowableValues = …)` 目前列了 18 项，止于 `MOVE_ANY_PROJECT_TO_TRASH`；`from(…)` 按 `Action.values()` 的顺序输出。
- `access/Action.java`：`projectScoped()` 标出项目级动作。
- P6-01（接口）、P6-24（`MERGE_ENTITIES` 曾同时更新枚举）、P3-23（OpenAPI 快照与 TypeScript 客户端生成）。
- 之后还会新增项目级动作的卡：P6-67、P6-75、P6-85、P6-93。

## 产出
- `MyProjectPermissionsResponse.java`：`allowableValues` 按 `Action` 声明顺序补上 `UPLOAD_AND_MERGE`、`UPLOAD_AND_MERGE_ADDITIONS`。
- 守护测试（放在现有的 `web/MyProjectPermissionsTest.java` 中）：用反射读取 `actions` 的 `allowableValues`，断言它包含 `Action.values()` 中全部 `projectScoped()` 为 `true` 的动作。
- 按 P3-23 的流程重新生成 `docs/api/openapi.json` 与 `frontend/packages/api-types` 的生成文件（`schema.d.ts`），不手工编辑生成结果。

## 实现要点
- 先写守护测试并确认它在补枚举前失败，再补枚举。
- 如果守护测试发现除这两项之外还有项目级动作不在枚举中，一并补上，并写进执行记录。
- 只改枚举与生成文件，不改权限计算、角色授予与前端代码。

## ECC 技能
工作项类型：`api-contract` —— API 契约（OpenAPI 快照与契约测试）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P11-02` 查看。

- 开工前加载的 skill：`contract-first`、`api-design`
- 提交前调用的审查 agent：`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='MyProjectPermissionsTest,OpenApiContractTest,OpenApiResponseSchemasTest'
pnpm -C frontend install
pnpm -C frontend --filter @wp/api-types run check
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- P6-28 的前端实现（本卡合并后由人恢复 P6-28）。
- 修改 P6-67、P6-75、P6-85、P6-93 的卡片内容；守护测试会在这些卡漏改枚举时失败。
