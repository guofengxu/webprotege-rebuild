---
id: P6-02
title: 后端：按用户 ID 或显示名称查找用户，供成员管理添加成员
phase: P6
module: wp-app, packages/api-types
type: impl
size: S
work_type: app-security
ecc_extra: []
status: done
depends_on: [P6-01]
---

# P6-02 查找候选成员（后端）

## 目标
有共享权限的用户在添加成员时，可以按用户 ID 前缀或显示名称查找用户，不必事先知道准确的用户 ID。对应 M4 差距清单第 2 项。

## 参考
- `docs/03-plan/m4-acceptance.md`「差距清单」第 2 项
- P3-22：查看与修改成员需要 `EDIT_SHARING_SETTINGS`（`project/GetProjectMembersHandler.java`）
- `access/UserService.java`、`access/JdbcUserRepository.java`；`V2__core_schema.sql` 中的 `users` 表（`user_id`、`display_name`、`email` …）
- `web/ProjectSettingsController.java`（成员接口 `listProjectMembers` / `putProjectMember` / `deleteProjectMember` 所在的控制器）
- P3-23 / P4-18：`OpenApiContractTest`、`OpenApiResponseSchemasTest`

## 产出
- `access/UserSummary.java`：`public record UserSummary(UserId userId, String displayName)`。
- `access/UserService.java`：新增 `public List<UserSummary> search(String query, int limit)`。
- `access/JdbcUserRepository.java`：新增 `search(String query, int limit)`。匹配条件为 `user_id ilike <q>%` 或 `display_name ilike %<q>%`，其中 `%`、`_`、`\` 先转义，并写明 `escape '\'`。排序：`user_id` 与查询词相同（忽略大小写）的排最前，其次是 `user_id` 前缀匹配，最后是只有显示名称匹配的；同一组内按 `user_id` 升序。最多返回 `limit` 条。
- `project/SearchMemberCandidates.java`：`record SearchMemberCandidates(ProjectId projectId, String query, int limit) implements Command<List<UserSummary>>`。紧凑构造器校验：`query` 去掉首尾空白后为 2 ~ 64 个字符，`limit` 为 1 ~ 20。
- `project/SearchMemberCandidatesHandler.java`：`requiredAction()` 为 `EDIT_SHARING_SETTINGS`，与查看成员一致。
- `web/ProjectSettingsController.java`：`GET /api/projects/{id}/member-candidates?q=<查询词>&limit=10`，operationId `searchMemberCandidates`，响应体为 `web/UserSummaryResponse.java` 的数组：`[{userId, displayName}]`。
- `docs/api/openapi.json` 更新快照；`OpenApiResponseSchemasTest` 增加 `searchMemberCandidates` 的 200 响应；`pnpm -C frontend gen` 重新生成 `frontend/packages/api-types/src/schema.d.ts`。
- 测试 `web/MemberCandidatesTest.java`（Testcontainers）：
  - 按 `user_id` 前缀命中，按显示名称子串命中（包括中文；大小写不敏感）；
  - 排序符合上面的三组规则；
  - `%` 与 `_` 按普通字符匹配：查询 `a_b` 不会命中 `axb`；
  - `limit` 生效；`limit` 大于 20、`q` 去掉首尾空白后少于 2 个字符或超过 64 个字符时返回 400；
  - 响应中每个元素的键集合恰好是 `userId`、`displayName`，不含 `email`、`appRoles`、`passwordResetRequired`；
  - 只有 `CAN_EDIT` 的成员和非成员得到 403，未登录得到 401；`CAN_MANAGE` 成员与系统管理员可以查询。

## 实现要点
- 不返回邮箱：邮箱只有管理员在用户管理页可以看到（P3-25）。
- 用户目录的暴露是**接受的风险**：任何项目创建者都可以新建一个空项目，成为它的 `CAN_MANAGE`，再用两个字符的前缀分批列出用户 ID 与显示名称。旧 WebProtégé 对所有登录用户提供用户 ID 补全，这里的范围更小（只对有共享权限的人开放，且不返回邮箱）。需要更严格的限制时另开卡，不在本卡中加限流。
- 结果中不排除已经是成员的用户，由 Web 端用成员列表来标注（P6-07）。
- 用户表规模小，不加索引，也不引入 `pg_trgm`。将来需要时另开卡。
- 日志中不要记录查询词。
- 依赖 P6-01 只是为了串行修改生成物 `docs/api/openapi.json`、`frontend/packages/api-types/src/schema.d.ts` 和 `OpenApiResponseSchemasTest`，避免并行卡改同一个文件。本卡不使用 P6-01 的代码。

## ECC 技能
工作项类型：`app-security` —— 认证、授权、令牌与其它安全相关接口。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-02` 查看。

- 开工前加载的 skill：`springboot-security`、`security-review`、`springboot-tdd`、`java-coding-standards`
- 提交前调用的审查 agent：`security-reviewer`、`java-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='MemberCandidatesTest,SharingTest,OpenApiContractTest,OpenApiResponseSchemasTest,ModularityTest'
pnpm -C frontend install
pnpm -C frontend --filter @wp/api-types run check
pnpm -C frontend --filter wp-web run typecheck
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写

## 不在本卡范围
- Web 端成员页的查找输入框（P6-07）
- 按邮箱查找、邀请链接、邀请邮件
- 系统级用户列表的搜索（`/api/admin/users`，P3-25）

## 执行记录
- 日期：2026-11-13
- 分支：feature/p6-02-member-candidate-search
- 测试：2026-11-13T21:08:59+08:00 通过（6 项）
- 实际改动：Added GET /api/projects/{id}/member-candidates with EDIT_SHARING_SETTINGS, UserSummary search (ILIKE escape + ranking), OpenAPI/api-types regen, and MemberCandidatesTest.
- 偏差：无
- 审查：security-reviewer, (PASS);, java-reviewer, (PASS;, MEDIUM:, blank, query, rejected, in, UserService.search)
