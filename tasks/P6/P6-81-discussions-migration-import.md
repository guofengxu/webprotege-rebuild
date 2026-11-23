---
id: P6-81
title: 后端：导入迁移包中的实体讨论线程
phase: P6
module: wp-app/migration
type: impl
size: M
work_type: app-service
ecc_extra: [security]
status: todo
depends_on: [P6-74, P6-80, P6-82]
---

# P6-81 讨论数据导入

## 目标
导入迁移包时，若存在 `discussion-threads.jsonl` 则写入讨论表；缺失文件视为无讨论（兼容旧包）。导入的 HTML **必须**经 P6-82 按 `body` 重渲并消毒（禁止原样信任旧 `renderedBody`）。

## 参考
- BS-19；`interchange-format.md`（P6-80 扩展）；EPIC 决定第 2、7、10 条
- 表单导入卡 P6-72 的模式可作对照；渲染器 P6-82

## 产出
- `migration` 模块读取 `discussion-threads.jsonl`，经 `discussion` 仓储插入线程与评论（保持 id / 时间 / 作者 / `body`）。
- **`rendered_body` 一律**用 P6-82 对 `body` 重新渲染并消毒后写入；忽略源文件中的旧 HTML（旧库未消毒）。
- 旧包无该文件 → 跳过且不失败。
- 更新 `interchange-format.md` 消费者侧说明（接在 P6-80 之后）。
- 测试：有文件导入往返（断言 `rendered_body` 不含脚本样例）；无文件导入成功。

## 实现要点
- 不升 `formatVersion`。
- 导入在项目创建/迁移事务边界内，失败策略与现有迁移包导入一致。
- 依赖 P6-82 **只为**渲染器；不在本卡新增 Markdown 依赖。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口；附加：`security`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-81` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`、`security-review`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`、`security-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='DiscussionsImportTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 迁移工具导出、讨论 REST、Web
