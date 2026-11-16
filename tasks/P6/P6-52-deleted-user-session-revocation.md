---
id: P6-52
title: 后端：已删除用户的已登录会话立即失效（会话注册表 + 按请求检查）
phase: P6
module: wp-app
type: impl
size: M
work_type: app-security
ecc_extra: [security]
status: todo
depends_on: [P6-29]
---

# P6-52 后端：已删除用户的会话立即失效

## 目标
系统管理员删除用户（P6-29）后，该用户在浏览器中已经登录的会话在下一次请求时就得到 401；包括部署或重启之前建立的会话。（P6-19 评审后从原 P6-29 拆出。）

## 参考
- P6-29：`access/UserDeleted`（事务提交后发布）、`UserAccount.deletedAt`
- `web/AuthController.java#establishSession`（会话主体是 `userId` 字符串；会话由 `saveContext` 惰性创建）、`web/security/SecurityConfig.java`（`apiSecurity` 过滤链）
- Spring Security 7：`SessionRegistryImpl`、`HttpSessionEventPublisher`、`ConcurrentSessionFilter`

## 产出
- `web/security/SecurityConfig.java`：注册 `SessionRegistryImpl` 与 `HttpSessionEventPublisher`；`sessionManagement` 配置 `maximumSessions(-1)`（不限制数量，只为启用 `ConcurrentSessionFilter`）与 `sessionRegistry`；会话过期时返回 401 的 `ProblemDetail`（与现有未登录响应一致），不重定向。
- `web/AuthController.java#establishSession`：在 `saveContext` **之后**（此时会话才存在）调用 `sessionRegistry.registerNewSession(session.getId(), userId)`。
- `web/security/DeletedAccountFilter.java`：放在 `SecurityContextHolderFilter` 之后，只处理会话认证的请求（API Key 与 Bearer 令牌已由 P6-29 处理）：当前主体已删除时使会话失效并返回 401。“是否已删除”通过 `UserService` 查询，结果用 Caffeine 缓存（`wp.security.deleted-check-ttl`，默认 30 秒）。这一层覆盖注册表里没有的会话（部署或重启之前建立的、注册遗漏的）。
- `web/security/DeletedUserSessionListener.java`：监听 `UserDeleted`，对该用户在注册表中的全部会话调用 `expireNow()`，并使缓存中该用户的条目失效。
- 测试 `web/DeletedUserSessionTest.java`（Testcontainers）：
  - 用户 A 用会话登录后被删除：A 的会话下一次请求得到 401；其他用户的会话不受影响；
  - 注册表中没有 A 的会话（测试中清空注册表，模拟重启之前建立的会话）：在缓存有效期后（测试中设为 0）下一次请求得到 401；
  - 正常用户的连续请求在缓存有效期内不重复查询数据库（通过缓存统计断言）；
  - 回归：`AuthFlowTest` 不变通过。

## 实现要点
- 注册表与缓存都只在本进程内有效：其它实例最迟在缓存有效期后发现已删除的用户。多实例下的立即失效留给 P6-40，在 `DeletedAccountFilter` 的 Javadoc 中说明。
- 过滤器只读用户状态，不改会话以外的任何东西；日志只记录用户 ID。
- 依赖 P6-29 使用它的事件与字段，并串行修改 `SecurityConfig.java`、`AuthController.java`（之后由 P6-51 修改）。

## ECC 技能
工作项类型：`app-security` —— 认证、授权、令牌与其它安全相关接口；附加：`security`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-52` 查看。

- 开工前加载的 skill：`springboot-security`、`security-review`、`springboot-tdd`、`java-coding-standards`
- 提交前调用的审查 agent：`security-reviewer`、`java-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='DeletedUserSessionTest,AdminDeleteUserTest,AuthFlowTest,ModularityTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 删除用户的接口与数据（P6-29）
- 多实例之间的立即失效、Spring Session（P6-40）
