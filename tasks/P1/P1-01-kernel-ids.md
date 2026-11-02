---
id: P1-01
title: 内核标识类型：ProjectId、RevisionNumber、UserId
phase: P1
module: wp-kernel
type: impl
size: S
work_type: kernel
ecc_extra: []
status: done
depends_on: [P0-03]
---

# P1-01 内核标识类型：ProjectId、RevisionNumber、UserId

## 目标
内核里所有标识都是带校验的值对象，不再到处传字符串和 long。

## 参考
- BS-03（修订号从 1 开始，0 表示空项目）

## 产出
- `wprebuild.kernel.id.ProjectId`：`record ProjectId(UUID value)`；`static ProjectId of(String)`（非法 UUID 抛 `IllegalArgumentException`）；`static ProjectId random()`；`toString()` 返回 UUID 字符串。
- `wprebuild.kernel.id.RevisionNumber`：`record RevisionNumber(long value) implements Comparable<RevisionNumber>`；`value >= 0`；常量 `ZERO`；`static RevisionNumber of(long)`；`RevisionNumber next()`；`boolean isZero()`；`boolean isAfter(RevisionNumber)`。
- `wprebuild.kernel.id.UserId`：`record UserId(String value)`；非空且去除首尾空白后非空；常量 `SYSTEM = new UserId("system")`（迁移、自动任务使用）。
- 测试 `IdsTest`：合法 / 非法输入、相等性、`RevisionNumber` 排序与 `next()`。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`kernel` —— 内核领域代码（wp-kernel，纯 Java + OWL API 5）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P1-01` 查看。

- 开工前加载的 skill：`java-coding-standards`、`tdd-workflow`、`hexagonal-architecture`
- 提交前调用的审查 agent：`java-reviewer`、`type-design-analyzer`、`silent-failure-hunter`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-kernel test -Dtest=IdsTest
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写

## 不在本卡范围
- 用户账户、权限（P3）

## 执行记录
- 日期：2026-11-02
- 分支：feature/p1-01-kernel-ids
- 测试：2026-11-02T13:50:06+08:00 通过（3 项）
- 实际改动：在 wp-kernel 增加带校验的 ProjectId、RevisionNumber、UserId，以及 IdsTest。
- 偏差：UserId 拒绝空白但不裁剪首尾空格；RevisionNumber.next() 在 Long.MAX_VALUE 时抛 IllegalArgumentException，卡片未单列这一条。
- 审查：java-reviewer, type-design-analyzer, silent-failure-hunter
