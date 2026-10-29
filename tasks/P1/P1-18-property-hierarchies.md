---
id: P1-18
title: 对象 / 数据 / 注解属性层级视图（BS-08）
phase: P1
module: wp-kernel
type: impl
size: M
work_type: kernel
ecc_extra: [rea]
status: todo
depends_on: [P1-17]
---

# P1-18 对象 / 数据 / 注解属性层级视图（BS-08）

## 目标
三种属性都有与类层级行为一致的层级视图。

## 参考
- BS-08
- 旧实现：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/hierarchy/AbstractOWLPropertyHierarchyProvider.java` 及三个子类

## 产出
- `ObjectPropertyHierarchyView`（根 `owl:topObjectProperty`）、`DataPropertyHierarchyView`（根 `owl:topDataProperty`）、`AnnotationPropertyHierarchyView`（无统一根，`roots()` 返回没有父属性的注解属性）。
- 可以把与类层级共用的逻辑抽到 `AbstractHierarchyView` 中；不要修改 `HierarchyView` 接口。
- 测试 `BS08_PropertyHierarchyViewsTest`：三种属性各自的根、父子关系、增量更新与 `rebuild` 一致（属性测试）。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`kernel` —— 内核领域代码（wp-kernel，纯 Java + OWL API 5）；附加：`rea`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P1-18` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`、`hexagonal-architecture`、`code-tour`
- 开工时可用的辅助 agent：`spec-miner`
- 提交前调用的审查 agent：`java-reviewer`、`type-design-analyzer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。

## 验收
```bash
./mvnw -q -pl wp-kernel test -Dtest=BS08_PropertyHierarchyViewsTest
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 属性特征、定义域与值域的展示（P3-17）
