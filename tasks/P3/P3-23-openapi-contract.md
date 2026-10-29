---
id: P3-23
title: OpenAPI 契约快照与 TypeScript 客户端生成
phase: P3
module: wp-app/web
type: impl
size: S
work_type: api-contract
ecc_extra: []
status: todo
depends_on: [P3-09, P3-10, P3-11, P3-12, P3-17, P3-18, P3-19, P3-20, P3-21, P3-22, P3-25, P3-26]
---

# P3-23 OpenAPI 契约快照与 TypeScript 客户端生成

## 目标
前端开发有一份稳定、受测试保护的 API 契约。

## 参考
- ADR-007、ADR-008

## 产出
- 依赖 `org.springdoc:springdoc-openapi-starter-webmvc-api`（取支持 Spring Boot 4.1 的最新版本）。
- 为全部控制器补齐 `@Operation` 与 `@Tag`；ProblemDetail 作为统一的错误响应模型。
- `docs/api/openapi.json`：契约快照，提交到仓库。
- 测试 `OpenApiContractTest`：启动应用后获取 `/v3/api-docs`，与 `docs/api/openapi.json` 比较（规范化 JSON 后比较）；不一致时失败，并提示运行 `./mvnw -pl wp-app -Dwp.openapi.update=true test -Dtest=OpenApiContractTest` 更新快照。
- `docs/api/README.md`：说明前端如何用 `openapi-typescript` 从快照生成类型。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`api-contract` —— API 契约（OpenAPI 快照与契约测试）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P3-23` 查看。

- 开工前加载的 skill：`contract-first`、`api-design`
- 提交前调用的审查 agent：`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=OpenApiContractTest
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 前端工程本身（P4）
