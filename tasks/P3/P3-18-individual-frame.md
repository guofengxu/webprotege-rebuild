---
id: P3-18
title: 个体 Frame 的读取与更新
phase: P3
module: wp-app/ontology, web
type: impl
size: S
work_type: app-service
ecc_extra: [rea]
status: todo
depends_on: [P3-17]
---

# P3-18 个体 Frame 的读取与更新

## 目标
个体可以查看和编辑。

## 参考
- 旧实现：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/frame/GetNamedIndividualFrameActionHandler.java`、`UpdateNamedIndividualFrameHandler.java`
- 旧 DTO：`../webprotege/webprotege-shared/src/main/java/edu/stanford/bmir/protege/web/shared/frame/NamedIndividualFrame.java`

## 产出
- `NamedIndividualFrame`：注解、类型（类）、对象属性断言、数据属性断言、`SameIndividual`。
- 读取命令、`toAxioms`、基于 `AbstractFrameUpdateGenerator` 的更新生成器；`GET/PUT /api/projects/{id}/frames/individual`。
- 命令 `ListIndividualsByType(ProjectId, IRI cls, int page, int size)`：分页返回某类的直接个体（`EntityNode`）；`GET /api/projects/{id}/individuals?type=<iri>&page=0&size=50`。
- 测试 `IndividualFrameTest`：读取、修改属性断言、冲突检测、分页。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口；附加：`rea`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P3-18` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`、`code-tour`
- 开工时可用的辅助 agent：`spec-miner`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。

## 验收
```bash
./mvnw -q -pl wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=IndividualFrameTest
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 推理得到的类型
