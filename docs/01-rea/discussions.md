# 讨论与评论（Entity Discussion Threads）逆向分析

对象：`../webprotege` 的实体讨论子系统。方法见 `README.md`（五层）。本文件只写结论与出处；可测行为见 `behavior-specs.md` 的 BS-19 ~ BS-20。

路径缩写：`LEG` / `LEG_SHARED` / `LEG_TEST` 同 `behavior-specs.md`；客户端为  
`LEG_CLIENT = ../webprotege/webprotege-client/src/main/java/edu/stanford/bmir/protege/web/client`。

规模（约）：server-core `issues/` ~20 文件；shared `issues/` ~63（其中 GitHub 风格 `Issue` / `Milestone` 等为死代码）；client `issues/` ~25。

---

## 1. 边界与上下文

| 边界 | 内容 |
|---|---|
| 对内 | 挂在**单个 OWL 实体**上的讨论线程；线程内有序评论；线程 `OPEN` / `CLOSED` |
| 对外入口 | 仅 GWT RPC（无 Jersey REST）；客户端 portlet `EntityDiscussionThreadPortletPresenter`、`CommentedEntitiesPortletPresenter` |
| 不越界 | 不写修订 / 不改公理；不替代 Issue Tracker；不负责项目级 changelog |
| 权限切分 | 看：`VIEW_OBJECT_COMMENT`；写评论：`CREATE_OBJECT_COMMENT`；改自己的评论：`EDIT_OWN_OBJECT_COMMENT`；开关线程：`SET_OBJECT_COMMENT_STATUS`。高层角色 `CAN_COMMENT` = `CAN_VIEW` + Issue 角色 + `OBJECT_COMMENTER` |

与新工程：讨论是**项目元数据**（PostgreSQL），不是修订日志真源；实时推送走现有 SSE / 长轮询（ADR-007），事件游标复用项目 head（与 `PermissionsChanged` 同模式）。

---

## 2. 静态结构

### 2.1 概念层次

```
Project
└── EntityDiscussionThread*     （按实体；可多条）
    ├── ThreadId (UUID)
    ├── entity (OWLEntity)
    ├── status OPEN | CLOSED
    └── Comment*                （嵌入数组；首条评论者视为线程创建者）
        ├── CommentId (UUID)
        ├── createdBy / createdAt / updatedAt?
        ├── body (Markdown 原文)
        └── renderedBody (服务端 HTML)
```

- **`EntityDiscussionThread`**：Morphia `@Entity("EntityDiscussionThreads")`；索引 `(projectId, entity, status)`、`comments.id` 唯一。  
  出处：`LEG_SHARED/issues/EntityDiscussionThread.java`。
- **`Comment`**：`body` + `renderedBody`；`getRenderedBody()` 在 `renderedBody == null` 时回退到 `body`。  
  出处：`LEG_SHARED/issues/Comment.java`。
- **`Status`**：`OPEN` / `CLOSED`。出处：`LEG_SHARED/issues/Status.java`。

### 2.2 动作一览

| 动作 | 用途 | 旧权限 / 校验 |
|---|---|---|
| `GetEntityDiscussionThreads` | 某实体全部线程 | `VIEW_OBJECT_COMMENT` |
| `GetCommentedEntities` | 项目内有评论的实体分页列表 | **未声明** `VIEW_OBJECT_COMMENT`（`getRequiredExecutableBuiltInAction` 为 null；缺陷） |
| `CreateEntityDiscussionThread` | 新建 OPEN 线程 + 首条评论 | `CREATE_OBJECT_COMMENT` |
| `AddEntityComment` | 向已有线程追加评论 | `CREATE_OBJECT_COMMENT` |
| `EditComment` | 改评论正文并重渲染 | 声明 `EDIT_OWN_OBJECT_COMMENT`，**不校验作者**（缺陷） |
| `DeleteEntityComment` | 删除评论 | 自定义校验：必须是作者；无线程权限动作 |
| `SetDiscussionThreadStatus` | OPEN ↔ CLOSED | `SET_OBJECT_COMMENT_STATUS` |

死代码（不移植）：`GetIssues*`、`AddIssueComment*`、`Issue` / `Milestone` / `IssueRepository` 等。

### 2.3 提及（Mention）

`MentionParser`（GWT RegExp）识别四类：`#数字`（Issue）、`@user` / `@{name}`、`R数字`（修订）、`Class(<iri>)` 等实体字面量。  
出处：`LEG_SHARED/issues/mention/MentionParser.java`。

`CommentRenderer` **只**把用户提替换成 `<span class="wp-comment__user-mention">…</span>`，其余提及原样留下再交 Markdown。  
出处：`LEG/issues/CommentRenderer.java`。

`CommentParticipantsExtractor`：评论作者 + body 中 `@user`；供邮件收件人。  
出处：`LEG/issues/CommentParticipantsExtractor.java`、`DiscussionThreadParticipantsExtractor.java`。

### 2.4 Markdown 渲染

服务端 CommonMark + 扩展：Autolink、Strikethrough、Tables、Ins。**未**配置 HTML 消毒；`HtmlRenderer` 默认可能放过 Markdown 源里的原始 HTML → XSS 风险。  
出处：`LEG/issues/CommentRenderer.java`。

### 2.5 客户端

- `DiscussionThreadListPresenter` / `DiscussionThreadPresenter`：按当前选中实体加载线程、回复、编辑、删除、开关状态。
- `CommentedEntitiesPresenter`：侧栏“有评论的实体”。
- 事件：订阅 `CommentPosted` 等刷新列表 / 项目动态。

---

## 3. 动态行为

### 3.1 创建线程

1. `CreateEntityDiscussionThreadHandler`：渲染首条评论 → `saveThread` → 发 `DiscussionThreadCreatedEvent` + `CommentPostedEvent` → 异步邮件 + Slack。  
2. 返回该实体当前全部线程。

### 3.2 追加 / 编辑 / 删除

- 追加：`addCommentToThread`（Mongo `$push`）→ `CommentPostedEvent` → 异步通知。  
- 编辑：按 `commentId` 替换整条 `Comment`（保留 `createdBy`/`createdAt`，设 `updatedAt`）→ `CommentUpdatedEvent`。**不**发邮件。**不**比对作者与当前用户。  
- 删除：`$pull`；**无**高层事件。仅作者可删（RequestValidator）。客户端删除按钮在 `CommentViewImpl.ui.xml` 中 `visible="false"`，实际 UI **从未开放删除**（服务端 API 仍在）。删光评论后空线程可残留。

### 3.3 状态

`setThreadStatus` → `DiscussionThreadStatusChangedEvent`（含实体、当前 OPEN 评论数）。

### 3.4 邮件通知（偶然 / 首期不做）

`CommentNotificationEmailer.getUsersToNotify`：

1. 线程参与者（作者 + `@user`）；
2. **并上**项目中所有有资源访问权的非 Guest 用户；
3. 去掉评论作者本人。

即几乎通知项目全体可访问用户。主题 `[项目显示名] Comment posted by …`；后续评论带 `In-Reply-To` / `References`。  
出处：`LEG/issues/CommentNotificationEmailer.java`。

### 3.5 Slack

`CommentPostedSlackWebhookInvoker`：发表评论时触发。BS-15 已写明不移植。

### 3.6 合并实体时转移线程

仅当处理方式为 **删除源实体**（非弃用）时，`discussionThreadRepository.replaceEntity(projectId, source, target)` 把源实体线程的 `entity` 改写为目标。  
出处：`LEG/entity/MergeEntitiesChangeListGenerator.java` 约 122–125 行。  
新系统 BS-13 此前写“暂未移植”；首期纳入（见 EPIC）。

### 3.7 与修订 / 事件总线

讨论写路径**不**经 `ChangeManager`；事件进项目 `EventManager`，与修订事件同一客户端轮询通道。新系统应对齐：讨论事件进入 `ProjectEventHub`，`revNo` 取当前项目 head（同 `PermissionsChanged`）。

---

## 4. 数据与状态

| 集合 / 状态 | 内容 | 迁移 |
|---|---|---|
| Mongo `EntityDiscussionThreads` | 线程文档 + 嵌入 `comments[]` | 是（P6） |
| 索引 | `(projectId, entity, status)`；`comments.id` 唯一 | 新库用等价约束 |
| 本体公理 | 无关 | — |

建议 PostgreSQL 形状（实现卡细化）：

- `entity_discussion_thread(thread_id, project_id, entity_iri, entity_type, status, …)`
- `entity_discussion_comment(comment_id, thread_id, created_by, created_at, updated_at, body, rendered_body, position)`  
  （规范化两表，避免 jsonb 内唯一约束难做；导出/导入仍可按线程拼回）

---

## 5. 演进证据

- 实体讨论是可用功能；GitHub 风格 Issue 包残留在 shared / server，客户端无入口 → **死代码**。
- 邮件收件人策略偏“广播”，与后期 Watches（按实体订阅）重叠；新工程应拆开：讨论首期不做邮件，与 P6-45 共用发送能力。
- webhook UI 可勾选 `COMMENT_POSTED`，服务端从不发（BS-15）——讨论事件不进项目 webhook。

---

## 6. 本质约束与偶然实现

| 类别 | 内容 | 新工程 |
|---|---|---|
| 本质 | 讨论挂在实体上；线程状态；评论有作者与时间；正文为 Markdown | **继承**（BS-19） |
| 本质 | `CAN_COMMENT` 角色承载评论动作 | **继承并填实**（BS-10） |
| 本质 | 删除合并时线程随实体转移到目标 | **继承**（更新 BS-13） |
| 本质 | 协同客户端需知道评论增删改 / 状态变化 | **继承**为 SSE 事件（BS-20） |
| 偶然 | GWT RPC、Morphia 嵌入文档、客户端轮询 | REST + PostgreSQL + SSE |
| 偶然 | 邮件广播全项目、Slack、Issue 提及 `#N` | 邮件留给 P6-45；Slack / Issue **不移植** |
| 偶然 | CommonMark 扩展集合 | 首期对齐常用子集 + **强制消毒** |
| 缺陷 | `EditComment` 不校验作者 | **必须修复** |
| 缺陷 | Markdown 未消毒 HTML | **必须修复** |
| 缺陷 | 删除评论无事件 | **新系统新增** `CommentDeleted` |
| 缺陷 | `GetCommentedEntities` 未要求 `VIEW_OBJECT_COMMENT` | **必须修复** |
| 偶然 | 客户端删除按钮永久隐藏 | 新系统首期**提供**删除（作者）并接事件刷新 |
| 附带 | `ChangeEntityIRIActionHandler` 也会 `replaceEntity` | 新系统尚无独立改 IRI 命令时不拆卡；仓储保留 `replaceEntity` 供合并与日后改名 |

---

## 7. 与现有新工程能力的衔接

| 能力 | 衔接点 |
|---|---|
| 权限 | `Action` / `ProjectRole.CAN_COMMENT` 填入四类评论动作（P6-75） |
| 事件 | 扩展密封 `ProjectEvent`；`EventTranslator` 监听讨论域事件；Web `applyProjectEvent` 穷尽分支（P6-77 / P6-78） |
| 合并实体 | `MergeEntitiesHandler` 成功后发 Spring 事件；`discussion` 模块改写线程实体（P6-79）；**不**把 Morphia 仓储塞进内核 |
| 迁移包 | 项目目录增加讨论文件；`formatVersion` 仍为 1（P6-80 / P6-81） |
| 邮件 | **首期不做**；P6-45 规划 SMTP / 投递后，再订阅读评论事件 |

---

## 8. 测试锚点（移植断言时优先）

- 仓储：`LEG/.../EntityDiscussionThreadRepository` 的 find / push / update / delete / replaceEntity
- 渲染与提及：`MentionParser` 单测（若有）、`CommentRenderer` 用户提 span
- 权限：`EditComment` 作者校验（新断言）；`DeleteEntityComment` 非作者拒绝
- 合并：`MergeEntitiesChangeListGenerator_TestCase` 中与 `discussionThreadRepo` 相关的交互
