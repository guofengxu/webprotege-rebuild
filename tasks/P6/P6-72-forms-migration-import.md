---
id: P6-72
title: 后端：导入迁移包中的表单描述符与选择器
phase: P6
module: wp-app/migration
type: impl
size: M
work_type: app-service
ecc_extra: []
status: todo
depends_on: [P6-66, P6-71]
---

# P6-72 表单元数据导入

## 目标
导入迁移包时，把 `forms.jsonl` / `form-selectors.jsonl` 写入表单表，保留 formId 与 ordinal；缺文件的旧包仍可导入；选择器警告写入导入报告。

## 参考
- BS-16；P6-71 的 interchange 约定；P6-66 仓储
- 现有 `migration` 模块导入流程（P3-10）
- EPIC：`migration`→`form` 依赖已由 P6-66 登记

## 产出
- 迁移导入管线：若存在表单二文件则校验并写入（先清后写或等价，使库与包一致）；**两文件皆缺** → 跳过表单步骤（兼容旧包）；仅缺其一 → 失败。
- 选择器 `formId` 不在 forms 中 → **警告并跳过**该选择器，记入报告。
- 再扫描 criteria：含未支持节点 → 警告（与 P6-71 同语义）。
- `interchange-format.md` 补充消费者行为。
- 测试：含表单包导入后仓储可读；无表单文件的旧包仍成功；损坏 JSON 失败；孤儿选择器警告。

## 实现要点
- 系统迁移路径，不经用户 `EDIT_FORMS`。
- 通过 `form` 模块仓储写入；不改 `ModularityTest`（P6-66 已加边）。
- 不跑 MatchingEngine；不改修订日志。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-72` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='FormsMigrationImportTest,ModularityTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 导出工具、实体表单运行时、Web 设计器
