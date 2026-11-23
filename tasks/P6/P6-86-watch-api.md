---
id: P6-86
title: 关注 REST：按实体 get/set、列出当前用户关注（OpenAPI）
phase: P6
module: wp-app/watch
type: impl
size: M
work_type: app-service
ecc_extra: []
status: todo
depends_on: [P6-45, P6-84, P6-85, P6-77]
---

# P6-86 关注 REST API

## 目标
登录用户可查询/设置自己对某实体的关注（无 / ENTITY / BRANCH），并可列出自己在本项目的全部关注；命令总线校验 `WATCH_CHANGES`。

## 参考
- BS-21；旧 `GetWatches` / `SetEntityWatches`（客户端主路径）
- EPIC「关注（P6-45）」；OpenAPI 串行接在 P6-77 之后

## 产出
- 命令与 Handler（`watch` 模块）：获取某实体关注、设置某实体关注（空=清除）、列出当前用户关注。
- `web` Controller：REST 路径挂到项目下；只操作**当前用户**的关注（忽略伪造他人 userId）。
- 持久化成功后发 Spring 域事件（供 P6-87 翻译为 `ProjectEvent`）；本卡**不**修改密封 `ProjectEvent`（由 P6-87 负责）。
- 更新 `docs/api/openapi.json`、`@wp/api-types`、`OpenApiResponseSchemasTest`。
- 测试：无权限 403；设置 BRANCH 后读取正确；清除后为空；不能改他人关注；**匿名个体**作为目标 → 4xx 拒绝。

## 实现要点
- **新系统修正**：所有写接口声明 `WATCH_CHANGES`（旧 `SetEntityWatches` 未声明）。
- API 只接受命名实体（类/属性/命名个体等）；**拒绝匿名个体**（BS-21）。
- 依赖 P6-77 **只为** OpenAPI 串行。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-86` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='WatchApiTest,OpenApiResponseSchemasTest,ModularityTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- SSE 翻译、邮件匹配、Web UI、迁移
