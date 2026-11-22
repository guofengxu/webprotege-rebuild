---
id: P6-58
title: 实例库服务：独立 API Key 认证（存于 wp_instances，不复用编辑器密钥）
phase: P6
module: wp-instances-app
type: impl
size: M
work_type: app-security
ecc_extra: [security]
status: todo
depends_on: [P6-57]
---

# P6-58 实例库 API Key

## 目标
业务系统用实例库自己的 API Key（请求头 `X-API-Key`）调用后续 REST；密钥哈希存于 `wp_instances`，与编辑器 `access` 模块的 API Key **完全分离**。

## 参考
- P6-34 / EPIC 决定第 2 条；ADR-013 第 4 节（系统集成、权限模型不匹配）
- P6-57：`wp-instances-app` 骨架与实例库数据源
- 编辑器侧对照（只读行为，勿复制模块）：`ApiKeyAuthenticationFilter`（`X-API-Key`）、密钥只存哈希的做法
- EPIC：应用 Flyway `db/instances-app` **V1**（API Key）

## 产出
- `backend/wp-instances-app/src/main/resources/db/instances-app/V1__api_keys.sql`（**不要**写入内核 `db/instances`）：`api_keys(id uuid pk, name text not null, key_hash text not null unique, created_at timestamptz not null, revoked_at timestamptz)`。
- `InstancesApiKeyService`：校验、吊销；哈希带 pepper `wp.instances.api-key-pepper`。**首期无管理 REST**：创建途径仅为 (1) `wp.instances.bootstrap-api-key` 在无未吊销键时插入一条；(2) 运维 SQL（步骤写入 `docs/api/instances.md`）。
- Security：`/api/**` 需认证；actuator liveness 可匿名；无键 / 错键 / 已吊销 → 401。
- 测试：无键 401；正确键通过；吊销后 401；明文不落库。
- `docs/api/instances.md`（本卡创建）：认证、bootstrap、SQL 轮换/吊销、与编辑器 API Key 的区别、单租户说明。
- 占位 `GET /api/v1/projects/{projectId}/ping`：`projectId` ≠ `wp.instances.project-id` → 403；匹配且已认证 → 200。

## 实现要点
- 日志与错误响应不得回显完整 API Key。
- 不实现用户登录、会话 Cookie、编辑器 Bearer、管理端 CRUD API。

## ECC 技能
工作项类型：`app-security` —— 认证、授权、令牌与其它安全相关接口；附加：`security`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-58` 查看。

- 开工前加载的 skill：`springboot-security`、`security-review`、`springboot-tdd`、`java-coding-standards`
- 提交前调用的审查 agent：`security-reviewer`、`java-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-instances-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='InstancesApiKeyTest,InstancesAppSmokeTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 批量写入 / 读取业务 API（P6-59、P6-60）
- 按项目的细粒度授权、管理端 CRUD UI
