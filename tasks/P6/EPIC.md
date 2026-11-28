# P6 扩展功能：候选清单

由人在“优先级”列填写 1（最高）~ 3 或“不做”，然后执行 `P6-00`。

> **2026-11-13（P6-00）**：人要求暂停移动端（P5），并“结合 P1 ~ P4 当前任务项规划 P6”。下表中的优先级是 Agent 依据 P1 ~ P4 的交付现状**提出的建议**，人确认后才算定稿（P6-00 的“人已标注优先级”由人勾选）。人如果改了优先级，用 `python tools/task.py reject P6-00 --reason "…"` 退回，Agent 按新的优先级重排。

> **2026-11-15（P6-19）**：第一轮（P6-01 ~ P6-18）已全部完成。人要求移动端（P5）继续暂停，并“结合 P6 已完成任务项规划 P6 未完成对应的子任务”。下表“优先级”一列保留第一轮的结果；其余条目的第二轮建议优先级与展开结果见文末「第二轮规划（P6-19）」，同样由人确认后定稿（P6-19 的“人已确认优先级”由人勾选）。

| 候选项 | 说明 | 优先级 |
|---|---|---|
| MVP 差距补齐（P6-00 新增） | `docs/03-plan/m4-acceptance.md` 差距清单中仍未处理的第 2、4、5 项：按名称查找用户、“我在本项目中的权限”、事件订阅随权限撤销与回收站失效，以及对应的 Web 端改动。第 1 项已在 `chore(build): add local docker stack and fix workspace redirect` 中修复；第 3 项是产品约束，不改 | 1 |
| 实例库服务 PoC | ADR-013：独立的 PostgreSQL 18 数据库（`wp_instances`），`instances` + `links` 模型，由本体生成校验规则；用诺和诺德仓库的规模造数据，测量深层追溯查询；p95 达不到 1 秒时评估 Apache AGE。建议下一轮排期，作为 P6-08 outbox 中继的消费者接入 | |
| 本体投影表 | ADR-013：由 outbox 驱动、只读、可重建的实体 / 标签 / 父子关系 / 定义域与值域表，供 BI 与 AI Agent 用 SQL 查询。本轮连同 outbox 中继（P6-08）一起展开 | 2 |
| outbox 发布器 → 图数据库 | 把每个修订同步到图数据库（Neo4j + neosemantics 或 RDF 三元组库），供 AI Agent 运行时查询。建议在 P6-08 完成后作为消费者接入 | |
| outbox 发布器 → Kafka | 把修订事件发布到 Kafka 主题，供其它系统订阅。建议在 P6-08 完成后作为消费者接入 | |
| MCP 工具 | 把只读查询命令（层级、Frame、搜索、历史）暴露为 MCP 工具；写操作需要人工确认。本轮只做只读工具 | 3 |
| 移动端：iOS 与微信小程序 | ADR-014 首期之外的平台；小程序需要合法域名与 HTTPS。P5 暂停期间不排期 | |
| 移动端推送通知 | 关注的实体被修改时推送。P5 暂停期间不排期 | |
| Vue 3.6 升级 | 3.6 正式发布后升级 Web 端，单独一张卡 | |
| TypeScript 7 评估 | vue-tsc 正式支持后再评估 | |
| 表单（Forms） | 已逆向并拆首期卡（P6-43 → P6-66~P6-73）；见文末「表单（P6-43）」 | |
| 讨论与评论 | 已逆向并拆首期卡（P6-44 → P6-74~P6-82）；见文末「讨论（P6-44）」 | |
| 关注（Watches）与通知 | 已逆向并拆首期卡（P6-45 → P6-83~P6-91）；见文末「关注（P6-45）」 | |
| 标签 | 已逆向并拆首期卡（P6-46 → P6-92~P6-99）；见文末「标签（P6-46）」 | |
| webhook | 修订提交后回调外部 URL（可以基于 outbox 实现）。建议在 P6-08 完成后作为消费者接入 | |
| 合并实体 | 把实体 A 合并到 B，并替换全部引用 | |
| 上传并合并到已有项目 | 对应旧系统的 `merge_add` | |
| Manchester 语法编辑 | 类 Frame 的高级编辑方式 | |
| OIDC / 企业微信登录 | 接入企业身份提供方 | |
| 多实例部署 | 按项目把请求路由到加载了该项目的实例 | |
| 删除用户 | 需要先确定其历史修订如何展示 | |

## 优先级的依据（P6-00）

- **1. MVP 差距补齐**：M4 已经达成，但 P4-17 记下的后端缺口没有归属。P3、P4 已全部完成，P5 暂停，这些缺口不进 P6 就没有卡可以承接。它们直接影响多人试用：只能看的成员会看到编辑按钮，被移出的成员仍能收到事件，添加成员必须记住准确的用户 ID。改动量小，只复用已有的模块。
- **2. 本体投影表**：P3-02 以来，每个修订都写入了 outbox，但没有任何消费者。投影表是 ADR-013 中风险最低的第一个消费者：同一个数据库，不新增服务和依赖。它所需的 outbox 中继（P6-08）也是实例库、图数据库、Kafka、webhook 的共同前提，所以这几项排在它之后。
- **3. MCP 工具（只读）**：ADR-007 已经把命令 `record` 设计成 MCP 工具的参数结构，P3 的查询命令和 API Key（P3-05）可以直接复用。它需要新增第三方依赖，所以先做 PoC（P6-14）。
- **P5 暂停期间不排期**：移动端相关的两项。
- **未排期**：其余条目与 P1 ~ P4 的现有能力关系不大（表单、讨论、关注、标签都要先做逆向分析），或者取决于外部条件（Vue 3.6、TypeScript 7、企业身份提供方），留待人排定后，另开规划卡细化。

## 展开结果（P6-00）

| 条目 | 卡片 | 说明 |
|---|---|---|
| 1. MVP 差距补齐 | P6-01 “我在本项目中的权限”（后端）<br>P6-02 查找候选成员（后端）<br>P6-03 事件订阅随权限撤销与回收站失效（后端）<br>P6-04 按权限显示新建、删除、回滚（Web）<br>P6-05 Frame 编辑器只读（Web）<br>P6-06 设置表单只读（Web）<br>P6-07 成员页查找用户（Web） | 后端卡同时更新 `docs/api/openapi.json` 与 `@wp/api-types`。P6-03 不新增事件类型（Web 端对事件联合类型做了穷尽检查）：回收站沿用 `resync`，被移出沿用 `PermissionsChanged` 再断开连接，所以不需要 Web 端配合 |
| 公共前提 | P6-08 outbox 中继 | 全局 outbox 锁保证 `id` 顺序等于提交顺序，不会跳行；按消费者记录进度，支持部分确认与“稍后重试”。不依赖投影表的 PoC |
| 2. 本体投影表 | P6-09 **PoC（一）**：`projection` 模块、表结构与写入器<br>P6-10 **PoC（二）**：从内存模型计算与全量重建<br>P6-11 **PoC（三）**：50 万公理规模的基准与通过标准<br>P6-12 增量更新<br>P6-13 状态与重建接口、外部 SQL 访问说明 | PoC 的通过标准（P6-11）：50 万公理规模下全量重建 ≤ 60 秒，三条典型查询 p95 ≤ 200 毫秒，两次重建结果相同。未达标时阻塞，由人决定；P6-12 依赖 P6-11 |
| 3. MCP 工具（只读） | P6-14 **PoC**：SDK 选型、`/mcp` 端点、`search_entities`、统一结果结构<br>P6-15 浏览工具（项目、层级、Frame）与结果大小限制<br>P6-16 个体与历史工具、使用说明 | PoC 的通过标准：Enforcer 依赖收敛通过且 `exclusion` 不超过 2 处、端到端往返、认证与权限拒绝、MCP 会话与密钥绑定、p95 ≤ 100 毫秒（基准模式）、新增 jar ≤ 5 MB |

依赖关系（`A → B` 表示 B 依赖 A）：P4-17 → {P6-01、P6-03、P6-08、P6-14}；P6-01 → {P6-02、P6-04}；P6-04 → {P6-05、P6-06、P6-07}；P6-02 → {P6-07、P6-13}；{P6-08、P6-03} → P6-09 → P6-10 → P6-11 → P6-12 → P6-13（P6-12 另依赖 P6-08；2026-11-14 起 P6-10 → P6-17 → P6-18 → P6-11，见下文「P6-11 首次运行与拆卡」）；P6-14 → P6-15 → P6-16。条目 2 对条目 1 的两处依赖（P6-09 依赖 P6-03，P6-13 依赖 P6-02）只是为了串行修改共享文件，见下；除此之外，三个条目互不依赖，可以并行。

共享文件与迁移号：

- `docs/api/openapi.json`、`frontend/packages/api-types/src/schema.d.ts`、`OpenApiResponseSchemasTest`：P6-01、P6-02、P6-13 都要修改，因此串行为 P6-01 → P6-02 → P6-13。MCP 的卡不改 OpenAPI。
- `frontend/wp-web/src/shared/api/queryKeys.ts`：P6-04 与 P6-07 都要修改，因此 P6-07 依赖 P6-04。P6-05 与 P6-06 不改它，两者也不改同一个文件，可以并行。
- `docs/02-architecture/overview.md` 第 2 节的模块表：P6-03（`events` 增加对 `project` 的依赖）、P6-09（新增 `projection` 模块）、P6-12、P6-13 都要修改，因此串行为 P6-03 → P6-09 → … → P6-12 → P6-13。
- Flyway：P6-08 使用 `V4`，P6-09 使用 `V5`（因此 P6-09 依赖 P6-08）。其它 P6 卡不新增迁移。
- `web/security/SecurityConfig.java`：只有 P6-14 修改（新增 MCP 过滤链，并把 `staticContent` 改为 `@Order(2)`）。
- `revision/JdbcRevisionCommitOutbox.java`：只有 P6-08 修改。它处在编辑的写路径上，按 ADR-006 审查锁顺序。

## P6-11 首次运行与拆卡（2026-11-14）

P6-11 首次运行（`docs/perf/projection-poc-2026-11-14.md`）只有一项未达标：Q1（递归 CTE 查深度为 3 的类的全部后代）p95 1,550 毫秒（门禁 ≤ 200 毫秒），p50 只有 135 毫秒。全量重建 28.4 秒、Q2、Q3、两次重建一致都达标。根因分析（`docs/perf/projection-poc-2026-11-14-root-cause.md`）的结论是测法缺陷：写入 50 万规模后立即采样，没有刷新统计信息、没有预热，也没有记录执行计划和后台活动。生产代码的全量重建同样不刷新统计信息。现有证据不足以否定投影表的设计。人要求按根因分析拆卡：

| 卡片 | 内容 |
|---|---|
| P6-17 | 全量重建提交后，对 5 张投影表执行 `ANALYZE`（`ProjectionWriter.refreshStatistics()`，由 `ProjectionRebuilder` 在提交后、在新事务中调用） |
| P6-18 | 基准测法修订（只改测试代码）：刷新统计信息、预热后采样，记录 Q1 执行计划、返回行数、autovacuum 与 GC 活动，新增冒烟模式 |
| P6-11 | 两张修复卡合并后重跑；语料、规模、查询和门禁阈值不改 |

依赖：P6-10 → P6-17 → P6-18 → P6-11 → P6-12。共享文件：`ProjectionWriter.java` 由 P6-17 和 P6-12 修改，已经通过 P6-11 串行；`ProjectionRebuilder.java` 只由 P6-17 修改（P6-12 只调用它）；`ProjectionBenchmark.java`、`ProjectionCorpus.java` 只由 P6-18 修改。两张修复卡都不新增 Flyway 迁移，也不改 `docs/02-architecture/overview.md`。重跑仍不达标时，由人按根因分析最后一节，在覆盖索引、调整 Q1 门禁、结构改造（闭包表或祖先路径，需要新开 ADR）和放弃投影表之间选择。

## 第二轮规划（P6-19，2026-11-15）

人要求：按规则建分支；移动端（P5）继续暂停；结合 P6 已完成的卡，规划候选清单中尚未完成的条目。下表的优先级是 Agent 的**建议**，人确认后才算定稿（P6-19 的“人已确认优先级”由人勾选）；人改了优先级时用 `python tools/task.py reject P6-19 --reason "…"` 退回重排。P6-49 ~ P6-52 是 planner / architect 评审后从 P6-20、P6-27、P6-29、P6-37 拆出的卡（避免超过 400 行），编号接在末尾，与 P6-17 / P6-18 的做法相同。

### 第二轮优先级（建议）

| 候选项 | 第二轮建议 | 卡片 | 结合的已完成能力 / 理由 |
|---|---|---|---|
| webhook | 1 | P6-20、P6-49、P6-21、P6-22 | P6-08 outbox 中继的第一个对外消费者；旧系统已有（BS-15），不新增依赖（JDK `HttpClient`）；补上旧系统没有的签名、超时、重试与按 webhook 隔离的并发投递 |
| 合并实体 | 1 | P6-23 ~ P6-25 | 旧系统的核心编辑功能（BS-13）；内核已有 IRI 改写与删除生成器（P1-12、P1-16），P6-01 / P6-04 的权限感知可以直接复用 |
| 上传并合并到已有项目 | 2 | P6-26、P6-27、P6-50、P6-28 | 复用 P3-09 的解析与 P3-20 的差异渲染（BS-14）。候选清单原写“对应 `merge_add`”，调研发现旧系统的 `merge/` 才是“上传新版本并按差异合并”，`merge_add/` 是“只追加”，两种方式都纳入 |
| 删除用户 | 2 | P6-29、P6-52、P6-30 | 系统管理员的运维缺口；旧系统没有删除用户的功能。决定：软删除，修订历史照常显示账户 ID，该 ID 不可复用；复用 P6-03 的订阅关闭 |
| 实例库服务 PoC | 2 | P6-31 ~ P6-34 | ADR-013 的核心；先测读写性能（P6-32 门禁）再决定服务形态（P6-34）；校验规则直接读取 P6-09 ~ P6-13 的投影表，不再自己加载 OWL 模型 |
| outbox 发布器 → Kafka | 3 | P6-35 | P6-08 的又一个消费者；新增 Kafka 依赖，默认关闭 |
| outbox 发布器 → 图数据库 | 未排期（评估结论：暂不做） | P6-36 | ADR-013 第 5 节四个条件当前均不满足；见下文「图数据库发布器评估（P6-36）」 |
| OIDC / 企业微信登录 | 3 | P6-37、P6-51、P6-38、P6-39 | ADR-010 推迟到 P6；通用 OIDC 用静态客户端注册即可测试；企业微信需要真实的企业凭据与可信域名，P6-39 先标为阻塞，2026-11-28 改为挂起 |
| TypeScript 7 评估 | 3 | P6-42 | TypeScript 7.0 已正式发布（npm `latest` 为 7.0.2）；“vue-tsc 正式支持”需要实测才能确认，评估卡给出结论 |
| Vue 3.6 升级 | 条件满足后 | P6-41（阻塞） | 截至 2026-11-15，3.6 仍是 RC（`3.6.0-rc.15`） |
| 多实例部署 | 未排期（规划卡，2026-11-24 起暂缓，2026-11-28 取消） | P6-40（取消；拆卡目录 `tasks/P10/`） | 牵涉 ADR-006 等多处单实例假设，先出 ADR 草案；见文末「规划卡暂缓与新阶段目录」「阻塞的规划卡改为取消」 |
| 表单（Forms） | 1（首期范围见下文「表单（P6-43）」） | P6-43、P6-66 ~ P6-73 | 逆向见 `docs/01-rea/forms.md`、BS-16 ~ BS-18；首期只读+TEXT/NUMBER（PROPERTY）可写 |
| 讨论与评论 | 1（首期范围见下文「讨论（P6-44）」） | P6-44、P6-74 ~ P6-82 | 逆向见 `docs/01-rea/discussions.md`、BS-19 ~ BS-20；邮件留给 P6-45 |
| 关注（Watches）与通知 | 1（首期范围见下文「关注（P6-45）」） | P6-45、P6-83 ~ P6-91 | 逆向见 `docs/01-rea/watches.md`、BS-21 ~ BS-22；SMTP / 投递队列独立首卡，讨论邮件可日后复用 |
| 标签 | 已拆首期卡（显式标签） | P6-46 → P6-92~P6-99 | MatchingEngine / 条件自动打标另议；见文末「标签（P6-46）」 |
| Manchester 语法编辑 | 未排期（逆向分析，2026-11-24 起暂缓，2026-11-28 取消） | P6-47（取消；拆卡目录 `tasks/P7/`） | 旧渲染器约 68 个文件，评估 OWL API 5 自带的渲染器能否替代；见文末「规划卡暂缓与新阶段目录」「阻塞的规划卡改为取消」 |
| MCP 写工具（“MCP 工具”条目的剩余部分） | 未排期（规划卡，2026-11-24 起暂缓，2026-11-28 取消） | P6-48（取消；拆卡目录 `tasks/P8/`） | 第一轮只做了只读工具；人工确认机制需要先出 ADR 草案；见文末「规划卡暂缓与新阶段目录」「阻塞的规划卡改为取消」 |
| 移动端：iOS 与微信小程序、移动端推送通知 | P5 暂停期间不排期 | — | 人的决定：移动端继续暂停 |

“未排期（规划卡）”“未排期（逆向分析）”表示本轮只建规划卡，规划卡完成并经人确认后再拆实现卡。`docs/03-plan/mvp-scope.md` 中“暂不纳入”但不在本候选清单中的条目（`viz/`、`bulkop/`、`csv/`、`obo/`、`perspective/`）本轮不规划，需要时由人先加入候选清单。

### P5 继续暂停

`P5-00` 保持 `blocked`。第二轮的卡片都不依赖 P5；新增的后端接口（webhook、合并实体、上传合并、删除用户、OIDC）只在 Web 端接入，移动端不跟进；`@wp/api-types` 照常重新生成，移动端恢复时可以直接使用。OIDC 用于移动端换取访问令牌、移动端推送通知都不在本轮。

### 依赖关系（第二轮）

`A → B` 表示 B 依赖 A：

- webhook：P6-13 → P6-20 → P6-49 → {P6-21、P6-22}
- 合并实体：P1-16 → P6-23；{P6-23、P6-49} → P6-24 → P6-25
- 上传合并：P1-21 → P6-26；P6-24 → P6-27；{P6-27、P6-26} → P6-50 → P6-28（另依赖 P6-22）
- 删除用户：P6-50 → P6-29 → {P6-52、P6-30}
- 实例库：P6-11 → P6-31 → P6-32 → P6-33（另依赖 P6-13）→ P6-34（2026-11-21 起 P6-31 → {P6-53、P6-54} → P6-32，见下文「P6-32 首次运行与拆卡」）
- Kafka：{P6-21、P6-51} → P6-35；图数据库评估：P6-32 → P6-36
- 企业登录：P6-29 → P6-37；{P6-37、P6-52} → P6-51 → {P6-38（另依赖 P6-28）、P6-39}
- 多实例：{P6-52、P6-27、P6-21} → P6-40
- 前端升级：P6-07 → P6-42 → P6-41（2026-11-22 起 P6-65 → P6-42，见下文「P6-42 首次执行与修复卡」）
- 逆向分析：P4-22 → P6-43；P6-03 → P6-44；P6-21 → P6-45；P3-19 → P6-46；P4-09 → P6-47
- MCP 写工具：{P6-16、P6-24} → P6-48

本卡合并后可以立即开工（依赖都已完成）、且互不共享文件的实现卡：P6-20、P6-23、P6-26、P6-31、P6-42。规划卡 P6-43、P6-44、P6-46、P6-47 的依赖也已完成，但规划卡一次只做一张（见下文“规划卡的编号规则”）。后端链上的多处依赖只是为了串行修改共享文件，见下。

### 多实例现状

第一轮的 outbox 中继、投影表，以及本轮的 webhook 投递（认领 + 租期 + 防重令牌）已支持多实例；本轮的会话失效（P6-52，进程内注册表与缓存）与上传暂存（P6-27，本机磁盘）只在单实例下成立。两者连同运行时注册表、事件订阅、Lucene 索引一起交给 P6-40。

### 共享文件与迁移号（第二轮）

- `docs/api/openapi.json`、`frontend/packages/api-types/src/schema.d.ts`、`OpenApiResponseSchemasTest`：P6-49、P6-24、P6-27、P6-50、P6-29、P6-51 都要修改，串行为 P6-49 → P6-24 → P6-27 → P6-50 → P6-29 → P6-51。卡片中“依赖某卡只是为了串行修改……”即指此。MCP、Kafka、实例库的卡不改 OpenAPI。
- Flyway（`backend/wp-app/src/main/resources/db/migration`）：P6-20 用 `V6`、P6-29 用 `V7`、P6-37 用 `V8`，其它卡不新增迁移。实例库（P6-31）使用自己的 `db/instances/V1`，与编辑器的迁移无关。
- `access/Action.java`、`access/ProjectRole.java`、`BS10_AccessServiceTest`：P6-24（`MERGE_ENTITIES`）→ P6-27（`UPLOAD_AND_MERGE`、`UPLOAD_AND_MERGE_ADDITIONS`）。
- `web/ProjectController.java`：P6-24 → P6-27 → P6-50。`web/security/SecurityConfig.java`、`web/AuthController.java`：P6-29（`me` 与认证过滤器中的“已删除”判断）→ P6-52 → P6-51 → P6-39。
- `backend/wp-app/pom.xml` 的依赖列表：P6-51（OAuth2 客户端）→ P6-35（Kafka）。根 `pom.xml` 的模块列表：只有 P6-31。
- `docs/02-architecture/overview.md`：P6-20 改第 2 节（新增 `integration` 模块），P6-31 改第 1 节（新增 `wp-instances`），两处不同小节，可以并行。`ModularityTest` 的 `EXPECTED`：只有 P6-20。
- 文档：`docs/api/README.md` 只有 P6-21；`docs/api/projection.md` 只有 P6-33；`docs/api/oidc.md` 为 P6-51 → P6-39；`docs/api/kafka.md` 只有 P6-35。
- Web 端：`src/shared/api/queryKeys.ts` 由 P6-22、P6-28、P6-38 修改，串行为 P6-22 → P6-28 → P6-38。合并实体（P6-25）与上传合并（P6-28）各建独立的功能目录，通过工作台注册表接入；webhook 标签页（P6-22）通过 `settingsTabs.ts` 的自动注册接入；三者都不改其它功能目录。`frontend/wp-web/package.json` 与锁文件：P6-42 → P6-41。

### 规划卡的编号规则

P6-34、P6-36、P6-40、P6-43 ~ P6-48 这九张规划卡都会编写新卡、修改本文件，并可能分配新的 Flyway 版本号与 BS 编号。为避免冲突：

- 规划卡一次只做一张：开工前确认没有其它规划卡处于 `in_progress`（`python tools/task.py next` 与 `tasks/INDEX.md` 可以看到状态）；
- 新卡编号取开工时 main 上的最大编号 + 1；新的 Flyway 版本号取当时 main 上已分配（含已规划未实现）的最大版本 + 1，并在本文件的共享文件小节中登记；BS 编号同理；
- 分支名规则只允许两位卡号，P6 最多到 P6-99。P6-43 表单拆卡用到 P6-73；九张规划卡的产出若可能超过 P6-99，由人决定是否开新的阶段目录。
- 2026-11-24 人已决定开新的阶段目录：P6-47、P6-48、P6-63、P6-40 拆出的卡分别放在 `tasks/P7/`、`tasks/P8/`、`tasks/P9/`、`tasks/P10/`，卡号在各自阶段内从 -01 起；Flyway 版本号、BS 编号、ADR 编号仍按上一条全局递增。见文末「规划卡暂缓与新阶段目录」。

## P6-32 首次运行与拆卡（2026-11-21）

P6-32 首次运行（`docs/perf/instances-poc-2026-11-20.md`）有 3 项未达标：Q3（堆垛机某天任务 → 托盘 → 批次，三跳）p95 94,403.54 毫秒（门禁 ≤ 200 毫秒）；W1 吞吐 167.6 次/秒（门禁 ≥ 200）；W1 单次写入 p95 93.66 毫秒（门禁 ≤ 50 毫秒）。Q1、Q2、Q4、Q5 和两次运行行数一致都达标。根因分析（`docs/perf/instances-poc-2026-11-20-root-cause.md`）的结论：主要是测法缺陷，基准用不带连接池的 `PGSimpleDataSource`，每条 SQL 都新建一条物理连接（约 20 毫秒）；生产代码 `TraceQueries.follow` 逐节点查询，Q3 一次调用发出 4,617 条 SQL，把这个开销放大到 94 秒。现有证据不足以否定 ADR-013 的实例库模型，也不能作为评估 Apache AGE 的依据。人要求按根因分析拆卡：

| 卡片 | 内容 |
|---|---|
| P6-53 | `TraceQueries.follow` 改为每一跳一条 SQL（`= any(?)` 批量取下一跳，一次调用一个连接），签名和结果不变；测试断言单次调用的连接数与语句数 |
| P6-54 | 基准测法修订（只改测试代码）：改用 HikariCP 连接池（test 作用域，版本由 Spring Boot BOM 管理），采样前填满连接池；报告记录连接开销基线、W1 按异常类型统计的失败次数与连接池配置；超时降到 3 小时 |
| P6-32 | 两张修复卡合并后重跑；语料、规模、查询、W1 参数和门禁阈值不改 |

依赖：P6-31 → {P6-53、P6-54} → P6-32 → P6-33。两张修复卡可以并行。共享文件：`TraceQueries.java` 只由 P6-53 修改；`InstanceBenchmark.java`、`WarehouseCorpus.java` 和 `backend/wp-instances/pom.xml` 只由 P6-54 修改。两张修复卡都不新增 Flyway 迁移，不改 `docs/02-architecture/overview.md`，也不改 OpenAPI。重跑仍不达标时，由人按根因分析最后一节选择下一步。本次拆卡用掉 P6-53、P6-54 两个编号。

## 实例库服务化（P6-34，2026-11-22）

依据：P6-32 重跑报告 `docs/perf/instances-poc-2026-11-21.md`（门禁全部达标）；P6-33（定义域 / 值域校验；必填、基数、枚举列为需扩展投影表）；ADR-013 第 4、5 节；P6-08 outbox 中继（进程内 `OutboxConsumer`）；P6-13 投影只读账号与 `project_state.rev_no`。planner / architect 审查后修订了租户边界、Flyway 归属与卡拆分（见下）。

### 决定（待人在提交前确认「服务形态」）

| # | 议题 | 决定 |
|---|---|---|
| 1 | 服务形态 | **同仓独立 Spring Boot 服务** `backend/wp-instances-app`，依赖纯 JDBC 库 `wp-instances`；独立数据库 `wp_instances`。与 ADR-013「独立服务、独立数据库」一致，**不**改 ADR-013，也**不**放进 wp-app Modulith。 |
| 2 | 写入接口与租户 | REST 批量写入（upsert / link / unlink / retire）；调用方提供 `at`；认证用**实例库自己的** API Key（**不**复用编辑器密钥）。**首期单租户**：配置项 `wp.instances.project-id` 指定唯一允许的本体项目；路径中的 `{projectId}` 必须与之相等，否则 403；IRI 表仍为全局（V1 无 `project_id` 列）。多项目隔离（表加 `project_id` 或按项目分库）与按项目绑键留给后续卡。幂等键见 P6-64。 |
| 3 | 规则刷新 | 投影表是编辑器 outbox 的**物化面**；跨进程不能挂 `OutboxConsumer`，故实例服务**写前核对** `projection.project_state.rev_no`（`CachedRuleSource`），等价于消费投影进度，**不**改 ADR-013 原文。本体变严后已有实例：**不回溯改写**；新写入按当前规则；漂移报告另开卡。首期校验仅为定义域 / 值域 / 类与属性存在性（**部分**满足 ADR-013 §4；必填 / 基数 / 枚举见 P6-63）。 |
| 4 | 必填 / 基数 / 枚举 | **首期不做**；P6-63 产出 ADR 草案（提议）与编辑器侧拆卡。 |
| 5 | 性能 | P6-32 已达标 → **不**加索引 / 物化路径 / AGE / 图库 ADR。 |
| 6 | 部署与运维 | Compose：**同一 Postgres 实例上的第二库** `wp_instances`（本地）；非开发环境应使用独立实例 / 独立备份。文档含备份、监控、投影只读账号、反向代理限流建议。网络写入基准见 P6-62（门禁锁定如下）。 |
| 7 | 契约版本 | **要**：`project_state.contract_version`（编辑器 Flyway **V9**，依赖 V8 已在 main）；实例库 `EXPECTED_VERSION` 不一致则拒绝写入并打 ERROR 日志（期望 / 实际）。破坏性投影变更须同提交递增两侧常量。 |

**P6-62 门禁（锁定）**：冒烟模式只写报告、不判定；全量模式（`-Dbench=true` 且非 smoke）门禁为单批 p95 ≤ 100 ms **且** 失败率 = 0；吞吐只记录不定门禁。不达标 → `task.py block`，不改生产代码。

### 展开卡片

| 卡片 | 内容 | 模块 |
|---|---|---|
| P6-55 | 编辑器：`project_state.contract_version`（V9）与投影文档 | wp-app/projection |
| P6-56 | 内核：按 `rev_no` 刷新的规则缓存 + 契约版本校验 | wp-instances |
| P6-57 | **PoC**：`wp-instances-app` 骨架（双数据源、内核+app 两套 Flyway、liveness/readiness） | wp-instances-app |
| P6-58 | 独立 API Key（bootstrap + SQL；无管理 REST） | wp-instances-app |
| P6-59 | REST 批量写入（调用方时间戳、校验、单租户校验） | wp-instances-app |
| P6-60 | REST 读取与追溯 | wp-instances-app |
| P6-61 | Compose（同实例第二库）与运维说明 | root |
| P6-62 | 经网络的端到端写入基准 | wp-instances-app |
| P6-63 | 规划卡：必填 / 基数 / 枚举 → ADR 草案与拆卡 | tasks/P6 |
| P6-64 | 写入幂等键（`Idempotency-Key`）与 instances OpenAPI 初稿 | wp-instances-app |

依赖（`A → B` 表示 B 依赖 A）：P6-37 → P6-55（**仅** Flyway V8→V9 串行，与 OIDC 功能无关；V8 进 main 后方可开工）；{P6-33、P6-55} → P6-56；P6-31 → P6-57 → {P6-58、P6-61}；{P6-56、P6-58} → P6-59 → P6-60 → P6-64；{P6-64、P6-61} → P6-62；P6-33 → P6-63。P6-57 与 P6-55 可并行。

### 共享文件与迁移号（服务化）

- 编辑器 Flyway：P6-55 用 **V9**。已分配 V6（P6-20）、V7（P6-29）、V8（P6-37）。
- 内核 Flyway `classpath:db/instances`：仍只有 **V1**（P6-31）；**服务卡不得往此目录加脚本**。
- 应用 Flyway `classpath:db/instances-app`（由 P6-57 挂上，启动时先 `InstanceSchema.migrate` 再跑 app 位置）：P6-58 用 **V1**（API Key）；P6-64 用 **V2**（幂等键）。与编辑器、内核迁移号无关。
- 根 `pom.xml`、`docs/02-architecture/overview.md` 第 1 节：只有 P6-57。
- `compose.yaml`、`docker/`：只有 P6-61。
- `docs/api/instances.md`：串行 **P6-58 → P6-59 → P6-60 → P6-64**；`docs/api/instances-openapi.json` 只由 P6-64 新增。不改编辑器 OpenAPI / `@wp/api-types`。
- `docs/api/projection.md`：P6-55、P6-56；扩展列归 P6-63。

## 图数据库发布器评估（P6-36，2026-11-22）

依据：ADR-013 第 5 节；P6-32 重跑报告 `docs/perf/instances-poc-2026-11-21.md`；P6-34 决定第 5 条（达标 → 不加 AGE / 图库 ADR）；P6-09 ~ P6-13 本体投影表；P6-14 ~ P6-16 MCP 只读工具；P6-35 Kafka 发布器（可选下游）。**不修改 ADR-013**，也不新建 ADR 草案或实现卡。

### 对照 ADR-013 第 5 节四个条件

| # | 条件（满足任一则另开 ADR） | 当前是否满足 | 依据 |
|---|---|---|---|
| 1 | 需要在实例上执行标准 SPARQL 查询或 OWL 推理 | **否** | §5 本条针对**实例**上的 SPARQL / OWL 推理：当前无产品或任务卡要求对外暴露此类端点；实例写入校验是封闭世界的定义域 / 值域守门（P6-33），**不是** OWL 推理。TBox 侧 Agent / BI 查询已由 §2 的投影表 SQL（P6-09 ~ P6-13，50 万公理下 Q1~Q3 p95 达标）与 MCP 只读工具（P6-14 ~ P6-16）覆盖，编辑器本体查询仍走内存 OWL API（§1）。 |
| 2 | AGE 的 PoC 中，代表性深层追溯 p95 超过 1 秒 | **否** | P6-32 用递归 CTE / 多跳 JOIN（**未启用 AGE**）即全部达标：Q4 深层追溯 p95 = 98.60 ms，Q5 反向追溯 p95 = 4.64 ms（门禁 ≤ 1000 ms）。P6-34 已据此决定不加索引 / 物化路径 / AGE / 图库 ADR。首次未达标（2026-11-20）已归因于测法与 N+1 SQL，不能作为 AGE 或图库的依据。 |
| 3 | 关系边数量超过约 1 亿条，且以多跳遍历为主要负载 | **否** | PoC 语料（诺和诺德仓库规模）`links` ≈ 310 万条（约 3.1×10⁶），低于 1 亿约两个数量级；多跳追溯已在门禁内。尚无生产实例库边数接近 1 亿的证据。 |
| 4 | 需要图算法（最短路径、社区发现等） | **否** | 候选清单与已完成能力中均无最短路径、社区发现等图算法需求；代表性负载是固定路径的追溯与投影 SQL，不是通用图算法。 |

### 决定（待人在提交前确认「评估结论」）

**暂不做** outbox → 图数据库 / RDF 三元组库发布器。不新建 ADR，不拆 PoC / 实现卡。候选清单中「outbox 发布器 → 图数据库」保持**未排期**（第二轮建议列写为「未排期（评估结论：暂不做）」）。说明：ADR-013 §5 的四个条件针对**实例库侧**是否升级到专用图库；「修订 / TBox → 图库给 Agent」另因 §2 已选定投影表（外加 MCP）而不做。

已有可替代路径（无需图库即可服务 AI Agent / 外部查询）：

- **TBox**：投影表（SQL）+ MCP 只读工具；
- **ABox（运行期）**：实例库 PostgreSQL + 递归 CTE；深层追溯未触发 AGE；
- **事件扇出（仅修订 / TBox）**：若外部系统要订阅本体修订，优先用已排期的 Kafka 发布器（P6-35）；将来若做「修订 RDF / 图库投影」，可作为 Kafka 下游，而不是现在再挂一个编辑器 outbox 消费者。实例侧图库升级不走这条路径（见下）。

### 重新评估的触发条件

出现以下**任一**情况时，重新开规划卡（或直接按 ADR-013 §5 另开 ADR 草案），不要在本结论上硬做：

1. **产品明确要求**在实例（或完整 RDF 导出）上提供标准 **SPARQL** 端点，或需要 **OWL 推理**（开放世界推断）而非封闭世界写入校验；
2. 实例库在**启用 AGE 的 PoC**（或等价测法）中，代表性深层追溯 **p95 > 1 秒**，且索引 / 物化路径等关系型手段已穷尽仍不达标；
3. 生产或规划中的关系边规模 **超过约 1 亿条**，且负载以多跳遍历为主；
4. 出现**图算法**产品需求（最短路径、社区发现、中心性等），而 PostgreSQL / AGE 无法以可接受成本满足；
5. MCP / 投影表 / 实例库 SQL **无法**覆盖某类已确认的 AI Agent 查询，且改投影形状的成本明显高于引入 RDF / 属性图库。

重新评估时仍对比：Neo4j + neosemantics、Jena Fuseki、GraphDB、继续投影表 + AGE。同步方式按触发原因分支：

- **TBox / 修订 RDF 导出或外部订阅本体变更**：优先订阅 P6-35 Kafka，其次直接消费编辑器 outbox（避免再挂一个与编辑器同进程的图库消费者）。
- **ADR-013 §5 的实例侧升级**（实例 SPARQL、AGE 失败、边规模、图算法）：从实例库写路径或其自身 outbox / 变更流同步，**不要**默认走编辑器 Kafka。

## P6-42 首次执行与修复卡（2026-11-22）

P6-42 的评估已经完成（`docs/03-plan/typescript-7-evaluation.md`，结论保持 TypeScript 6.0.3），但验收命令 `pnpm -C frontend --filter wp-web run verify` 因一条与 TypeScript 无关的用例失败，被标为 `blocked`。复盘结论：`WorkspacePage.spec.ts` 的 “opening with ?entity= shows the same IRI in the detail panel” 从 P4-09（`ac0d7ac`，2026-11-13）起就在 `main` 上失败。P4-09 为 `CLASS` 注册了 `ClassFrameEditor`，而这条页面用例使用收集全部 `register.ts` 的默认注册表，详情面板渲染的是真实编辑器，未 mock 的 `/frames/class` 请求让它停在“加载中…”。在 `bf28b23`（P4-09 合并前）通过，在 `1347beb`、`0f88386`、`c32552f` 上都稳定失败。CI 的 `frontend` job 只运行安装与 `@wp/api-types` 的检查，不运行 wp-web 的 `verify`，所以没有被拦下。全量 `vitest run` 时还观察到 `authGuard.spec.ts` 一条用例偶发超过 5 秒超时。

| 卡片 | 内容 |
|---|---|
| P6-65 | 只改测试：`WorkspacePage.spec.ts` 用空注册表隔离（与 `EntityDetailPanel.spec.ts`、`HierarchyPanel.spec.ts` 的写法一致）；`authGuard.spec.ts` 把测试体内的 `import("./router")` 改为静态导入 |
| P6-42 | 状态改回 `todo`，依赖 {P6-07、P6-65}；重新执行时只复核上游版本并在评估文档中追加复核记录，不重复整套试升级。开工前由人删除已合并的远程分支 `feature/p6-42-web-typescript-7-evaluation` |
| P6-41 | 仍为 `blocked`：npm 上 `vue` 的 `latest` 为 3.5.46，3.6 只到 `3.6.0-rc.16`（2026-11-20）。补记依赖说明：P6-42 完成后，本卡只等 Vue 3.6 正式版 |

依赖：P6-65 → P6-42 → P6-41。共享文件：`WorkspacePage.spec.ts`、`authGuard.spec.ts` 只由 P6-65 修改；`frontend/wp-web/package.json` 与锁文件仍按 P6-42 → P6-41 串行。P6-39（企业微信登录）按人的要求本次不处理，保持原状。本次用掉 P6-65 一个编号。

不建卡、由人处理的事项：

- CI 的 `frontend` job 增加 wp-web 的 `verify`（`.github/workflows/ci.yml` 属于开发标准文件，由人另开分支修改），避免同类失败再次只在本地暴露。
- `main` 上 CI 的 `java` job 从 2026-11-14 前后起持续失败（`AdminUserManagementTest` 2 条断言失败）。Java 卡的全量检查是 `./mvnw -q verify`，需要先查清根因，再决定是否建修复卡。

## 表单（P6-43，2026-11-23）

依据：`docs/01-rea/forms.md`；BS-16 ~ BS-18；旧工程 `server/form`、`shared/form`、`client/form`；P3-15 ~ P4-22 Frame 读写；P6-26 `ChangePipeline` 期望 head；P2 迁移包格式。planner / architect 审查后若调整首期边界，以 EPIC 本小节与卡片为准。

### 决定（待人在提交前确认「首期范围」）

| # | 议题 | 决定 |
|---|---|---|
| 1 | 首期目标 | **实体表单运行时**：按选择器取表单并投影；**TEXT / NUMBER**（仅 `PROPERTY` 绑定）可编辑并经 `ChangePipeline` 写回公理；其它控件只读占位。描述符经 REST 管理。 |
| 2 | 条件选择器 | **纳入简化版**：criteria 全量 jsonb 存储；匹配仅 `EntityTypeIsOneOf` + `SubClassOf`（ALL/ANY；`SubClassOf`=任意祖先）。criteria 树任一未支持节点 → **整条**选择器不命中。完整 MatchingEngine 不在首期（P6-46 亦不移植；另议 Query/匹配规划卡）。 |
| 3 | 表格 / 子表单 | **首期不做可写**；投影返回空/占位，保存时若 edited≠pristine 则拒绝。 |
| 4 | 表单设计器 Web UI | **首期不做**（可用 API / 迁移导入描述符）。 |
| 5 | 旧数据迁移 | **纳入**：导出/导入 `Forms` + `FormSelectors`；`formatVersion` 仍为 1（新文件可选；导出**始终写空文件**）；迁移报告警告含未支持节点的选择器。 |
| 6 | 并发 | 保存同时校验 **`expectedHead`** 与 **pristine≡当前运行时**（两层 409：`STALE_HEAD` / `FORM_DATA_CHANGED`）。 |
| 7 | 模块 | 新 Modulith 模块 **`form`**：仅描述符/选择器持久化与管理命令（deps：`bus`、`access`、`shared`；**不**直接调 `ChangePipeline`）。实体表单读写命令放在 **`ontology`**（符合 overview「ProjectRuntime 处理器在 ontology」）。允许依赖 `form` 的模块：`web`、`migration`、**`ontology`（只读仓储/门面，加载描述符与选择器）**。内核 `wprebuild.kernel.form` 放变更生成器。 |
| 8 | 不做 | Collections 表单、`FormDataRepository`、跨项目复制 UI、IMAGE 上传、完整 MatchingEngine、旧系统漏检的无权限写（新系统一律校验 `EDIT_FORMS`）。 |

### 展开卡片

| 卡片 | 内容 | 模块 |
|---|---|---|
| P6-66 | 表单表结构 V10 + JDBC 仓储 + Modulith `form` 壳（含 `web`/`migration`→`form`） | wp-app/form |
| P6-67 | `EDIT_FORMS` + 描述符/选择器 CRUD（Handler 在 form；Controller 在 web） | wp-app/form |
| P6-68 | 内核：表单数据 → 公理变更生成器（BS-18 子集 + pristine 锚定） | wp-kernel |
| P6-69 | 按实体取表单 API（BS-17：匹配 + PROPERTY 投影） | wp-app/ontology |
| P6-73 | 保存实体表单 API（BS-18 + 双层 409） | wp-app/ontology |
| P6-70 | Web：实体表单面板（TEXT/NUMBER 读写，其它只读） | wp-web |
| P6-71 | 迁移工具：导出 Forms / FormSelectors（含选择器警告） | wp-legacy-migrator |
| P6-72 | 后端：导入交换包中的表单描述符与选择器 | wp-app/migration |

依赖（`A → B` 表示 B 依赖 A）：P6-55 → P6-66 → P6-67；P6-67 → P6-69；{P6-68、P6-69} → P6-73 → P6-70；P6-26 → P6-68；P6-66 → P6-71；{P6-66、P6-71} → P6-72。P6-68 与 P6-66/67/71 可并行。OpenAPI 全局串行接在 P6-51 之后：… → P6-51 → P6-67 → P6-69 → P6-73。

### 共享文件与迁移号（表单）

- 编辑器 Flyway：P6-66 用 **V10**（**P6-55 V9 进 main 后方可开工**）。已分配 V6…V9 不变。
- `docs/02-architecture/overview.md` 第 2 节、`ModularityTest`、`web`/`migration`/`ontology` 的 `allowedDependencies`：只有 **P6-66**（一次性登记 `form` 及 `ontology|web|migration → form` 边）。
- `access/Action.java`、`ProjectRole.java`、`BS10_AccessServiceTest`：只有 P6-67。
- `docs/api/openapi.json`、`@wp/api-types`、`OpenApiResponseSchemasTest`：串行 **P6-51 → P6-67 → P6-69 → P6-73**（P6-67 的 `depends_on` 含 P6-51 仅为此串行）。
- `docs/02-architecture/interchange-format.md`：串行 **P6-71 → P6-72**（不升 `formatVersion`；IC 增补表单文件；始终导出空文件）。
- `docs/01-rea/behavior-specs.md` BS-16~18 / `forms.md`：本规划卡已写；实现卡只引用不改规格语义（发现偏差写阻塞）。
- Web：表单功能目录与相关 `queryKeys` 只由 P6-70 改。

本次拆卡用掉 P6-66 ~ P6-73（其中 P6-73 为保存 API）八个编号。

## 讨论（P6-44，2026-11-23）

依据：`docs/01-rea/discussions.md`；BS-19 ~ BS-20；旧工程 `server/issues`、`shared/issues`、`client/issues`；P6-03 事件推送；P6-24 合并实体；BS-10 `CAN_COMMENT` 占位。planner / architect 审查后若调整首期边界，以 EPIC 本小节与卡片为准。

### 决定（待人在提交前确认「首期范围」）

| # | 议题 | 决定 |
|---|---|---|
| 1 | 首期目标 | **实体讨论运行时**：按实体列线程；新建线程 / 回复；编辑与删除**自己的**评论；开关线程；有评论实体列表；Web 讨论面板。 |
| 2 | Markdown 与 XSS | **服务端** CommonMark（+ Autolink / Strikethrough / Tables / Ins）渲染并**消毒** HTML；持久化 `body` + `rendered_body`；客户端展示 `renderedBody`，不以客户端 Markdown 为安全边界。 |
| 3 | 提及 | **仅** `@userId` / `@{name}` 特殊渲染（供日后邮件）；`#N` Issue、`Rn`、实体字面量首期不做特殊解析。 |
| 4 | 邮件通知 | **首期不做**；与关注（P6-45）共用日后的 SMTP / 投递能力。讨论 API 仍发域事件，便于后续订阅。 |
| 5 | 事件与 SSE | 扩展 `ProjectEvent`：`DiscussionThreadCreated`、`CommentPosted`、`CommentUpdated`、`CommentDeleted`（新）、`DiscussionThreadStatusChanged`；`revNo` = 当前 head（同 `PermissionsChanged`）。后端事件 + `@wp/api-types` + Web 穷尽处理放在相邻卡。不进项目 webhook。 |
| 6 | 合并实体 | **纳入**：`DELETE_MERGED_ENTITY` 成功后把源实体线程改挂目标；`DEPRECATE_MERGED_ENTITY` 不转移。经 Spring 事件，不改内核生成器。 |
| 7 | 旧数据迁移 | **纳入**：导出/导入 `EntityDiscussionThreads`；`formatVersion` 仍为 1；导出始终写文件（可空）。 |
| 8 | 模块 | 新 Modulith 模块 **`discussion`**：deps `bus, access, shared`；`web` / `migration` / `events` 允许依赖它；`ontology` **不**直接依赖（合并用 Spring 事件）。 |
| 9 | 不做 | Slack、Issue/Milestone、`EDIT_ANY_OBJECT_COMMENT`、邮件、webhook `COMMENT_POSTED`、旧系统漏检的“改他人评论”。 |
| 10 | 新依赖 | P6-76 允许引入 CommonMark（含旧系统同款扩展）与 OWASP Java HTML Sanitizer（或执行记录中写明的同等消毒库）；不升 Spring Boot / OWL API。 |

### 展开卡片

| 卡片 | 内容 | 模块 |
|---|---|---|
| P6-74 | 讨论表结构 V11 + JDBC 仓储 + Modulith `discussion` 壳 | wp-app/discussion |
| P6-75 | 填实 `CAN_COMMENT` 四类动作（Action / ProjectRole / BS-10 测试） | wp-app/access |
| P6-82 | `CommentRenderer`（CommonMark + 提及 + 消毒）与允许的 POM 依赖 | wp-app/discussion |
| P6-76 | 讨论 REST：线程/评论 CRUD、域事件（Controller 在 web；OpenAPI 路径） | wp-app/discussion |
| P6-77 | 讨论 → `ProjectEvent` + hub 同 head 唤醒 + OpenAPI / `@wp/api-types` | wp-app/events |
| P6-78 | Web：讨论面板 + 有评论实体列表 + `applyProjectEvent` 穷尽分支 | wp-web |
| P6-79 | 合并实体后转移讨论线程（BS-13；可最小改 `MergeEntitiesHandler`） | wp-app/discussion |
| P6-80 | 迁移工具：导出 EntityDiscussionThreads | wp-legacy-migrator |
| P6-81 | 后端：导入讨论线程（按 body 重渲消毒） | wp-app/migration |

依赖（`A → B` 表示 B 依赖 A）：P6-66 → P6-74（**仅** Flyway V10→V11）；P6-74 → P6-82 → P6-76；P6-75 → P6-76；P6-73 → P6-76（**仅** OpenAPI 串行）；P6-76 → P6-77 → P6-78；{P6-74、P6-24} → P6-79；P6-74 → P6-80；{P6-74、P6-80、P6-82} → P6-81。P6-74 与 P6-75 可并行；P6-82 与 P6-75 可并行；P6-79 / P6-80 可与 P6-76 并行（不共享 OpenAPI 时）。

### 共享文件与迁移号（讨论）

- 编辑器 Flyway：P6-74 用 **V11**（**P6-66 V10 进 main 后方可开工**）。已分配 V6…V10 不变。
- `docs/02-architecture/overview.md` 第 2 节、`ModularityTest`：只有 **P6-74**（登记 `discussion` 及 `web|migration|events → discussion`）。
- `access/Action.java`、`ProjectRole.java`、`BS10_AccessServiceTest`：只有 **P6-75**（与 P6-67 的 `EDIT_FORMS` 串行：P6-67 → P6-75）。
- `wp-app/pom.xml` 渲染相关依赖：只有 **P6-82**。
- `docs/api/openapi.json`、`@wp/api-types`、`OpenApiResponseSchemasTest`：串行 **… → P6-73 → P6-76 → P6-77**。
- `ProjectEvent` 密封接口、`EventTranslator`、`ProjectEventHub.takeWaiters`、`applyProjectEvent` / `projectEvents`：P6-77（后端含同 head 唤醒）与 P6-78（前端）分工。
- `docs/02-architecture/interchange-format.md`：串行 **P6-80 → P6-81**（不升 `formatVersion`）。
- `MergeEntitiesHandler` / 合并后 Spring 事件：只有 **P6-79**（允许该 Handler 最小发布改动）。
- `docs/01-rea/behavior-specs.md` BS-19~20 / `discussions.md`：本规划卡已写；实现卡只引用不改规格语义。

本次拆卡用掉 P6-74 ~ P6-82（其中 P6-82 为渲染器）九个编号。

## 关注（P6-45，2026-11-23）

依据：`docs/01-rea/watches.md`；BS-21 ~ BS-22；旧工程 `server/watches`、`shared/watches`、`client/watches`；P6-08 / P6-21 outbox 登记与投递；BS-08 层级；P6-44 已把讨论邮件留给本卡的 SMTP。planner / architect 审查后若调整首期边界，以 EPIC 本小节与卡片为准。

### 决定（待人在提交前确认「首期范围」）

| # | 议题 | 决定 |
|---|---|---|
| 1 | 首期目标 | **实体关注运行时** + **按修订的邮件通知**：设置 ENTITY/BRANCH；列出我的关注；Web 关注入口；修订提交后匹配关注并发邮件。 |
| 2 | 邮件发送能力 | **独立两卡**，放在 `integration` 模块，供关注与**日后**讨论邮件共用：P6-83 登记队列（`mail_deliveries` V12 + `MailEnqueue` + `MailProperties`，不引入新依赖）；P6-87 投递线程（新增 `spring-boot-starter-mail`，认领/重试发送）。未配置 SMTP 主机或 `wp.mail.enabled=false` 时登记为 `SKIPPED`，不阻塞 outbox。 |
| 3 | 通知触发源 | **outbox 消费者**（非写路径进程内 Frame 事件）：按修订求受影响实体 → BS-21 匹配；模型未应用该修订时 `OutboxRetryLater`（同 P6-12）。受影响实体 = **公理主语**（与 `EntityFrameChanged` 同规则，也与旧系统一致），**不用** `revision_subjects` 全签名索引；大修订超过主语阈值时只做直接匹配。 |
| 4 | 同一修订多次命中 | **每用户每修订一封**，正文列全部命中实体（旧系统按实体多次发送）。 |
| 5 | 是否排除修改者 | **排除**本修订作者（**新系统修正**；旧关注通知不排除）。仍要求收件人有 `VIEW_PROJECT` 与邮箱。 |
| 6 | 退订与频率 | 退订 = 清除关注；不做全局退订表、不做跨修订摘要/限频。 |
| 7 | 站内通知与关注事件 | **首期不做**通知中心；已有 SSE `EntityFrameChanged` 等实时事件即可。关注增删**不发**项目事件：`ProjectEventHub` 向项目全部订阅者广播，`WatchAdded{userId,…}` 会暴露个人关注；Web 写成功后自行刷新。 |
| 8 | 权限 | `WATCH_CHANGES` 归入 `CAN_VIEW`；所有关注读写 API 必须声明该动作。 |
| 9 | 模块 | 新 Modulith 模块 **`watch`**：deps `bus, access, shared, project, revision, ontology, integration`（读运行时/层级做匹配、读修订内容、取项目显示名、登记邮件）；`web` / `migration` → `watch`（`events` 不依赖 `watch`）。 |
| 10 | 迁移 | **纳入**导出/导入 Mongo `Watches`；`formatVersion` 仍为 1。 |
| 11 | 不做 | 关注变更历史全表扫描 API、合并时转移关注、移动推送、讨论评论邮件、站内收件箱、把多收件人放同一 TO、关注项目事件与多标签页同步、`mail_deliveries` 历史清理。 |

### 展开卡片

| 卡片 | 内容 | 模块 |
|---|---|---|
| P6-83 | 邮件登记：`mail_deliveries` V12 + `MailEnqueue` + `MailProperties`（`enabled`/`host`/`from`/`link-base-url`；关闭时 `SKIPPED`） | wp-app/integration |
| P6-84 | 关注表结构 V13（外键、非空实体列）+ JDBC 仓储 + Modulith `watch` 壳 + `WatchSubjects`（公理主语规则） | wp-app/watch |
| P6-85 | `WATCH_CHANGES` 动作与 `CAN_VIEW` 映射 | wp-app/access |
| P6-86 | 关注 REST：按实体 get/set、列出我的关注（含显示名；Controller 在 web；OpenAPI） | wp-app/watch |
| P6-87 | 邮件投递线程：`spring-boot-starter-mail` + SMTP 配置 + 认领/重试发送（**审查修订后改作此用途**） | wp-app/integration |
| P6-88 | outbox 消费者：用 `WatchSubjects` 求主语、匹配关注并登记邮件（部分确认 / `OutboxRetryLater`、主语阈值） | wp-app/watch |
| P6-89 | Web：实体右键菜单关注对话框 + 「我的关注」项目子路由 | wp-web |
| P6-90 | 迁移工具：导出 Watches | wp-legacy-migrator |
| P6-91 | 后端：导入交换包中的 Watches | wp-app/migration |

依赖（`A → B` 表示 B 依赖 A）：P6-74 → P6-83（**仅** Flyway V11→V12）；P6-83 → P6-84（**仅** V12→V13）；P6-83 → P6-87；P6-84 → P6-86；P6-77 → P6-85 → P6-86（P6-77 → P6-85 **仅** OpenAPI 串行：`permissions/me` 的动作枚举已发布）；P6-86 → P6-89；{P6-84、P6-83、P6-21} → P6-88；{P6-84、P6-81} → P6-90（P6-81 **仅**为迁移包文件串行）；{P6-84、P6-90} → P6-91。P6-85 与 P6-84 可并行；P6-87 与 P6-84 / P6-88 可并行（P6-88 只登记，不依赖投递线程）；P6-90 可与 P6-86 并行（不共享 OpenAPI 时）。端到端发信需要 P6-87 与 P6-88 都合并。

### 共享文件与迁移号（关注）

- 编辑器 Flyway：P6-83 用 **V12**，P6-84 用 **V13**（**P6-74 V11 进 main 后方可开工 P6-83**）。已分配 V6…V11 不变。
- `backend/wp-app/pom.xml`：只有 **P6-87** 增加 `spring-boot-starter-mail`（理由：JDK 无内置 SMTP 客户端；与 Spring Boot 4.1.1 对齐的官方 starter；讨论邮件日后复用同一依赖）。
- `integration/MailProperties`、`MailDeliveryRepository`：P6-83 新建 → P6-87 增补（串行）。
- `docs/02-architecture/overview.md` 第 2 节、`ModularityTest`：只有 **P6-84**（登记 `watch`，deps 含 `project, revision, ontology, integration`；`web|migration → watch`；`integration` 职责注明含邮件投递）。
- `access/Action.java`、`ProjectRole.java`、`BS10_AccessServiceTest`：只有 **P6-85**（与 P6-75 串行：P6-75 → P6-85）。
- `docs/api/openapi.json`、`@wp/api-types`、`OpenApiResponseSchemasTest`：串行 **… → P6-77 → P6-85 → P6-86**（同时只许一个写者；P6-85 给 `permissions/me` 的 `actions` 枚举加 `WATCH_CHANGES`，P6-86 加关注接口；之后接标签 P6-93）。
- `ProjectEvent` 密封接口、`EventTranslator`、`ProjectEventHub.takeWaiters`、`applyProjectEvent`：关注卡**都不改**（关注不发项目事件）；写者链由 P6-77 直接交给标签 P6-96 / P6-97。
- `docs/api/README.md` 邮件小节：只由 **P6-87** 追加。
- `docs/02-architecture/interchange-format.md` 与迁移包文件清单（`wp-legacy-migrator` 的 `export/PackageFiles`、`PackageVerifier`；`migration/MigrationPackageReader`）：串行 **P6-80 → P6-81 → P6-90 → P6-91 → P6-98 → P6-99**（不升 `formatVersion`）。
- `docs/01-rea/behavior-specs.md` BS-21~22 / `watches.md`：本规划卡已写（2026-11-24 审查修订见下）；实现卡只引用不改规格语义。

本次拆卡用掉 P6-83 ~ P6-91 九个编号。

### 审查修订（2026-11-24）

P6-45 合并后复核拆卡，按以下问题修订（分支 `docs/p6-watches-review-fix-cards`）。依据：新工程 `ProjectEventHub.publish`、`EventTranslator.frameSubjects`、`OntologyChange.signature()`、`OutboxRelay`（单线程）、`ProjectExistenceCheck`、`application.yaml`；旧工程 `HighLevelEventGenerator`、`AbstractHierarchyProvider.getAncestors`。

| # | 问题 | 修订 |
|---|---|---|
| 1 | 原 P6-87 的 `WatchAdded{userId, entity, watchType}` 经 `ProjectEventHub` 广播给项目全部订阅者并进入重放窗口，暴露个人关注；唯一受益方是同一用户的其它标签页 | 关注不发项目事件（BS-11、BS-21）；P6-86 不发域事件；P6-89 写成功后自行刷新；原 P6-87 撤销，编号改作邮件投递线程；P6-94 / P6-96 的依赖由 P6-87 改为 P6-86 / P6-77 |
| 2 | BS-22 用 `revision_subjects`（公理全签名）作受影响实体，与旧系统“公理主语”不同且未标明；又称与 `AffectedSubjects` 同集合，实际 `revision_subjects` 还含本体注解里的注解属性 | 受影响实体改为与 `EntityFrameChanged` 同一“公理主语”规则；P6-88 用 `RevisionLog.read` 读修订，不再增补 `RevisionHistoryQueries` |
| 3 | P6-88 要写项目名，`watch` 依赖里没有 `project`；要放链接，应用里没有站点对外地址配置 | P6-84 给 `watch` 增加 `project` 依赖；P6-83 的 `MailProperties` 增加 `link-base-url`（可空，未配置时不含链接） |
| 4 | 原 P6-83 含迁移、仓储、投递线程、SMTP 配置与文档，参照 P6-21（`WebhookDispatcher` 354 行）明显超过 400 行 | 拆为 P6-83（登记队列，S，不引入新依赖）与 P6-87（投递线程 + 新依赖，M） |
| 5 | 迁移包链只串了 P6-90 → P6-91，与讨论 P6-80 / P6-81 会同时改 `interchange-format.md`、`PackageFiles`、`MigrationPackageReader` | P6-90 增加依赖 P6-81 |
| 6 | `revision_subjects` 只有 IRI 没有类型；删除实体在 head 中查不到 | 由第 2 条解决（主语带类型，IRI 主语可按变更签名解析）；BS-21 写明祖先按匹配时模型计算的近似 |
| 7 | outbox 中继单线程，大修订的祖先展开会拖住投影、webhook、搜索 | BS-22 / P6-88：先读关注、无关注直接确认；锁外读 DB；主语阈值 `wp.watch.max-subjects-per-revision`（默认 5000） |
| 8 | BS-21 称“个体含类型类自身”与旧系统一致，实际旧 `getAncestors` 不含自身 | 改标为“新系统修正”，并提示不要移植相反的旧断言 |
| 9 | `watches.md` 漏记旧 Frame 事件去重写反（单条公理变更不发关注邮件） | `watches.md` §3.2、§6 补记 |
| 10 | P6-91 未处理用户不存在；P6-84 未写外键 | P6-84 写明外键；P6-91 跳过不在 `users` 表中的用户 |
| 11 | P6-86 列表未给显示名；P6-89 入口有歧义 | P6-86 列表返回显示名与类型；P6-89 入口定为 `EntityContextMenu` 与 `/projects/:projectId/watches` 子路由 |
| 12 | P6-88 含邮件 HTML 与主题拼装，未配安全审查 | P6-88 `ecc_extra` 增加 `security` |

修订后按 P6-45 的审查要求复审（planner、architect，均为 APPROVE WITH CHANGES），再按以下意见修订：

| # | 问题 | 修订 |
|---|---|---|
| 13 | `MailEnqueue` 若靠捕获 `DuplicateKeyException` 实现幂等，PostgreSQL 会让整个中继事务失效 | P6-83 规定 `on conflict (idempotency_key) do nothing`，并加“同一外层事务内重复登记后仍能提交”的测试 |
| 14 | P6-88 在批中途抛 `OutboxRetryLater` 会让整批回滚、反复重匹配 | P6-88 改为部分确认：滞后修订不是本批第一条时返回其前一条 id |
| 15 | `entity_watch` 实体列可空，唯一约束挡不住重复；时间列无类型 | P6-84 实体列 `not null`，`entity_type` 加取值 check，时间列 `timestamptz not null default now()` |
| 16 | `permissions/me` 的 `actions` 枚举已发布在 `openapi.json`，P6-85 / P6-93 改动作却不在 OpenAPI 串行链上 | 链改为 `… → P6-77 → P6-85 → P6-86 → P6-93 → P6-94`；两卡产出与验收加 OpenAPI |
| 17 | P6-88 规模接近上限，且卡号已满无法再拆 | `WatchSubjects` 及其测试移到 P6-84 |
| 18 | 导出格式未定、导入“无法解析”有歧义、链接未要求 URL 编码、项目状态检查未点名 API | P6-90 定为 `watches.jsonl` 与字段；P6-91 定义解析失败、点名 `MigrationPackageReader` 并要求在用户与项目之后导入；P6-88 / BS-22 要求 IRI URL 编码与属性转义；P6-88 用 `ProjectExistenceCheck.state()` |

architect 另提“引入 Actuator 后邮件健康检查会受 SMTP 影响”：`wp-app` 目前没有 Actuator，不适用，未改。

**未改动、待人决定**：
- **P6 卡号已用到 P6-99**。`plan_lint.py` 的 `ID_RE`（`^P\d-\d{2}$`）与分支名正则只接受两位卡号，本次只能复用 P6-87；为控制 P6-88 规模，把 `WatchSubjects` 移进了 P6-84，而不是新开卡。P6-47、P6-48 等规划卡拆卡前，需要人决定放宽编号规则（`tools/` 属开发标准文件）或另开阶段。（2026-11-24 人已决定另开阶段，见文末「规划卡暂缓与新阶段目录」。）
- 表单 P6-71 / P6-72 与讨论 P6-80 / P6-81 两条迁移链之间同样没有串行（P6-44 遗留），本次未改 P6-44 的卡。
- 表单 P6-67（`EDIT_FORMS`）与讨论 P6-75（评论动作）同样修改已发布的 `actions` 枚举，却不在 OpenAPI 串行链上（P6-43 / P6-44 遗留），本次未改。

## 标签（P6-46，2026-11-23）

依据：`docs/01-rea/tags.md`；BS-23 ~ BS-24；旧工程 `server/tag`、`shared/tag`、`client/tag`；P3-19 实体搜索（逆向确认旧系统**无**按项目 Tag 过滤搜索）；BS-10 / BS-11。planner / architect 审查后若调整首期边界，以 EPIC 本小节与卡片为准。

### 决定（待人在提交前确认「首期范围」）

| # | 议题 | 决定 |
|---|---|---|
| 1 | 首期目标 | **显式标签**：项目标签目录 CRUD（整表替换）、实体差分打标、层级/搜索节点展示、Web 设置与打标 UI、迁移。 |
| 2 | 条件自动打标签 | **首期不做** MatchingEngine / 条件编辑 UI。`criteria` 列 jsonb **只存不求值**（迁移保真）；日后再挂匹配引擎。 |
| 3 | MatchingEngine 规模 | 约 13 个 EntityMatch 根类型、~66 Criteria、~126 客户端 match 文件，与 Query 共用——单独为标签移植不划算。 |
| 4 | 「搜索过滤」 | 旧系统**没有**按项目 Tag 的服务端搜索过滤（仅层级 CSS「显示的标签」）。原产出中的搜索过滤卡改为：**EntityNode 附带显式标签**（P6-95）+ Web 可见性过滤；**不做**服务端 search-by-tag。 |
| 5 | 权限 | `EDIT_PROJECT_TAGS` → `CAN_MANAGE`；`EDIT_ENTITY_TAGS` → `CAN_COMMENT`；写接口均须服务端声明（修正旧 UpdateEntityTags 漏洞）。 |
| 6 | 修订 | 标签**不**产生修订；与项目设置同属编辑器元数据。 |
| 7 | 事件 | `ProjectTagsChanged` / `EntityTagsChanged` 进 `ProjectEventHub`（`revNo` = head）；不进 webhook。 |
| 8 | 模块 | 新 Modulith 模块 **`tag`**：deps `bus, access, shared`；`web` / `migration` / `events` → `tag`；**不**依赖 `revision` / `ontology`。 |
| 9 | EntityNode | 增加显式标签摘要字段；由 **web** 在层级/搜索响应组装时批量填充（避免 `ontology`→`tag`）。 |
| 10 | 迁移 | **纳入**导出/导入 Mongo `Tags` + `EntityTags`；`formatVersion` 仍为 1。 |
| 11 | 不做 | 条件自动打标、usage 计数、search-by-tag、webhook、本体变更重算标签事件。 |

### 展开卡片

| 卡片 | 内容 | 模块 |
|---|---|---|
| P6-92 | 标签表结构 V14 + JDBC 仓储 + Modulith `tag` 壳 | wp-app/tag |
| P6-93 | `EDIT_PROJECT_TAGS` / `EDIT_ENTITY_TAGS` 与角色映射 | wp-app/access |
| P6-94 | 标签 REST：项目目录 get/set、实体标签 get/update（OpenAPI） | wp-app/tag |
| P6-95 | EntityNode 显式标签字段 + 层级/搜索响应填充 | wp-app/web |
| P6-96 | 标签 → `ProjectEvent`（ProjectTagsChanged / EntityTagsChanged）+ hub + api-types | wp-app/events |
| P6-97 | Web：项目标签设置、实体打标、chips、显示过滤、`applyProjectEvent` | wp-web |
| P6-98 | 迁移工具：导出 Tags / EntityTags | wp-legacy-migrator |
| P6-99 | 后端：导入交换包中的 Tags / EntityTags | wp-app/migration |

依赖（`A → B` 表示 B 依赖 A）：P6-84 → P6-92（**仅** Flyway V13→V14）；P6-92 → P6-94；P6-86 → P6-93 → P6-94（OpenAPI 串行：动作枚举已发布；2026-11-24 关注审查修订前为 P6-87 → P6-94）；P6-94 → P6-95 → P6-96 → P6-97；P6-95 → P6-97；P6-91 → P6-98（**仅** interchange-format 串行）；P6-92 → P6-98；{P6-92、P6-98} → P6-99。P6-93 与 P6-92 可并行；P6-98 可与 P6-94 并行（不共享 OpenAPI 时）。P6-96 另硬依赖 P6-77 对同 head 非修订事件唤醒的改动（须纳入 Tag 事件；关注不再发项目事件）；P6-96 扩展 `ProjectEvent` 联合类型后须与 P6-97 紧邻合并以免打断 Web 穷尽检查。

### 共享文件与迁移号（标签）

- 编辑器 Flyway：P6-92 用 **V14**（**P6-84 V13 进 main 后方可开工**）。已分配 V6…V13 不变。
- `docs/02-architecture/overview.md` 第 2 节、`ModularityTest`：只有 **P6-92**（登记 `tag`，deps `bus, access, shared`；`web|migration|events → tag`）。
- `access/Action.java`、`ProjectRole.java`、`BS10_AccessServiceTest`：只有 **P6-93**（与 P6-85 串行：P6-85 → P6-93）。
- `docs/api/openapi.json`、`@wp/api-types`、`OpenApiResponseSchemasTest`：串行 **… → P6-86 → P6-93 → P6-94 → P6-95 → P6-96**（同时只许一个写者）。
- `EntityNode`（ontology dto）与 `ResponseSchemasCustomizer`：由 **P6-95** 扩展 tags 字段（默认空）；**web Controller** 在 `bus.execute` 之后批量填充（禁止 `ontology`/`search`→`tag`）。
- `ProjectEvent` 密封接口、`EventTranslator`、`ProjectEventHub.takeWaiters`、`applyProjectEvent`：P6-96（后端）与 P6-97（前端）分工；承接 P6-77。
- `docs/02-architecture/interchange-format.md`：串行 **P6-91 → P6-98 → P6-99**（不升 `formatVersion`）。
- `docs/01-rea/behavior-specs.md` BS-23~24 / `tags.md`：本规划卡已写；实现卡只引用不改规格语义。

本次拆卡用掉 P6-92 ~ P6-99 八个编号。

## 规划卡暂缓与新阶段目录（2026-11-24）

人要求：`tasks/INDEX.md` 中仍有规划卡影响开发计划（例如 P6-47）；为了不影响实现卡之间的依赖与推进顺序，把相关规划卡统一标为阻塞；规划卡今后拆出的任务各开一个一级目录，例如 P6-47 拆出的任务放在 P7。

### 范围

P6 中尚未完成的规划卡共四张：P6-47、P6-48、P6-63 的依赖都已完成，出现在「现在可以开工」中；P6-40 的依赖 P6-52 尚未完成，P6-52 合并后同样会出现。四张一起标为 `blocked`。P5-00 早已阻塞（移动端暂停），它的卡本来就放在 `tasks/P5/`，不变。

| 规划卡 | 状态 | 拆卡目录 | 新卡编号 | 说明 |
|---|---|---|---|---|
| P6-47 Manchester 语法编辑 | 待办 → 阻塞 | `tasks/P7/` | P7-01 起 | 人指定 |
| P6-48 MCP 写工具与人工确认 | 待办 → 阻塞 | `tasks/P8/` | P8-01 起 | |
| P6-63 投影约束扩展 | 待办 → 阻塞 | `tasks/P9/` | P9-01 起 | |
| P6-40 多实例部署 | 待办 → 阻塞 | `tasks/P10/`（预留） | P10-01 起 | 两位阶段号，见下文「两位阶段号」 |

阶段号的分配：P6-47 → P7 由人指定；其余按“已经可以开工的在前、卡号小的在前”。P6-40 的依赖链最长（P6-50 → P6-29 → P6-52），排在最后。

### 为什么标为阻塞

- 四张卡都不被任何卡依赖，标为 `blocked` 不改变实现卡之间的依赖关系。“可以开工”只计算 `todo` 的卡，阻塞的卡不再出现在 `tasks/INDEX.md`「现在可以开工」与 `python tools/task.py next` 中。
- `AGENTS.md` 第 1 节让 Agent 取编号最小的可开工卡。不阻塞时，可以开工的卡按编号依次是 P6-47、P6-48、P6-50、P6-57、P6-63、P6-65、P6-68，Agent 会先做两张规划卡。
- P6 卡号已用到 P6-99，规划卡原来的“编号接续当时的最大编号”在 P6 内已经无法执行。

### 新阶段目录的约定

- 每个目录先只有 `EPIC.md`：顶部是暂缓说明，正文是来源、候选项与编号规则。规划卡恢复后，把首期范围、展开的卡片、依赖关系与共享文件写进对应阶段的 `EPIC.md`，不再写进本文件。
- 卡号在各自阶段内从 -01 起；Flyway 版本号、BS 编号、ADR 编号全局唯一，仍按「规划卡的编号规则」取 main 上已分配的最大值 + 1。
- 新阶段的卡可以依赖 P6 的卡（例如 OpenAPI、`Action.java` 串行链上的最后一张），`depends_on` 直接写卡号。
- 规划卡仍然一次只做一张。
- `tasks/INDEX.md` 按 `tasks/P*/EPIC.md` 列出阶段：P7 ~ P10 在有卡片之前显示为“暂无卡片”，并链接 EPIC 与负责拆卡的规划卡（规划卡的 `module` 指向该阶段目录）；INDEX 末尾“各阶段的候选清单”一句同样自动列出全部 EPIC。

### 两位阶段号

原来卡号与分支名只允许一位阶段号（`P\d-\d{2}`），P10 放不了卡。人决定（2026-11-24）修改卡号定义并保留 P10：

- 卡号：`P` + 阶段号 0 ~ 99（不带前导零）+ `-` + 两位编号，例如 `P1-03`、`P10-01`；`P07-01`、`P100-01` 不合法。任务分支为对应的小写形式，例如 `docs/p10-01-<短描述>`；`Task:` 尾注同理。
- 同步修改的开发标准文件：`tools/plan_lint.py`（`ID_RE`；阶段与卡号改为按数字排序，P9 在 P10 之前；只有 EPIC、还没有卡片的阶段也列在 INDEX 中；INDEX 末尾按 `tasks/P*/EPIC.md` 自动列出候选清单）、`tools/task.py`（`next` 等处按数字排序）、`.cursor/skills/git-naming-conventions/scripts/validate_names.py`（任务分支与 `Task:` 尾注）、`.githooks/commit-msg`、`.githooks/pre-push`、`.github/workflows/ci.yml`（任务分支判断），以及 `git-naming-conventions` 的 `SKILL.md`、`reference.md`。新增测试 `tools/test_plan_lint.py` 与 `.cursor/skills/git-naming-conventions/scripts/test_validate_names.py`。
- 已有卡片的编号与顺序不变；单个阶段内仍最多 99 张卡。

### 恢复方式

人决定开始某张规划卡时：把该卡 `status` 改回 `todo` 并删除其「阻塞」小节，撤销对应阶段 `EPIC.md` 顶部的暂缓说明，合并后按 `python tools/task.py start <卡号>` 开工。远端已有 P6-47 的认领分支 `docs/p6-47-manchester-rea-planning`（2026-11-24 从当时的 main 创建，没有提交），恢复 P6-47 前由人删除它，否则 `start` 会提示远程已存在分支。（2026-11-28 起这四张卡已改为取消，恢复方式以下文「阻塞的规划卡改为取消」为准。）

## 阻塞的规划卡改为取消（2026-11-28）

人要求：把状态为“阻塞”、类型为 planning 的任务卡改为“取消”。

### 范围

| 规划卡 | 状态 | 拆卡目录 |
|---|---|---|
| P5-00 移动端（uni-app）MVP | 阻塞 → 取消 | `tasks/P5/` |
| P6-40 多实例部署 | 阻塞 → 取消 | `tasks/P10/` |
| P6-47 Manchester 语法编辑 | 阻塞 → 取消 | `tasks/P7/` |
| P6-48 MCP 写工具与人工确认 | 阻塞 → 取消 | `tasks/P8/` |
| P6-63 投影约束扩展 | 阻塞 → 取消 | `tasks/P9/` |

其它阻塞的卡（P6-28、P6-30、P6-39、P6-41、P6-61）是实现卡，不在本次范围内，保持 `blocked`。

### 新状态 `cancelled`

- 卡片状态新增 `cancelled`（INDEX 中显示为“取消”）：人决定不再做的卡。`tools/plan_lint.py` 要求取消的卡写「取消」小节说明原因；INDEX 的汇总行单列取消数，取消的卡不进入「现在可以开工」与「阻塞中」；依赖图用灰色虚线框表示。`python tools/task.py next` 本来只列 `todo` 的卡，不需要修改。
- 未取消的卡依赖已取消的卡时，`plan_lint` 报错：要么改依赖，要么一并取消。本次取消的五张卡都没有被任何卡依赖，实现卡的依赖关系不变。
- 同步修改：`tools/plan_lint.py`、`tools/test_plan_lint.py`、`tasks/TEMPLATE.md`（状态注释），以及 P5、P7 ~ P10 各 `EPIC.md` 顶部说明和 `docs/03-plan/roadmap.md`。

### 卡片与目录的处理

- 五张卡保留原文，`status` 改为 `cancelled`，末尾追加「取消」小节；原「阻塞」小节保留作历史记录，其中的“恢复方式”不再适用。
- P5、P7 ~ P10 的 `EPIC.md` 保留候选清单，作为日后重新规划的依据；INDEX 中 P7 ~ P10 仍显示为“暂无卡片”，规划卡标为“取消”。

### 重新启动

由人决定。把对应卡片的 `status` 改回 `todo`，删除「阻塞」与「取消」两节，撤销对应阶段 `EPIC.md` 顶部的说明，合并后按 `python tools/task.py start <卡号>` 开工；也可以另开一张新的规划卡。恢复 P6-47 前，如远端仍有认领分支 `docs/p6-47-manchester-rea-planning`，由人先删除。

## 企业微信登录挂起与 P11 收尾目录（2026-11-28）

人要求：与微信集成相关的功能改为“挂起”；新开 `tasks/P11/`，专门放 P6 的收尾和修复卡。

### 挂起

- 卡片状态新增 `suspended`（INDEX 中显示为“挂起”）：人主动暂停、等外部条件的卡。必须写「挂起」小节说明恢复条件；INDEX 的汇总行单列挂起数，并在「挂起中」单独列出；不进入「现在可以开工」与「阻塞中」；依赖图用浅紫色表示。与 `blocked` 的区别：`blocked` 是执行中遇到问题停下，`suspended` 是人决定暂停。`python tools/task.py next` 只列 `todo` 的卡，不需要修改。
- 与微信集成相关的未完成卡只有 P6-39（企业微信扫码登录），由 `blocked` 改为 `suspended`。P6-37、P6-51 已完成（通用的外部身份绑定与 OIDC 登录），P6-38 只在「不在本卡范围」中提到企业微信，均不变；移动端的“微信小程序”候选项随 P5-00 已取消，不变。
- 同步修改：`tools/plan_lint.py`、`tools/test_plan_lint.py`、`tasks/TEMPLATE.md`（状态注释）。

### P11 收尾与修复目录

- P6 卡号已用到 P6-99，P6 已有功能的收尾与修复卡放在 `tasks/P11/`，卡号从 P11-01 起，约定见 `tasks/P11/EPIC.md`。
- 首批两张：P11-01（Java 测试隔离，修复 `main` 上 Java CI 的失败）、P11-02（`/permissions/me` 动作枚举补齐 `UPLOAD_AND_MERGE` 两项）。P6-61 增加依赖 P11-01，P6-28 增加依赖 P11-02，两张卡的「阻塞」小节写明了解除方式。
- 编号顺序上 P6 的卡排在 P11 之前；P11-01 合并前 Java 卡的全量检查很可能失败，建议人指定先做 P11-01。

### 阻塞卡复核（2026-11-28）

| 卡 | 状态 | 说明 |
|---|---|---|
| P6-28 | 阻塞 → 待办 | 阻塞原因已由依赖 P11-02 表达，P11-02 合并前不会出现在可以开工的卡中 |
| P6-30 | 阻塞 → 待办 | 增加依赖 P6-65（`WorkspacePage.spec.ts` 失败的修复卡） |
| P6-61 | 阻塞 → 待办 | 阻塞原因已由依赖 P11-01 表达 |
| P6-41 | 阻塞 → 挂起 | 等 Vue 3.6 正式发布，外部条件无法写进 `depends_on` |

三张改回待办的卡沿用 P6-42 的做法：原「阻塞」小节改名为「首次执行记录（日期，当时标为 blocked）」保留。它们的产出（P6-30、P6-61）或阻塞记录（P6-28）已随各自的阻塞 PR 合入 `main`，远端仍有已合并的分支 `feature/p6-28-web-upload-merge`、`feature/p6-30-web-delete-user`、`chore/p6-61-instances-compose-ops`；开工前由人删除，否则 `start` 会提示远程已存在分支，而 `--resume` 会切到不含后续修复的旧分支。
