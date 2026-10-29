---
id: P3-07
title: 命令总线与统一校验链
phase: P3
module: wp-app/bus, web
type: impl
size: M
work_type: app-service
ecc_extra: []
status: todo
depends_on: [P3-06]
---

# P3-07 命令总线与统一校验链

## 目标
所有业务操作都经过同一个入口，权限和项目状态在入口统一校验。

## 参考
- ADR-007
- 旧实现：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/dispatch/impl/DispatchServiceExecutorImpl.java`、`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/dispatch/validators/`

## 产出
- `wprebuild.app.bus.Command<R>`：标记接口（查询也用它，按约定用 `Get*`、`List*` 命名）。
- `wprebuild.app.bus.CommandHandler<C extends Command<R>, R>`：`Class<C> commandType()`；`Optional<ProjectId> project(C)`；`Optional<Action> requiredAction(C)`（为空表示只需登录）；`default boolean allowsTrashedProject()`，默认 `false`；`R handle(C, CommandContext)`。
- `wprebuild.app.bus.CommandContext`：`record CommandContext(UserId user)`。
- `wprebuild.app.bus.ProjectExistenceCheck`（SPI 接口，由 project 模块实现，P3-08）：`ProjectState state(ProjectId)`，返回 `ACTIVE` / `TRASHED` / `MISSING`。
- `wprebuild.app.bus.CommandBus`：启动时收集全部 `CommandHandler` Bean，同一命令类型有多个处理器时启动失败；`<R> R execute(UserId, Command<R>)`：项目不存在 → `NotFoundException`；项目在回收站且处理器不允许 → `ConflictException`；权限不足 → `PermissionDeniedException`；每种命令记录一个 Micrometer timer（标签为命令类名）。
- `wprebuild.app.web.ProblemDetailsAdvice`：`NotFoundException` → 404，`PermissionDeniedException` → 403，`ConflictException` 与 `ConcurrentAppendException` → 409，参数校验失败 → 400；响应体统一为 `ProblemDetail`，带 `code` 扩展字段。
- 测试 `CommandBusTest`：用测试处理器覆盖上述每个分支；重复注册导致启动失败（用 `ApplicationContextRunner`）。
- 测试 `ProblemDetailsAdviceTest`：每种异常对应的状态码和响应体。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P3-07` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='CommandBusTest,ProblemDetailsAdviceTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 任何具体的业务命令
