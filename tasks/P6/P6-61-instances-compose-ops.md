---
id: P6-61
title: 实例库服务：Compose（同实例第二库）与备份、监控、限流说明
phase: P6
module: root
type: impl
size: M
work_type: ci-devops
ecc_extra: [docker]
status: todo
depends_on: [P6-57]
---

# P6-61 实例库 Compose 与运维说明

## 目标
本地 `compose.yaml` 在**同一 Postgres 实例**上创建 `wp_instances` 库并拉起 `instances` 服务；运维文档写清备份、监控、投影只读账号与入口限流。

## 参考
- P6-34 / EPIC 决定第 6 条；ADR-013 第 4 节
- 根目录 `compose.yaml`；`docker/backend/Dockerfile`
- P6-13：`wp_projection_reader`；P6-57：配置键与端口；P6-58：bootstrap 密钥

## 产出
- `compose.yaml`：**锁定**同实例第二库——init 脚本 `create database wp_instances`；服务 `instances` 构建 `docker/instances/Dockerfile`，端口例如 `127.0.0.1:8082:8082`；环境变量含实例库 URL、投影库 URL（只读用户）、`wp.instances.project-id`、bootstrap API Key（仅开发）。
- `docker/instances/Dockerfile`；`docker/postgres/init-instances-db.sql`（或现有 init 目录等价物）。
- `docs/ops/instances.md`：本地 vs 非开发（非开发应用独立 Postgres 实例与独立备份）；健康 / readiness URL；投影只读账号接线；bootstrap 警告；**建议在反向代理对 `/api/` 做按键或按 IP 限流**；投影 lag / `contract_version` 不匹配时看实例服务 ERROR 日志。
- 验收：`docker compose -f compose.yaml config` 通过。

## 实现要点
- 不改 wp-app / frontend 行为；生产密钥不入库。

## ECC 技能
工作项类型：`ci-devops` —— CI、Docker Compose 与部署配置；附加：`docker`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-61` 查看。

- 开工前加载的 skill：`deployment-patterns`、`docker-patterns`、`github-ops`
- 提交前调用的审查 agent：`code-reviewer`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
docker compose -f compose.yaml config
python tools/plan_lint.py --check
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 写入基准（P6-62）、CI 增加 instances job
