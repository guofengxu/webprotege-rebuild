---
id: P3-24
title: 后端端到端验收（里程碑 M3）
phase: P3
module: wp-app
type: impl
size: S
work_type: app-service
ecc_extra: [milestone]
status: done
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
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=BackendAcceptanceTest
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] M2 中的真实项目已导入新系统，并通过 IC-7、IC-8（结果写入 `m3-acceptance.md`）
- [x] 执行记录已填写

## 不在本卡范围
- 前端

## 执行记录
- 日期：2026-11-11
- 分支：feature/p3-24-backend-acceptance
- 测试：2026-11-11T04:51:27+08:00 通过（3 项）
- 实际改动：新增 BackendAcceptanceTest 与 docs/03-plan/m3-acceptance.md。脚本用 RestClient 走完管理员建用户、上传 warehouse-sample.ttl、授权、API Key 长轮询、创建周转托盘、英文标签、搜索、修订历史、回滚、下载和回收站，以及移动端令牌换发。API Key 与 Bearer 不进会话，长轮询异步派发会变成匿名请求，所以这两个过滤器在异步派发上再认证一次。2026-11-11 导入 P2-05 的真实迁移包：4 个项目、3 条修订、1 个新用户、9 条警告，IC-7 与 IC-8 通过。差距（brownfield，不另建文档）：默认搜索只索引无语言 rdfs:label，验收因此先写入 en；未能导出的项目 0a3eaa4c 不在包内；真实包不在仓库里；无法识别的实体创建套件回落到 UUID；事件订阅用长轮询而不是 SSE。
- 偏差：场景一用 API Key 长轮询订阅事件，没有打开 SSE。搜索前把 rdfs:label 的 en 写入显示名称设置。真实包只含 P2-05 verify 通过的 4 个项目。
- 审查：java-reviewer, code-reviewer
