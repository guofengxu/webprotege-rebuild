---
id: P3-15
title: 类 Frame 读取
phase: P3
module: wp-app/ontology, web
type: impl
size: M
work_type: app-service
ecc_extra: [rea]
status: done
depends_on: [P3-13]
---

# P3-15 类 Frame 读取

## 目标
前端能拿到一个类的完整编辑视图。

## 参考
- 旧实现：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/frame/GetClassFrameActionHandler.java`、`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/frame/translator/`（类相关的 translator）
- 旧 DTO：`../webprotege/webprotege-shared/src/main/java/edu/stanford/bmir/protege/web/shared/frame/ClassFrame.java`、`PlainClassFrame.java`

## 产出
- `wprebuild.app.ontology.frame.ClassFrame`：`record ClassFrame(EntityNode subject, List<AnnotationValue> annotations, List<EntityNode> parents, List<PropertyValue> propertyValues, List<String> otherAxioms)`。
- `AnnotationValue`：`record AnnotationValue(EntityNode property, ValueNode value, Optional<String> lang)`；`ValueNode`：`sealed`，实现为 `IriValue(EntityNode)` 和 `LiteralValue(String lexical, String datatype, Optional<String> lang)`。
- `PropertyValue`：`record PropertyValue(EntityNode property, PropertyValueKind kind, ValueNode filler)`；`PropertyValueKind`：`SOME`（`SubClassOf(C ObjectSomeValuesFrom(p D))`）、`HAS_VALUE`（`ObjectHasValue` / `DataHasValue`）、`DATA_SOME`（`DataSomeValuesFrom(p 数据类型)`）。MVP 只支持这三种，其它以该类为主语的公理（复杂父类表达式、不相交类等）放进 `otherAxioms`，用 Manchester 语法渲染为只读文本。
- 命令 `GetClassFrame(ProjectId, IRI)`（需要 `VIEW_PROJECT`）；`GET /api/projects/{id}/frames/class?iri=<iri>`。
- 测试 `ClassFrameReadTest`：在测试本体中构造三种属性值、多语言注解、两个父类和一条不支持的公理，逐项断言。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口；附加：`rea`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P3-15` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`、`code-tour`
- 开工时可用的辅助 agent：`spec-miner`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ClassFrameReadTest
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写

## 不在本卡范围
- 编辑（P3-16）

## 执行记录
- 日期：2026-11-09
- 分支：feature/p3-15-class-frame-read
- 测试：2026-11-09T21:30:44+08:00 通过（3 项）
- 实际改动：新增类 Frame 读取：注解、命名父类、三种限制，以及其余以该类为主语的公理的 Manchester 文本。
- 偏差：GetClassFrame 放在 ontology 根包，因为 Spring Modulith 只公开本体模块根包，web 不能依赖 frame 子包。ClassFrame 等类型仍在 frame 包。测试同时断言了 ObjectHasValue 和 DataHasValue。
- 审查：java-reviewer, code-reviewer
