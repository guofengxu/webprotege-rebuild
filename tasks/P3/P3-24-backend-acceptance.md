---
id: P3-24
title: 后端端到端验收（里程碑 M3）
phase: P3
module: wp-app
type: impl
size: S
work_type: app-service
ecc_extra: [milestone]
status: todo
depends_on: [P3-23]
---

# P3-24 后端端到端验收（里程碑 M3）

## 目标
用一个脚本化测试证明后端 MVP 的全部功能可以连起来用。

## 参考
- `docs/03-plan/roadmap.md`（M3 条件）
- `docs/03-plan/mvp-scope.md`

## 产出
- 测试 `BackendAcceptanceTest`（`@SpringBootTest(webEnvironment = RANDOM_PORT)` + Testcontainers，使用 `RestClient` 发真实 HTTP 请求）：管理员登录 → 通过管理 API 创建用户 B（P3-25）→ 上传 `warehouse-sample.ttl` → 把 B 设为 CAN_EDIT → B 用 API Key 订阅事件 → B 创建子类“周转托盘”→ 收到事件 → 编辑其类 Frame 加英文标签 → 搜索 `pallet` 命中 → 查看修订历史 → 回滚最近一次修订 → 下载 ttl 并校验内容 → 把项目移入回收站。
- 同一测试类的第二个场景（移动端流程）：B 调用 `POST /api/auth/tokens` 获取访问令牌 → 用 `Authorization: Bearer` 查询类层级 → 发起长轮询 → 管理员创建一个类 → 长轮询返回该事件 → 用刷新令牌换取新令牌，旧刷新令牌失效。
- `docs/03-plan/m3-acceptance.md`：记录测试覆盖的步骤，以及用真实迁移包导入后的 IC-7 / IC-8 结果。

## 实现要点
- 测试只通过 HTTP 调用，不直接调用内部 Bean，这样它也能作为 API 用法示例。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口；附加：`milestone`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P3-24` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`、`production-audit`、`brownfield-retrospective`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`
- 可用命令：`/review-pr`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：里程碑 PR 合并前跑一次 /review-pr 多代理审查；brownfield-retrospective 的差距清单写入卡片执行记录，不新建文档目录。

## 验收
```bash
./mvnw -q -pl wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=BackendAcceptanceTest
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] M2 中的真实项目已导入新系统，并通过 IC-7、IC-8（结果写入 `m3-acceptance.md`）
- [ ] 执行记录已填写

## 不在本卡范围
- 前端
