# 关注与邮件通知（Watches）逆向分析

对象：`../webprotege` 的实体关注与变更邮件子系统。方法见 `README.md`（五层）。本文件只写结论与出处；可测行为见 `behavior-specs.md` 的 BS-21 ~ BS-22。

路径缩写：`LEG` / `LEG_SHARED` / `LEG_TEST` 同 `behavior-specs.md`；客户端为  
`LEG_CLIENT = ../webprotege/webprotege-client/src/main/java/edu/stanford/bmir/protege/web/client`。

规模（约）：server-core `watches/` 14 文件；shared `watches/` 14；client `watches/` 6；另有 `GetWatchedEntityChangesActionHandler` 与邮件模板。

---

## 1. 边界与上下文

| 边界 | 内容 |
|---|---|
| 对内 | 用户对某个 OWL 实体登记 `ENTITY` 或 `BRANCH` 关注；实体 Frame 变更时按关注匹配收件人并发邮件 |
| 对外入口 | 仅 GWT RPC（无 Jersey REST）；客户端对话框 `WatchPresenter`、portlet `WatchedEntitiesPortletPresenter`、层级树右键 `WatchUiAction` |
| 不越界 | 不写修订；不替代 webhook / 讨论邮件；不负责站内通知中心 |
| 权限 | 读写关注：`WATCH_CHANGES`（旧 `PROJECT_VIEWER` 起）；列表关注变更历史另需 `VIEW_CHANGES`；收件人还需 `VIEW_PROJECT` |

与新工程：关注是**项目元数据**（PostgreSQL）；触发应对齐修订提交（outbox），而不是进程内 Frame 事件；邮件投递复用独立的 SMTP / 投递队列（与日后讨论邮件共用）。

---

## 2. 静态结构

### 2.1 概念层次

```
Project
└── Watch*                    （每用户每实体至多一条；类型可被覆盖）
    ├── userId
    ├── entity (OWLEntity)
    └── type ENTITY | BRANCH
```

- **`Watch`**：共享 DTO；字段 `userId`、`entity`、`type`。  
  出处：`LEG_SHARED/watches/Watch.java`。
- **`WatchType`**：`ENTITY` | `BRANCH`。出处：`LEG_SHARED/watches/WatchType.java`。
- **`WatchRecord`**：Morphia `@Entity("Watches")`；唯一索引 `(projectId, userId, entity)`（**不含** `type`）。  
  出处：`LEG/watches/WatchRecord.java`。

### 2.2 动作一览

| 动作 | 用途 | 旧权限 |
|---|---|---|
| `GetWatches` | 某用户对某实体的直接关注 | `WATCH_CHANGES` + `VIEW_PROJECT` |
| `SetEntityWatches` | 替换某用户对某实体的关注集合（客户端主路径） | **未声明** BuiltInAction（缺陷） |
| `AddWatch` / `RemoveWatches` | 单条增删 | `AddWatch` 声明 `WATCH_CHANGES`；`RemoveWatches` **未声明**（缺陷） |
| `GetWatchedEntityChanges` | 按用户全部关注扫描修订，列出相关 `ProjectChange` | `VIEW_CHANGES` |

客户端 UI 只走 `GetWatches` + `SetEntityWatches`（`WatchPresenter`）；`AddWatch` / `RemoveWatches` 基本是遗留入口。

### 2.3 匹配与祖先

`WatchManagerImpl.findWatchRecordsForEntity`：

1. 用 `IndirectlyWatchedEntitiesFinder.getRelatedWatchedEntities(entity)` 取“相关”实体集合，再并上变更实体自身；
2. 查这些实体上的全部 `WatchRecord`；
3. 过滤：`BRANCH` 保留，或 `ENTITY` 且 `record.entity == 变更实体`。

祖先规则（`IndirectlyWatchedEntitiesFinder`）：

| 变更实体种类 | 相关集合 |
|---|---|
| Class / Object / Data / Annotation property | 对应层级 `getAncestors` |
| NamedIndividual | 其命名类型类断言上各类的 `getAncestors` |
| Datatype | 自身单例 |

出处：`LEG/watches/WatchManagerImpl.java`、`IndirectlyWatchedEntitiesFinder.java`。

### 2.4 客户端

- `WatchPresenter`：对话框三选一（无 / 实体 / 分支）→ `SetEntityWatches`。
- `WatchedEntitiesPortletPresenter`：调用 `displayChangesForWatches`；订阅 `WatchAdded` / `WatchRemoved` / 权限变更。无权限时仍把 `forbiddenVisible` 设为 false（UI 缺陷）。
- 层级树：`EntityHierarchyContextMenuPresenter` 用 `WATCH_CHANGES` 启用关注菜单。

---

## 3. 动态行为

### 3.1 设置关注

1. `SetEntityWatchesActionHandler`：删除该用户对该实体的全部现有 watch，再逐条 `addWatch`。
2. `WatchManagerImpl.addWatch`：`saveWatchRecord`（按 `(projectId,userId,entity)` **upsert**，后写的 `type` 覆盖）→ `WatchAddedEvent`。
3. `removeWatch`：按含 `type` 的全键删除 → `WatchRemovedEvent`。

同一用户同一实体不能同时持有 ENTITY 与 BRANCH：唯一索引 + upsert 保证只留一条。

### 3.2 触发通知（旧系统）

1. 项目加载后 `WatchManagerImpl.attach()` 订阅五类 Frame 变更事件（类 / 对象属性 / 数据属性 / 注解属性 / 命名个体）。
2. 每次 Frame 变更：`handleEntityFrameChanged(entity, byUser)` → 匹配关注用户集合 → `WatchTriggeredHandler.handleWatchTriggered`。
3. **按实体、按次**发邮件：一次修订若改了多个实体的 Frame，会触发多次邮件。
4. **未订阅** `DatatypeFrameChangedEvent`（若事件存在也不发关注邮件）。
5. Frame 事件的来源是 `HighLevelEventGenerator.translateOntologyChanges`：对每条公理变更用 `AxiomSubjectProvider.getSubject(axiom)` 取**公理主语**（IRI 主语经 `EntitiesInProjectSignatureByIriIndex` 解析为实体），**不是**公理全签名。`SubClassOf(A, partOf some B)` 只产生 A 的 Frame 事件。
6. **缺陷（去重写反）**：同一方法里 `.filter(e -> !changedEntities.add(e))` 只让同一修订内**第二次及以后**出现的实体通过（`changedEntities` 每个修订新建）。因此只改一条公理的实体不产生 Frame 事件，也就不发关注邮件；改标签这类“删一条、加一条”的编辑才会触发。新系统不沿用 Frame 事件触发，此缺陷随之消失。

出处：`LEG/watches/WatchManagerImpl.java` 约 63–130 行；`WatchTriggeredHandlerImpl.java`；`LEG/events/HighLevelEventGenerator.java` 约 62–118 行。

### 3.3 收件人过滤与是否排除修改者

`WatchTriggeredHandlerImpl`：

1. 对候选 `usersToNotify` 过滤：必须有 `VIEW_PROJECT`；
2. 映射邮箱、去重；
3. **不**从列表中去掉 `byUser`（修改者本人也会收到，只要有邮箱与权限）；
4. 一次 `sendMail` 把所有地址放进同一封邮件的 **TO** 列表（收件人互相可见）。

出处：`WatchTriggeredHandlerImpl.java` 约 93–124 行；`SendMailImpl` `RecipientType.TO`。

对比讨论邮件：`CommentNotificationEmailer` **会**去掉评论作者。关注通知不排除修改者是旧系统的明确行为。

### 3.4 邮件内容

- 主题：`[显示名] Changes made to <短名> in <显示名> by by <显示名>`——`String.format` 已含 `by %s`，参数里又拼了 `"by " + displayName`，主题常出现重复 `by`（缺陷）。
- 正文：Mustache 模板 `templates/watch-notification-email-template.html`（脚注有笔误 “You you received…”）。
- 头：`In-Reply-To` / `References` 使用 `projectId`，便于邮件客户端串成会话。

### 3.5 关注实体变更列表

`WatchedChangesManager.getProjectChangesForWatches`：遍历**全部修订**，对每个修订用 `EntitiesByRevisionCache` 求涉及实体，再按 ENTITY / BRANCH 祖先规则过滤，拉取 `ProjectChange`。分页参数被忽略（整表一页）。  
出处：`LEG/watches/WatchedChangesManager.java`、`LEG/change/GetWatchedEntityChangesActionHandler.java`。

### 3.6 合并实体

`MergeEntitiesChangeListGenerator` **只**转移讨论线程，**不**改写或删除 Watches。合并后源实体上的关注会悬空，直到用户手动清理。

---

## 4. 数据与状态

| 集合 / 状态 | 内容 | 迁移 |
|---|---|---|
| Mongo `Watches` | `projectId`、`userId`、`entity`、`type` | 是（P6） |
| 唯一索引 | `(projectId, userId, entity)` | 新库等价唯一约束 |
| 本体公理 | 无关 | — |

建议 PostgreSQL 形状（实现卡细化）：

- `entity_watch(project_id, user_id, entity_iri, entity_type, watch_type, …)`  
  唯一 `(project_id, user_id, entity_iri, entity_type)`；`watch_type in ('ENTITY','BRANCH')`。

邮件投递队列（新系统新增，非旧 Mongo）：

- `mail_deliveries(…)`：登记 + 认领 + 重试，模式对齐 `webhook_deliveries`（P6-20 / P6-21）。

---

## 5. 演进证据

- 关注与讨论邮件两套收件人策略并存：讨论偏广播，关注按实体订阅——新工程应保持关注的订阅语义，讨论邮件另议。
- 触发绑在 Frame 事件上，与修订游标、outbox 消费者模型不一致；新工程应改为**按修订**计算受影响实体。
- `AddWatch` / `RemoveWatches` 与 UI 主路径脱节；`SetEntityWatches` / `RemoveWatches` 权限声明不完整。
- 关注变更历史接口全表扫描，与 BS 中“按实体查历史曾扫描全部修订”同类问题。

---

## 6. 本质约束与偶然实现

| 类别 | 内容 | 新工程 |
|---|---|---|
| 本质 | 用户可对实体登记 ENTITY 或 BRANCH 关注 | **继承**（BS-21） |
| 本质 | BRANCH 匹配子孙（含个体经类型类的祖先）；ENTITY 只匹配自身 | **继承**（BS-21） |
| 本质 | 有权查看项目的关注者应能得知被关注实体的变更 | **继承**为邮件（BS-22）；收件人需 `VIEW_PROJECT` |
| 本质 | 关注为项目元数据，可迁移 | **继承** |
| 偶然 | 进程内 Frame 事件触发、每实体一封邮件、TO 群发 | 改为 outbox 按修订触发；每用户每修订一封（逐封投递，单 `To`，不用 TO / Bcc 群发） |
| 偶然 | 不排除修改者 | **新系统修正**：排除修改者本人（与讨论邮件一致） |
| 偶然 | GWT RPC、Morphia、Mustache 模板文件可覆盖 | REST + PostgreSQL + 代码内 / 资源模板 |
| 偶然 | `WatchAddedEvent` / `WatchRemovedEvent` 进项目事件流 | **不移植**：新事件中心向项目全部订阅者广播，会暴露个人关注（BS-21） |
| 缺陷 | `SetEntityWatches` / `RemoveWatches` 未声明 `WATCH_CHANGES` | **必须修复** |
| 缺陷 | 个体只取类型类的 `getAncestors`（不含自身），类 C 的直接实例变更不命中 C 上的 BRANCH 关注 | **新系统修正**：个体相关集合含类型类自身（BS-21） |
| 缺陷 | Frame 事件去重写反，单条公理变更不发关注邮件（§3.2 第 6 条） | 随触发源改为 outbox 消失；主语规则保留“公理主语” |
| 缺陷 | 邮件主题重复 `by`、模板笔误、无权限时 portlet 仍显示内容 | 实现时修正，不单独开卡 |
| 不做（首期） | 站内通知中心、邮件摘要/频率策略表、移动推送、合并时转移关注、关注变更历史全表扫描 API | 见 EPIC |

---

## 7. 与现有新工程能力的衔接

| 能力 | 衔接点 |
|---|---|
| 权限 | `WATCH_CHANGES` 归入 `CAN_VIEW`（对齐旧 `PROJECT_VIEWER`） |
| Modulith | 新模块 `watch`：deps `bus, access, shared, project, revision, ontology, integration`（`project` 取项目显示名）；`web` / `migration` → `watch` |
| 层级 | BS-08 祖先查询（层级视图 `parents`）；个体经类型类及其祖先（含类型类自身，新系统修正） |
| Outbox | 新消费者按修订匹配关注；受影响实体 = 与 `EntityFrameChanged` 同一“公理主语”规则（`EventTranslator.frameSubjects`），修订内容用 `RevisionLog.read` 读出；**不用** `revision_subjects`（全签名索引）；模型未应用时 `OutboxRetryLater`（同 P6-12）；大修订超过主语阈值时只做直接匹配 |
| 投递 | 复用 P6-21「登记 + 独立投递 + 重试」：登记队列（P6-83）与 SMTP 投递线程（P6-87，`spring-boot-starter-mail`）分卡 |
| 事件 | 关注增删**不**进 `ProjectEventHub`；Web 写成功后自行刷新 |
| 迁移包 | 导出/导入 Watches；`formatVersion` 仍为 1 |
| 讨论邮件 | 首期只服务关注；讨论 API 已发域事件，日后可订阅读同一投递队列 |

---

## 8. 测试锚点（移植断言时优先）

- 仓储 upsert：同实体先 ENTITY 后 BRANCH → 只留 BRANCH（`WatchRecordRepository_IT`）
- 匹配：BRANCH 祖先命中；ENTITY 不因祖先命中；个体经类型类祖先；**类 C 的直接实例变更命中 C 上的 BRANCH（新系统修正，与旧断言相反）**
- 主语：`SubClassOf(A, partOf some B)` 只算 A（B 的关注者不收信）；只改 `rdfs:label` 也触发；删除实体时仍能按变更签名取到类型
- 收件人：无 `VIEW_PROJECT` 不发；无邮箱跳过；**新系统**排除修改者
- 同一修订多个命中实体 → **一封**邮件（新系统）
- 模型滞后 → `OutboxRetryLater`，进度不前进
- 主语数超过阈值 → 只做直接匹配并记 WARN；项目无关注 → 不读修订内容、直接确认
