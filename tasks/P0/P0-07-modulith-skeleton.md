---
id: P0-07
title: 声明 wp-app 的 Modulith 模块与依赖规则
phase: P0
module: wp-app
type: impl
size: S
work_type: app-service
ecc_extra: []
status: done
depends_on: [P0-04]
---

# P0-07 声明 wp-app 的 Modulith 模块与依赖规则

## 目标
wp-app 的 10 个应用模块都已声明，模块边界由测试强制执行。

## 参考
- ADR-001
- `docs/02-architecture/overview.md` 第 2 节（模块与允许依赖表）

## 产出
- 在 `wprebuild.app` 下创建子包：`shared`、`access`、`bus`、`project`、`revision`、`ontology`、`search`、`events`、`migration`、`web`。
- 每个子包一个 `package-info.java`，标注 `@org.springframework.modulith.ApplicationModule(displayName = ..., allowedDependencies = {...})`，取值严格按 overview 第 2 节的表；`shared` 使用 `type = ApplicationModule.Type.OPEN`。
- `src/test/java/wprebuild/app/ModularityTest.java`：`ApplicationModules.of(WpApplication.class).verify()`；另一个测试方法用 `Documenter` 把模块图输出到 `target/spring-modulith-docs`。

## 实现要点
- 做一次反向验证：在 `revision` 包里临时引用 `ontology` 包的一个类，确认 `ModularityTest` 失败，然后撤销。在执行记录里写明。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P0-07` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dtest=ModularityTest -Dsurefire.failIfNoSpecifiedTests=false
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录中有反向验证的结果
- [x] 执行记录已填写

## 不在本卡范围
- 任何业务类

## 执行记录
- 日期：2026-11-02
- 分支：feature/p0-07-modulith-skeleton
- 测试：2026-11-02T13:36:07+08:00 通过（3 项）
- 实际改动：在 wprebuild.app 下声明 shared、access、bus、project、revision、ontology、search、events、migration、web 十个 Spring Modulith 模块。shared 为 OPEN 且 allowedDependencies 为空数组，其余允许依赖按 overview 第 2 节，web 依赖其余全部模块。ModularityTest 核对注解上的 displayName、type 与 allowedDependencies 并调用 verify()；Documenter 把模块图写到 target/spring-modulith-docs/components.puml。反向验证：revision 临时引用 ontology.OntologyMarker 后 verify() 失败，信息为 Module 'revision' depends on module 'ontology' via RevisionBoundaryProbe -> OntologyMarker，Allowed targets: shared。两个临时类已删除，测试恢复通过。审查指出解析后的空依赖流无法区分显式空列表和默认开放标记，已改为断言注解数组本身。
- 偏差：displayName 使用表中的模块名，没有把「职责」列整段写入注解。职责文本含标点时，Documenter 会生成无法阅读的组件 id。
- 审查：java-reviewer, code-reviewer
