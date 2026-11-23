---
id: P6-76
title: 后端：实体讨论 REST（线程与评论 CRUD、域事件）
phase: P6
module: wp-app/discussion
type: impl
size: M
work_type: app-service
ecc_extra: [security, rea]
status: todo
depends_on: [P6-74, P6-75, P6-73, P6-82]
---

# P6-76 讨论 REST API

## 目标
项目可通过 REST 管理实体讨论线程与评论；写操作在持久化后发布 Spring 域事件（供 P6-77 转成 SSE）。渲染与消毒复用 P6-82。

## 参考
- BS-19；`docs/01-rea/discussions.md` §2–3
- EPIC「讨论（P6-44）」决定第 1–5、9、10 条
- 旧 Handler：`Create*` / `Add*` / `Edit*` / `Delete*` / `Set*Status` / `Get*`
- OpenAPI 串行：接在 P6-73 之后；渲染器：P6-82

## 产出
- 命令 + Handler（在 `wprebuild.app.discussion`，经总线，带 BS-19 权限）：按实体取线程、创建线程、追加评论、编辑自己的评论、删除自己的评论、设置状态、有评论实体分页列表（列表也必须 `VIEW_OBJECT_COMMENT`）。写入时调用 P6-82 `CommentRenderer` 填 `rendered_body`。向 CLOSED 线程追加不自动重开；空线程策略在测试中钉死。
- **删除**与编辑相同：`requiredAction = EDIT_OWN_OBJECT_COMMENT`，且 Handler 内校验 `createdBy == 当前用户`（旧删除无 BuiltInAction，新系统统一到该动作）。
- REST **Controller 放在 `wprebuild.app.web`**（对齐表单：Handler 在 discussion，Controller 在 web）；更新 `docs/api/openapi.json`；再生 `@wp/api-types` 中与讨论 REST 相关的类型（**不含** ProjectEvent 联合扩展，留给 P6-77）。
- 域事件记录（在 `discussion` 包，须对 `events` 模块可见）：线程创建、评论发布/更新/删除、状态变更；Handler 在事务提交后 `ApplicationEventPublisher` 发布。
- 测试：权限拒绝；非作者编辑/删除 403；CRUD 往返；域事件被发布（可用同步监听器断言）。

## 实现要点
- 依赖 P6-73 **只为** OpenAPI / api-types 串行；依赖 P6-82 取渲染器（本卡**不再**新增 CommonMark / 消毒库）。
- 本卡不把域事件译成 `ProjectEvent`、不改 Web `applyProjectEvent`、不改 `ProjectEventHub.takeWaiters`。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口；附加：`security`、`rea`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-76` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`、`security-review`、`code-tour`
- 开工时可用的辅助 agent：`spec-miner`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`、`security-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 注意：spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='DiscussionApiTest,OpenApiResponseSchemasTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- CommentRenderer / POM 渲染依赖（P6-82）、SSE `ProjectEvent` 扩展、Web 面板、邮件、合并转移、迁移
