---
id: P3-18
title: 个体 Frame 的读取与更新
phase: P3
module: wp-app/ontology, web
type: impl
size: S
work_type: app-service
ecc_extra: [rea]
status: done
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
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=IndividualFrameTest
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写

## 不在本卡范围
- 推理得到的类型

## 执行记录
- 日期：2026-11-10
- 分支：feature/p3-18-individual-frame
- 测试：2026-11-10T10:50:28+08:00 通过（3 项）
- 实际改动：新增具名个体 Frame 的读取与更新，以及按类分页列出直接个体。编辑按 from 与 to 的公理差量写入，当前 Frame 已变时返回 409 FRAME_CHANGED。
- 偏差：生产代码约 786 行（不含测试），高于 S 卡预期的 200 行。读取、toAxioms、更新生成器、REST 和按类分页都是卡片要求的产出。三个及以上个体的 SameIndividual 只出现在 otherAxioms，不参与写入，避免差量无法删除原公理。直接个体只统计该类自身的类断言，不包含子类个体，也不把 owl:Thing 展开成未声明类型的全部个体。推理类型不读取。
- 审查：java-reviewer, code-reviewer
