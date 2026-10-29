---
id: P3-17
title: 对象 / 数据 / 注解属性 Frame 的读取与更新
phase: P3
module: wp-app/ontology, web
type: impl
size: M
work_type: app-service
ecc_extra: [rea]
status: todo
depends_on: [P3-16]
---

# P3-17 对象 / 数据 / 注解属性 Frame 的读取与更新

## 目标
三种属性都能像类一样查看和编辑。

## 参考
- 旧实现：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/frame/GetObjectPropertyFrameActionHandler.java`、`GetDataPropertyFrameActionHandler.java`、`GetAnnotationPropertyFrameActionHandler.java` 及对应的 Update 处理器
- 旧 DTO：`../webprotege/webprotege-shared/src/main/java/edu/stanford/bmir/protege/web/shared/frame/ObjectPropertyFrame.java`、`DataPropertyFrame.java`、`AnnotationPropertyFrame.java`

## 产出
- `ObjectPropertyFrame`：注解、父属性、定义域（类）、值域（类）、特征（7 种）、逆属性。`DataPropertyFrame`：注解、父属性、定义域、值域（数据类型）、是否函数型。`AnnotationPropertyFrame`：注解、父属性、定义域（IRI）、值域（IRI）。
- 每种 Frame 都有读取命令、`toAxioms` 和与 P3-16 相同模式的更新生成器（`from` / `to` + `FRAME_CHANGED` 冲突检查）；把 P3-16 中可复用的冲突检查与差异计算提取为 `AbstractFrameUpdateGenerator<F>`。
- REST：`GET/PUT /api/projects/{id}/frames/{object-property|data-property|annotation-property}`。
- 测试 `PropertyFramesTest`：三种属性各自读取、修改一项、冲突检测。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口；附加：`rea`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P3-17` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`、`code-tour`
- 开工时可用的辅助 agent：`spec-miner`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。

## 验收
```bash
./mvnw -q -pl wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=PropertyFramesTest
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 属性链、不相交属性等高级公理的编辑（只在 `otherAxioms` 中只读显示）
