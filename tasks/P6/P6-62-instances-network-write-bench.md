---
id: P6-62
title: 实例库服务：经网络接口的端到端批量写入基准（只测量，不优化）
phase: P6
module: wp-instances-app
type: impl
size: S
work_type: benchmark
ecc_extra: []
status: todo
depends_on: [P6-64, P6-61]
---

# P6-62 网络写入基准

## 目标
经 HTTP 测量批量写入的端到端单批 p95 与失败率（含序列化、认证、校验、落库）；只测量，不优化。

## 参考
- P6-34 / EPIC「P6-62 门禁（锁定）」；P6-32 报告格式
- P6-59 / P6-64：batch API 与幂等；P6-61：Compose

## 产出
- 测试 `InstancesNetworkWriteBenchmark`（默认跳过；`-Dbench=true` 启用）：经 HTTP + API Key 发送批量写入；持续至少 30 秒或固定 N 批；记录吞吐、单批 p95、失败率。
- 报告 `docs/perf/instances-network-write-<日期>.md`。
- **门禁（全量，非 smoke）**：单批 p95 ≤ 100 ms **且** 失败率 = 0；吞吐只记录。不达标 → `task.py block`。
- 冒烟 `-Dbench.smoke=true`：短报告到 `target/`，不判定门禁。

## 实现要点
- **只测量，不优化** `src/main`；必须经真实 HTTP。

## ECC 技能
工作项类型：`benchmark` —— 性能基准。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-62` 查看。

- 开工前加载的 skill：`benchmark`
- 提交前调用的审查 agent：`performance-optimizer`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：performance-optimizer 只用于分析报告，本类卡片不做任何优化（见卡片「实现要点」）。

## 验收
```bash
./mvnw -q -pl :wp-instances-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=InstancesNetworkWriteBenchmark -Dbench=true -Dbench.smoke=true
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 报告已生成（冒烟或全量）
- [ ] 执行记录已填写

## 不在本卡范围
- 调优、加索引、AGE、改门禁（由人决定）
