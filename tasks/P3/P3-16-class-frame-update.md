---
id: P3-16
title: 类 Frame 更新（含乐观并发）
phase: P3
module: wp-app/ontology, web
type: impl
size: M
work_type: app-service
ecc_extra: [rea]
status: todo
depends_on: [P3-15, P3-14]
---

# P3-16 类 Frame 更新（含乐观并发）

## 目标
用户编辑类的标签、注解、父类和属性值后提交，系统只写入真正变化的公理。

## 参考
- 旧实现：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/frame/UpdateClassFrameActionHandler.java`、`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/frame/FrameChangeGenerator.java`、`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/frame/AbstractUpdateFrameHandler.java`

## 产出
- `wprebuild.app.ontology.frame.ClassFrameAxioms`：`Set<OWLAxiom> toAxioms(ClassFrame)`，即 P3-15 读取逻辑的逆过程（`otherAxioms` 不参与转换）。
- `wprebuild.app.ontology.frame.UpdateClassFrameGenerator implements ChangeListGenerator<ClassFrame>`：输入为客户端提交的 `from`（编辑前看到的 Frame）和 `to`（编辑后的 Frame）；先检查当前 Frame 是否仍等于 `from`，不等则抛 `ConflictException`（错误码 `FRAME_CHANGED`）；变更 = 删除 `toAxioms(from) − toAxioms(to)`，添加 `toAxioms(to) − toAxioms(from)`；描述为 `Edited class <显示名称>`。
- 命令 `UpdateClassFrame(ProjectId, ClassFrame from, ClassFrame to)`（需要 `EDIT_ONTOLOGY`）；`PUT /api/projects/{id}/frames/class`，返回更新后的 Frame 和修订号。
- 测试 `ClassFrameUpdateTest`：修改中文标签只产生 1 删 1 增；增加一个父类后层级查询可见；两个用户基于同一个 `from` 先后提交，第二个得到 409 `FRAME_CHANGED`；`otherAxioms` 中的公理在更新后仍然存在。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口；附加：`rea`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P3-16` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`、`code-tour`
- 开工时可用的辅助 agent：`spec-miner`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。

## 验收
```bash
./mvnw -q -pl wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=ClassFrameUpdateTest
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- Manchester 语法编辑（P6）
