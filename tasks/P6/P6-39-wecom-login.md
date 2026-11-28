---
id: P6-39
title: 后端：企业微信扫码登录（复用 P6-37 的外部身份绑定与会话建立）
phase: P6
module: wp-app
type: impl
size: M
work_type: app-security
ecc_extra: [security]
status: suspended
depends_on: [P6-51]
---

# P6-39 后端：企业微信扫码登录

## 目标
配置了企业微信自建应用之后，登录页可以显示“企业微信登录”：用户扫码授权后，系统按企业微信的 `userid` 绑定本地账户并建立与账号密码登录相同的会话。

## 参考
- P6-37：`access/ExternalIdentityService`、`ExternalIdentity`、`ExternalLoginPolicy`、`user_identities`；P6-51：`web/security/SessionLogin`、外部登录过滤链、`GET /api/auth/providers`、`docs/api/oidc.md`
- 企业微信开发者文档：“企业微信登录（网页授权登录 / 扫码登录）”“获取 access_token”“获取访问用户身份”。企业微信的登录不是标准 OIDC：授权码换取的是企业内的 `userid`，`access_token` 用 `corpid + corpsecret` 获取并按有效期缓存
- `docs/api/oidc.md`（P6-37）

## 产出
- `web/security/WecomProperties.java`：`wp.wecom.enabled`（默认 `false`）、`corp-id`、`agent-id`、`secret`（只从环境变量或外部配置读取）、`display-name`、`api-base-url`（默认企业微信官方地址，测试中指向本地桩）。
- `web/security/WecomClient.java`：用 JDK `HttpClient` 调用“获取 access_token”与“获取访问用户身份”两个接口；`access_token` 在有效期内缓存，`errcode` 为过期类错误时刷新一次后重试；超时 5 秒；日志中不记录 `secret`、`access_token`、授权码。
- `web/WecomLoginController.java`：
  - `GET /oauth2/wecom/authorize`：生成并在会话中保存一次性 `state`，重定向到企业微信扫码登录页（带 `appid = corp-id`、`agentid`、`redirect_uri`、`state`）；
  - `GET /oauth2/wecom/callback?code&state`：校验 `state`（一次性、5 分钟有效），用 `code` 取得 `userid`，调用 `ExternalIdentityService.resolve`（`issuer = "wecom:" + corpId`、`subject = userid`；企业微信不返回已验证邮箱，`ExternalLoginPolicy` 的按邮箱绑定固定为 `false`，自动建号取 `wp.wecom.auto-provision`，默认 `false`），成功时用 `SessionLogin` 建立会话并重定向到 `/`，失败重定向到 `/login?error=oidc_unlinked` 或 `/login?error=oidc_failed`。
- `web/security/SecurityConfig.java`：P6-51 的外部登录过滤链改为在 `wp.oidc.enabled` 或 `wp.wecom.enabled` 任一为 `true` 时注册，`securityMatcher` 增加 `/oauth2/wecom/**`；`oauth2Login` 仍只在 `wp.oidc.enabled=true` 时配置。这条链必须使用会话（不能落到无状态的 `staticContent` 链，否则 `state` 无法保存）。
- `GET /api/auth/providers`：开启时增加一项 `{ id: "wecom", displayName, loginUrl: "/oauth2/wecom/authorize" }`。
- `docs/api/oidc.md` 增加“企业微信”一节：需要的自建应用配置、可信域名、回调地址，只写占位值。
- 测试 `web/WecomLoginTest.java`：用 JDK `com.sun.net.httpserver.HttpServer` 在本地起企业微信接口的桩：正常登录；`state` 不匹配、过期或重复使用时拒绝；未绑定的 `userid`；`access_token` 过期后刷新一次重试成功；桩超时时重定向到 `oidc_failed`；日志中没有 `secret` 与授权码。

## 实现要点
- 不引入任何企业微信 SDK 或新的第三方库，HTTP 调用只用 JDK `HttpClient`。
- 前端不需要改：P6-38 的登录页按 `/api/auth/providers` 显示按钮，企业微信的条目会自动出现。

## ECC 技能
工作项类型：`app-security` —— 认证、授权、令牌与其它安全相关接口；附加：`security`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-39` 查看。

- 开工前加载的 skill：`springboot-security`、`security-review`、`springboot-tdd`、`java-coding-standards`
- 提交前调用的审查 agent：`security-reviewer`、`java-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='WecomLoginTest,OidcLoginTest,AuthFlowTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写（含用真实企业微信应用手工冒烟的结果）

## 不在本卡范围
- 企业微信内的免登录（企业微信客户端内打开网页时的静默授权）
- 同步企业微信通讯录、按部门映射项目角色
- 移动端（P5 暂停）

## 阻塞
- 日期：2026-11-15
- 原因：外部条件未就绪。企业微信登录需要真实的企业 `corpid`、自建应用 `agentid` 与 `secret`，以及已备案、已在企业微信后台配置为可信域名的 HTTPS 域名；没有这些条件时只能对着本地桩开发，无法完成执行记录要求的手工冒烟。卡片内容本身完整。
- 恢复方式：人准备好上述配置后，把本卡 `status` 改回 `todo` 并删除本节，再按 `python tools/task.py start P6-39` 开工（P6-51 须已合并）。

## 挂起
- 日期：2026-11-28
- 决定：人决定把与微信集成相关的功能挂起（`status: suspended`）。本卡是唯一未完成的微信集成卡；上面的「阻塞」小节保留作历史记录。决定见 `tasks/P6/EPIC.md`「企业微信登录挂起与 P11 收尾目录」。
- 恢复条件：人准备好企业微信自建应用（`corpid`、`agentid`、`secret`），以及已备案、已在企业微信后台配置为可信域名的 HTTPS 域名。
- 恢复方式：把本卡 `status` 改回 `todo`，删除「阻塞」与「挂起」两节，合并后按 `python tools/task.py start P6-39` 开工（P6-51 已完成）。
- 影响：没有卡依赖本卡。P6-38 的登录页按 `/api/auth/providers` 显示入口，挂起期间不会出现企业微信入口；已完成的 P6-37（外部身份绑定）与 P6-51（通用 OIDC 登录）不受影响。
