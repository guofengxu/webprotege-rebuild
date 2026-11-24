# 行为规格（BS-xx）

每条规格都来自旧系统的代码或测试，新系统的验收测试按规格编号命名，例如 `BS01_ChangeListMinimiserTest`。
「旧出处」里的路径相对本仓库根目录（旧工程在 `../webprotege`）。`LEG` 是下面这个前缀的缩写：

```
LEG = ../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server
LEG_API = ../webprotege/webprotege-server-api/src/main/java/edu/stanford/bmir/protege/web/server
LEG_SHARED = ../webprotege/webprotege-shared/src/main/java/edu/stanford/bmir/protege/web/shared
LEG_TEST = ../webprotege/webprotege-server-core/src/test/java/edu/stanford/bmir/protege/web/server
```

---

## BS-01 变更列表最小化

旧出处：`LEG_API/change/ChangeListMinimiser.java`，测试 `../webprotege/webprotege-server-api/src/test/java/edu/stanford/bmir/protege/web/server/change/ChangeListMinimiser_TestCase.java`。

对一组变更按顺序处理，键为 `(ontologyId, axiom)`：

1. 同一个键上，一次添加和一次删除互相抵消，与先后顺序无关。
2. 抵消之后还剩的添加或删除，保留**第一次出现**的那条，位置不变；同键的重复项丢弃。
3. 非公理变更（本体注解、导入）原样保留，位置不变。

例子（A 为同一本体中的同一公理）：

| 输入 | 输出 |
|---|---|
| +A | +A |
| +A, −A | （空） |
| +A, −A, +A | +A（第一条 +A） |
| +A, −A, +A, −A | （空） |
| −A, +A | （空） |
| +A, +A | +A |

## BS-02 有效变更过滤

旧出处：`LEG/index/impl/RootIndexImpl.java`（`getEffectiveChanges`、内部类 `ChangeFilter`）。

先按 BS-01 最小化，再逐条判断：

| 变更 | 有效条件 |
|---|---|
| 添加公理 | 目标本体中**不包含**该公理 |
| 删除公理 | 目标本体中**包含**该公理 |
| 添加本体注解 | 目标本体中不包含该注解 |
| 删除本体注解 | 目标本体中包含该注解 |
| 添加 / 删除导入 | **新系统**：按同样的“包含 / 不包含”规则判断 |

> 旧系统的怪异行为：`ChangeFilter` 没有处理导入变更，visitor 默认返回 `null`，所以导入变更**永远被判为无效**，只能在首次导入项目时写入。新系统改为按规则判断。这是有意的行为差异，测试里要单独写一条用例并加注释。

## BS-03 修订

- 修订号从 1 开始，同一项目内连续递增。修订 0 表示空项目（尚无修订）。
- 修订 1 是项目的初始内容：导入文件中的每条公理、每个本体注解、每个导入声明各对应一条添加变更（旧出处：`LEG/project/ProjectImporter.java`，`getInitialChangeRecords`）。
- 每个修订记录：修订号、用户、时间戳（毫秒）、高层描述、变更列表。
- 第 N 号修订时的本体 = 依次应用修订 1..N 的变更。

## BS-04 回滚修订

旧出处：`LEG/change/RevisionReverterChangeListGenerator.java`，测试 `LEG_TEST/change/RevisionReverterChangeListGenerator_TestCase.java`。

- 回滚第 N 号修订 = 生成一个**新**修订，内容是第 N 号修订每条变更的逆变更，并按**倒序**排列（最后一条变更最先撤销）。
- 逆变更：添加 ↔ 删除，适用于公理、本体注解和导入。
- 新修订的描述为 `Reverted revision N`。
- 修订 N 不存在时，不产生修订，返回失败结果。
- **新系统新增**：修订 N 中含有 `DeclareOntology`（登记本体）变更时，拒绝回滚，返回失败结果。
- 新修订照常经过 BS-02 过滤：如果期间别人已经改过同样的公理，对应的逆变更可能无效，会被过滤掉。

## BS-05 临时实体 IRI（Fresh Entity IRI）

旧出处：`LEG_SHARED/entity/FreshEntityIri.java`，测试 `../webprotege/webprotege-shared/src/test/java/edu/stanford/bmir/protege/web/shared/entity/FreshEntityIri_TestCase.java`。

客户端新建实体时，不自己生成 IRI，而是提交一个临时 IRI：

```
entity:?langTag=<语言标签>&discriminator=<区分符>[&parentIri=<父实体 IRI>]*#<用户输入的名称>
```

- `parentIri` 可以重复多次；值里的 `&` 转义为 `%26`，`#` 转义为 `%23`。
- 实体类型不写在 IRI 里，由实体本身的类型（Class / ObjectProperty / …）决定。
- 同一次提交中，`(区分符, 名称, …)` 完全相同的临时 IRI 指代**同一个**新实体，只生成一次正式 IRI。
- 服务端发现变更里引用了临时 IRI 时：检查该用户对该实体类型的创建权限 → 用项目配置的 CrudKit（BS-06）生成正式 IRI 及其创建变更（声明公理 + 标签）→ 把所有变更和结果对象里的临时 IRI 替换为正式 IRI → 把 `临时 → 正式` 的映射返回给客户端。
- 旧出处（服务端处理流程）：`LEG/project/chg/ChangeManager.java`，`applyChanges`。

## BS-06 实体 IRI 生成（CrudKit）

旧出处：`LEG/crud/`（`uuid/`、`supplied/`、`obo/` 三个子目录），测试 `LEG_TEST/crud/uuid/UuidEntityCrudKitHandlerTestCase.java`、`LEG_TEST/crud/supplied/SuppliedNameSuffixEntityCrudKitHandlerTestCase.java`。

三种生成方式共同的规则：

- 用户输入如果是 `<完整IRI>`（带尖括号）或者可展开的前缀名（如 `owl:Thing`），直接使用该 IRI，不再生成。
- 否则，IRI = 项目配置的 IRI 前缀 + 后缀，后缀按下面的方式生成。
- 创建变更至少包含：一条声明公理；用户输入非空时，再加一条标签注解（注解属性和语言取项目的显示名称设置，默认 `rdfs:label`）。

| 方式 | 后缀 | 标签 |
|---|---|---|
| UUID（默认） | `R` + base62 编码的 UUID。前缀字母 `R` 是为了满足 NCName 首字符要求，同时不暴露实体类型 | 用户输入的名称 |
| 用户输入的名称（Supplied name） | 用户输入的名称，空白按设置处理：转驼峰（默认）或替换为下划线 | 本地名（local name） |
| OBO 风格 | `<ID 前缀>_<零填充的数字>`，数字从当前用户被分配的区间里取下一个未使用的值 | 用户输入的名称 |

OBO 方式的细节（用户区间、零填充位数）以旧测试为准，移植时逐条对照。

## BS-07 删除实体

旧出处：`LEG/crud/DeleteEntitiesChangeListGenerator.java`（内部委托 `LEG/util/EntityDeleter.java`）。

删除一组实体 = 删除项目内**所有本体**中引用了这些实体的公理，包括以该实体 IRI 为主语的注解断言。结果对象返回被删除的实体集合。

## BS-08 类层级

旧出处：`LEG/hierarchy/ClassHierarchyProviderImpl.java`，测试 `LEG_TEST/hierarchy/ClassHierarchyProviderImpl_TestCase.java`。

- 根节点为 `owl:Thing`。
- C 是 P 的子类，当且仅当以下任一成立：
  - 存在 `SubClassOf(C P)`，P 是命名类；
  - 存在 `EquivalentClasses(C X)`，X 是 `ObjectIntersectionOf`，且 P 是其中的命名类操作数。
- 没有任何命名父类的类挂在 `owl:Thing` 下。
- 层级树随变更增量更新；更新后的结果必须与“从头计算”的结果一致（测试用随机变更序列对比两者）。
- 对象属性、数据属性、注解属性的层级同理，依据 `SubObjectPropertyOf` / `SubDataPropertyOf` / `SubAnnotationPropertyOf`。对象属性根为 `owl:topObjectProperty`，数据属性根为 `owl:topDataProperty`，注解属性没有统一的根，展示为根列表。

## BS-09 显示名称（短名）

旧出处：`LEG/shortform/DefaultShortFormAnnotationPropertyIRIs.java`、`DictionaryManager.java`。

- 默认候选注解属性按顺序：`skos:prefLabel`、`rdfs:label`、`dc:title`、`dcterms:title`。
- 项目可以配置“显示语言列表”，每项为 `(注解属性, 语言标签)`，按顺序取第一个命中的值。
- 都没有命中时，退回 IRI 的本地名；本地名也为空时，使用完整 IRI。
- 内置实体（`owl:Thing`、`rdfs:label` 等）使用固定的前缀名形式，如 `owl:Thing`。

## BS-10 权限模型（MVP 子集）

旧出处：`../webprotege/webprotege-shared-core/src/main/java/edu/stanford/bmir/protege/web/shared/access/BuiltInAction.java`、`BuiltInRole.java`。

旧系统有 50 多个动作和一组可组合的角色。MVP 只保留以下角色，每个角色包含其左侧角色的全部动作：

| 项目角色 | 新增动作 |
|---|---|
| CAN_VIEW | VIEW_PROJECT、VIEW_CHANGES、DOWNLOAD_PROJECT、**WATCH_CHANGES**（BS-21，由 P6-85 填实） |
| CAN_COMMENT | VIEW_OBJECT_COMMENT、CREATE_OBJECT_COMMENT、EDIT_OWN_OBJECT_COMMENT、SET_OBJECT_COMMENT_STATUS（BS-19，由 P6-75 填实；MVP 曾为占位）；**EDIT_ENTITY_TAGS**（BS-23，由 P6-93 填实） |
| CAN_EDIT | EDIT_ONTOLOGY、EDIT_ONTOLOGY_ANNOTATIONS、CREATE_CLASS、DELETE_CLASS、CREATE_PROPERTY、DELETE_PROPERTY、CREATE_INDIVIDUAL、DELETE_INDIVIDUAL、REVERT_CHANGES |
| CAN_MANAGE | EDIT_PROJECT_SETTINGS、EDIT_SHARING_SETTINGS、EDIT_NEW_ENTITY_SETTINGS、EDIT_PROJECT_PREFIXES、MOVE_ANY_PROJECT_TO_TRASH（仅本项目）、**EDIT_FORMS**（BS-16，由 P6-67 加入）、**EDIT_PROJECT_TAGS**（BS-23，由 P6-93 填实） |

| 应用角色 | 动作 |
|---|---|
| PROJECT_CREATOR | CREATE_EMPTY_PROJECT、UPLOAD_PROJECT |
| SYSTEM_ADMIN | 全部应用动作（CREATE_ACCOUNT、DELETE_ANY_ACCOUNT、RESET_ANY_USER_PASSWORD、EDIT_APPLICATION_SETTINGS 等），并对所有项目拥有 CAN_MANAGE |

- 项目创建者自动获得该项目的 CAN_MANAGE。
- **P6 第二轮新增**（P6-19 规划，旧出处同上 `BuiltInRole.java`）：CAN_EDIT 增加 `MERGE_ENTITIES`（BS-13，由 P6-24 加入）；CAN_MANAGE 增加 `UPLOAD_AND_MERGE`、`UPLOAD_AND_MERGE_ADDITIONS`（BS-14，由 P6-27 加入）。归属与旧系统一致（旧系统中前者属于 PROJECT_EDITOR，后两者属于 PROJECT_MANAGER）。
- **P6 表单**（P6-43 规划，旧出处同上）：CAN_MANAGE 增加 `EDIT_FORMS`（旧系统 PROJECT_MANAGER）。实体表单**读**仍用 `VIEW_PROJECT`，**写值**仍用 `EDIT_ONTOLOGY`（不新增 VIEW_FORMS）。
- **P6 讨论**（P6-44 规划，旧出处同上 `BuiltInRole.OBJECT_COMMENTER`）：CAN_COMMENT 填入上表四类评论动作（旧系统还有 `EDIT_ANY_OBJECT_COMMENT` 与 Issue 相关动作，**首期不移植**）。编辑 / 删除评论还必须是作者本人（见 BS-19）。
- **P6 关注**（P6-45 规划，旧出处同上 `BuiltInRole.PROJECT_VIEWER`）：CAN_VIEW 增加 `WATCH_CHANGES`（BS-21，由权限实现卡填实）。
- **P6 标签**（P6-46 规划，旧出处同上 `BuiltInRole.OBJECT_COMMENTER` / `PROJECT_MANAGER`）：CAN_COMMENT 增加 `EDIT_ENTITY_TAGS`；CAN_MANAGE 增加 `EDIT_PROJECT_TAGS`（BS-23，由 P6-93 填实）。读项目 / 实体标签仍用 `VIEW_PROJECT`。
- 权限校验在命令总线入口统一执行（旧出处：`LEG/dispatch/impl/DispatchServiceExecutorImpl.java`，`RequestValidator` 链），不在各 Handler 里零散判断。

## BS-11 高层事件

旧出处：`LEG_SHARED/event/`（`ProjectEvent` 的各子类）、`LEG/events/`。

MVP 需要的事件（其余见 P6）：

| 事件 | 触发条件 | 载荷 |
|---|---|---|
| ProjectChanged | 每个新修订 | 修订号、用户、时间、描述、变更条数 |
| EntityHierarchyChanged | 层级中有父子关系增删 | 层级类型、增加 / 删除的 (父, 子) 边 |
| EntityFrameChanged | 某实体的 Frame 内容（标签、注解、父类、属性值）发生变化 | 实体 |
| BrowserTextChanged | 某实体的显示名称变化 | 实体、新显示名 |
| PermissionsChanged | 项目角色分配变化 | 项目 |
| CommentPosted / CommentUpdated / CommentDeleted | 讨论评论增删改（BS-20） | 见 BS-20 |
| DiscussionThreadCreated / DiscussionThreadStatusChanged | 讨论线程创建或状态变化（BS-20） | 见 BS-20 |
| WatchAdded / WatchRemoved | 用户增删实体关注（BS-21） | userId、实体、watchType；`revNo` = head |
| ProjectTagsChanged / EntityTagsChanged | 项目标签目录或实体显式标签变更（BS-24） | 见 BS-24 |

- 事件从**已提交的修订**派生，修订号作为事件流的游标，客户端断线后可以凭修订号补齐。
- **非修订事件**（`PermissionsChanged`、讨论事件、关注增删事件、标签事件）：仍带当前项目 head 作为 `revNo` 游标（与角色变更同一模式），不产生新修订。
- 静默生成器（旧系统的 `SilentChangeListGenerator`）产生的修订只发 ProjectChanged。

## BS-12 支持的格式

- 导入：RDF/XML、Turtle、OWL/XML、OWL 函数式语法、Manchester 语法；也支持包含多个本体文档（含导入闭包）的 zip。
- 导出（旧出处：`LEG_SHARED/download/DownloadFormatExtension.java`）：`owl`（RDF/XML）、`ttl`、`owx`、`omn`、`ofn`。项目包含多个本体时打包成 zip。
- 可以导出任意历史修订（重放到该修订）。

## BS-13 合并实体

旧出处：`LEG/entity/MergeEntitiesChangeListGenerator.java`、`LEG_SHARED/entity/MergeEntitiesAction.java`、`LEG_SHARED/entity/MergedEntityTreatment.java`，测试 `LEG_TEST/entity/MergeEntitiesChangeListGenerator_TestCase.java`。权限 `MERGE_ENTITIES`（BS-10）。

把一组源实体合并到一个目标实体，按以下顺序生成变更（顺序有意义，后一步依赖前一步的结果）：

1. **替换引用**：在项目**所有本体**中，把每个源实体的 IRI 改写为目标实体的 IRI（与重命名相同：删除含源 IRI 的公理，加入改写后的公理）。源实体的注解断言因此都移到了目标实体上。
2. **标签降级**：对每个本体中源实体原有的 `rdfs:label` 与 `skos:prefLabel` 注解断言，删除它们在目标实体上的改写结果，改为目标实体上的 `skos:altLabel`；值（含语言标签）、注解上的注解、公理上的注解都保留。目的是不让目标实体出现重复的首选标签。**新系统修正**：目标在该本体中原本就有完全相同的断言时保留该断言（旧系统会把它删掉）。
3. 按处理方式二选一：
   - `DEPRECATE_MERGED_ENTITY`：在默认本体中为源 IRI 加入 `owl:deprecated true` 的注解断言，并把源实体**原有的**全部注解断言按原本体加回源 IRI。旧系统的第 1 步把源的类型声明也改写到了目标上，源 IRI 因此只剩注解断言、不再出现在签名中；**新系统新增**：同时在默认本体中加回源实体的类型声明，使被弃用的源实体仍能在层级、搜索与 Frame 中找到；
   - `DELETE_MERGED_ENTITY`：不加弃用断言，源实体从此不再出现在任何公理中。
- 生成器的结果是目标实体；修订描述为用户填写的说明。
- **新系统新增**：用户没有填写说明时，描述为 `Merged <n> entities into <目标显示名>`；按以下顺序检查，命中即拒绝合并、不产生修订：源集合为空 → 目标在源集合中 → 源或目标是内置实体（例如 `owl:Thing`）→ 源或目标未声明 → 实体类型不一致（实体类型从本体声明中解析，请求不携带类型）。
- 旧系统在 `DELETE_MERGED_ENTITY` 时把讨论线程移到目标实体；**新系统**在讨论功能落地后同样转移（P6-79）：合并修订提交成功后，把源实体上的全部讨论线程改挂到目标实体；`DEPRECATE_MERGED_ENTITY` **不**转移。转移在应用层完成（Spring 事件），不进入内核变更生成器。
- 与旧测试对应的断言：源 IRI 被替换为目标 IRI；源的 `rdfs:label` / `skos:prefLabel` 变成目标的 `skos:altLabel`；`DELETE_MERGED_ENTITY` 时没有弃用断言；`DEPRECATE_MERGED_ENTITY` 时有弃用断言。

## BS-14 上传并合并到已有项目

旧系统有两种方式，都是先上传本体文件，再把差异作为**一个修订**写入项目。权限见 BS-10（两者都还需要 `EDIT_ONTOLOGY`）。

**方式一：上传新版本，按差异合并**（旧出处：`LEG/merge/ComputeProjectMergeActionHandler.java`、`MergeUploadedProjectActionHandler.java`、`ModifiedProjectOntologiesCalculator.java`、`OntologyDiffCalculator.java`、`OntologyPatcher.java`；权限 `UPLOAD_AND_MERGE`）

- 配对：项目中的本体与上传的本体只有在**本体 IRI 相同**（且都不是匿名本体）时才配对；没有配对的本体不参与合并。
- 差异：对每一对本体计算完整的差异——上传版本有而项目没有的公理为“新增”，项目有而上传版本没有的公理为“删除”；本体注解同样计算新增与删除。导入声明不参与差异。
- 预览：列出公理的新增 / 删除，按公理排序；确认后按用户填写的说明一次性应用。
- **新系统新增**：项目与上传文件**各自恰好只有一个本体**时，即使本体 IRI 不同或为匿名也直接配对（旧系统此时什么都不合并）；预览同时给出本体注解的新增 / 删除条数与未配对的上传本体清单；应用时必须带上预览时的项目修订号，期间项目有了新修订则拒绝（409），需要重新预览；修订号在生成锁内比较（与生成变更在同一把锁下），不能在提交之前单独检查。

**方式二：只追加**（旧出处：`LEG/merge_add/ExistingOntologyMergeAddActionHandler.java`、`NewOntologyMergeAddActionHandler.java`、`MergeOntologyCalculator.java`、`OntologyMergeAddPatcher.java`；权限 `UPLOAD_AND_MERGE_ADDITIONS`）

- 旧系统允许勾选部分上传本体；**新系统简化**为把全部上传本体的公理与本体注解**追加**到项目中的一个目标本体，不删除任何内容；已存在的公理照常由 BS-02 过滤。
- 旧系统还允许追加到“新本体”，但会忽略用户填写的 IRI、创建匿名本体，修订描述也固定为 `Merge ontologies into new ontology`。**新系统不移植**“追加到新本体”，只允许选择项目中已有的本体；修订描述为用户填写的说明，未填写时为 `Merged uploaded ontologies into <目标本体 IRI 或 o<n>>`。

## BS-15 项目 webhook

旧出处：`LEG/webhook/ProjectChangedWebhookInvoker.java`、`ProjectChangedWebhookPayload.java`、`JsonPayloadWebhookExecutor.java`、`LEG_SHARED/webhook/ProjectWebhook.java`、`ProjectWebhookEventType.java`；触发点 `LEG/project/chg/ChangeManager.java#generateAndDispatchHighLevelEvents`。

- 每个项目可以配置多个 webhook，每个包含回调地址与订阅的事件类型；修改需要 `EDIT_PROJECT_SETTINGS`。
- 事件 `PROJECT_CHANGED`：每产生一个新修订触发一次，向订阅了它的 webhook 发送 `POST`，`Content-Type: application/json`，载荷字段为 `projectId`、`userId`（字符串）、`revisionNumber`、`timestamp`（毫秒时间戳）。
- 旧系统用全局单线程发送，同一项目的通知按修订顺序发出；没有重试、没有超时设置、没有签名，响应状态只记日志。
- 旧系统的 `COMMENT_POSTED` 可以在界面上勾选，但服务端从不发送；Slack 通知只在发表评论时发送。**两者都不移植**。
- **新系统新增**（P6-20、P6-21）：由 outbox 中继驱动，对每个 webhook 至少投递一次，同一 webhook 按修订顺序投递；请求带事件类型、投递 ID（供接收方去重）与可选的 HMAC-SHA256 签名头；连接与读取有超时；失败按指数退避重试，超过次数后放弃并记录；只允许 `http` / `https` 地址，默认拒绝回环、私有与链路本地地址（可配置放开）。旧系统对静默修订不发通知，新系统的 outbox 不区分静默修订，**每个修订都通知**。

## BS-16 表单描述符与选择器

旧出处：`LEG_SHARED/form/FormDescriptor.java`、`FormFieldDescriptor` / `FormControlDescriptor` / `OwlBinding`、`EntityFormSelector.java`；`LEG/form/EntityFormRepositoryImpl.java`、`EntityFormSelectorRepositoryImpl.java`、`SetEntityFormDescriptorActionHandler.java`；测试 `LEG_TEST/form/EntityFormRepositoryImpl_IT.java`、`EntityFormSelectorRepositoryImpl_IT.java`。详解见 `forms.md`。

- 每个项目有一组表单描述符：`formId`（UUID）、多语言标签、有序字段列表；字段含控件描述（八种之一）、可选 OWL 绑定、必填/可重复/只读等元数据；可选“主体工厂”描述如何为新行铸造实体。
- 每个 `formId` 至多一条选择器：`CompositeRootCriteria` 决定哪些实体适用该表单；描述符与选择器**分存**，删除描述符时应同时去掉选择器。
- 描述符列表有稳定顺序（旧系统 `ordinal`）；整表替换时按提交顺序重编号。
- 改描述符或选择器需要 `EDIT_FORMS`（归入 CAN_MANAGE，见 BS-10）。**新系统修正**：凡写入描述符的接口都必须校验该动作（旧系统部分 Handler 未声明）。
- 表单**字段值**不进描述符表；无独立“表单值”存储（旧 `FormDataRepository` 已废弃，不移植）。
- **首期控件写入范围**见 EPIC「表单（P6-43）」；JSON 须能表示全部八种控件与四种绑定，以便迁移与后续卡，即使运行时只实现子集。

## BS-17 按实体取表单（投影）

旧出处：`LEG/form/EntityFormManager.java`、`GetEntityFormActionHandler.java`、`EntityFrameFormDataDtoBuilder.java`、`BindingValuesExtractor.java`；客户端 `LEG_CLIENT/form/EntityFormStackPresenter.java`。

- 给定实体：用项目全部选择器匹配实体，加载命中的描述符（可再按请求中的 `formId` 过滤）；无选择器的表单**不适用**任何实体。
- 对每个适用表单，按字段绑定从**当前本体**投影控件值；结果带描述符，供客户端作 pristine 快照。
- 读需要 `VIEW_PROJECT`。
- **首期选择器匹配**只实现 `EntityTypeIsOneOf` 与 `SubClassOf`（可组合 ALL/ANY）。`SubClassOf`：**任意祖先**（含间接超类）即命中，与旧 `MatchingEngine` 对类层级的常用语义一致；仅比较直接父类会误伤迁移数据。criteria 树中**任一**未支持节点 → **整条选择器不命中**（不部分求值）。完整 MatchingEngine 留待日后 Query/匹配引擎规划卡（P6-46 仅做显式标签，不移植 MatchingEngine）。
- **首期投影**：`TEXT` / `NUMBER` 且绑定为 `PROPERTY` 时，投影该属性在主体上的值（数据属性字面量与注解断言均支持，按属性种类区分）；`CLASS` / `INSTANCE` / `SUBCLASS` 绑定的字段首期返回空占位（只读）。其余控件种类返回空值或只读占位，不得抛错导致整表失败。分页 / 区域过滤 / 排序：首期默认整页。

## BS-18 保存实体表单（→ 公理变更）

旧出处：`LEG_SHARED/form/SetEntityFormsDataAction.java`；`LEG/form/SetEntityFormsDataActionHandler.java`、`FormDataUpdateSanityChecker.java`、`EntityFormChangeListGenerator.java`、`FormFrameFlattener.java`、`FormFrameConverter.java`、`processor/*`；公理差分经 `LEG/frame/FrameChangeGenerator.java`；管线 `LEG/project/chg/ChangeManager.java`。

- 请求携带同一批 `formId` 的 **pristine** 与 **edited** 表单数据；两侧 `formId` 集合必须相同，且每对描述符相等，否则拒绝（不写修订）。
- 按表单将数据转为 `FormFrame`，嵌套主体摊平合并后，对每个 `OWLEntity` 主体求 Frame 差分并生成公理增删；类主体上的“实例”集合额外以类断言公理处理（默认本体）。无变更的表单跳过。
- 新主体（网格行 / 子表单）经主体工厂生成临时 IRI，由写管线铸造正式 IRI（BS-05）。
- 权限：`EDIT_ONTOLOGY`（不是 `EDIT_FORMS`）。必须经 `ChangePipeline` 提交；成功则产生修订与高层事件，与其它本体编辑一致。
- **新系统并发（两层，均在生成锁内）**：
  1. 请求必须带期望的项目 head；与当前 head 不等 → `StaleHeadException` / 409 `STALE_HEAD`（与 BS-14 一致）。旧系统无此检查。
  2. 对每个摊平后的主体，由 **pristine** 推出的 Frame（首期仅 PROPERTY 上的 TEXT/NUMBER 相关断言）必须等于当前运行时；不等 → 409 `FORM_DATA_CHANGED`（语义对齐 Frame 编辑的 `from` 校验 / `FRAME_CHANGED`），不写修订。仅校验 head 不足以防止同修订号下的内容冲突。
- **首期可写**：仅 `TEXT` / `NUMBER` 且 `OwlBinding` 为 `PROPERTY`（数据属性或注解属性）。其它控件或绑定的 edited 必须与 pristine 相等，否则拒绝。表格 / 子表单 / 实体名等另开卡。
- **往返不变量**（P6-69/P6-73 测试）：投影后不改即保存 → 不产生修订；改一处 TEXT/NUMBER 属性值 → 与直接 Frame 更新同一绑定的公理结果一致。

## BS-19 实体讨论线程与评论

旧出处：`LEG_SHARED/issues/EntityDiscussionThread.java`、`Comment.java`、`Status.java`；`LEG/issues/*Handler.java`、`EntityDiscussionThreadRepository.java`、`CommentRenderer.java`；详解见 `discussions.md`。

- 每个项目有一组挂在 OWL 实体上的讨论线程：`threadId`（UUID）、实体（iri + 类型）、`OPEN`/`CLOSED`、有序评论列表。一条实体可有多条线程。
- 评论：`commentId`（UUID，项目内唯一）、`createdBy`、`createdAt`、可选 `updatedAt`、Markdown `body`、服务端生成的 `renderedBody`（消毒后的 HTML）。
- **Markdown**：服务端渲染（CommonMark + Autolink / Strikethrough / Tables / Ins）；**必须**消毒，禁止原样输出用户提供的 HTML。客户端展示 `renderedBody`，不以客户端 Markdown 作为安全边界。
- **提及（首期）**：仅识别 `@userId` / `@{displayName}`，渲染为用户提 span；`#数字`（旧 Issue）、`Rn`、实体字面量**不**做特殊解析或跳转（可原样留在 Markdown 文本中）。
- 读线程 / 有评论实体列表：`VIEW_OBJECT_COMMENT`（**新系统修正**：旧 `GetCommentedEntities` 未声明该动作）。创建线程与追加评论：`CREATE_OBJECT_COMMENT`。开关线程：`SET_OBJECT_COMMENT_STATUS`。编辑与删除评论：命令总线声明 `EDIT_OWN_OBJECT_COMMENT`，**且** Handler 校验当前用户必须是该评论 `createdBy`（**新系统修正**：旧编辑未校验作者；旧删除无 BuiltInAction、仅自定义校验；旧客户端删除按钮未开放——新系统首期开放删除并发 `CommentDeleted`）。首期不提供“编辑/删除任意评论”。
- 删光某线程全部评论后：允许留下空线程，或级联删除空线程——实现卡选一并测试钉死（旧系统可留空线程）。
- 讨论写路径**不**产生修订、不经 `ChangePipeline`。向已 `CLOSED` 的线程追加评论：允许，且**不**自动重开（与旧系统一致）。
- **首期不做**：邮件通知、Slack、GitHub 风格 Issue / Milestone、项目 webhook 的 `COMMENT_POSTED`。

## BS-20 讨论高层事件

旧出处：`LEG_SHARED/issues/CommentPostedEvent.java`、`CommentUpdatedEvent.java`、`DiscussionThreadCreatedEvent.java`、`DiscussionThreadStatusChangedEvent.java`；投递经旧 `EventManager`。新系统经 `ProjectEventHub`（ADR-007）。

| 事件 | 触发 | 载荷（要点） |
|---|---|---|
| DiscussionThreadCreated | 新建线程 | threadId、实体、首条评论摘要 |
| CommentPosted | 新建线程的首评或追加评论 | threadId、comment、实体、该实体评论数 / OPEN 线程评论数 |
| CommentUpdated | 编辑评论成功 | threadId、comment |
| CommentDeleted | 删除评论成功（**新系统新增**；旧系统无事件） | threadId、commentId、实体 |
| DiscussionThreadStatusChanged | 开关线程 | threadId、实体、status、该实体 OPEN 评论数 |

- 全部实现 `ProjectEvent`：`type` + `revNo`（取发布时项目 head，与 `PermissionsChanged` 相同），不新增修订。
- 讨论模块持久化成功后发 Spring 域事件；`events` 模块翻译并 `ProjectEventHub.publish`。Web / 长轮询客户端必须穷尽处理新 `type`（与现有五类事件同一联合类型）。
- 讨论事件**不**写入项目 webhook 投递（与 BS-15：旧 `COMMENT_POSTED` 从未发送一致）。

## BS-21 实体关注（Watches）

旧出处：`LEG_SHARED/watches/Watch.java`、`WatchType.java`；`LEG/watches/WatchRecord.java`、`WatchRecordRepositoryImpl.java`、`WatchManagerImpl.java`、`IndirectlyWatchedEntitiesFinder.java`、`*ActionHandler.java`；客户端 `LEG_CLIENT/watches/WatchPresenter.java`。详解见 `watches.md`。

- 每个项目有一组关注记录：`userId`、实体（iri + 类型）、`watchType` ∈ {`ENTITY`,`BRANCH`}。同一用户对同一实体**至多一条**（后写覆盖类型；不能同时持有 ENTITY 与 BRANCH）。
- **匹配**（给定一次修订中“受影响”的实体集合；该集合**不**预扩展子孙）：
  - `ENTITY`：仅当变更实体等于关注实体时命中；
  - `BRANCH`：当关注实体等于变更实体，或关注实体属于变更实体的“相关祖先集合”时命中；
  - 类 / 对象属性 / 数据属性 / 注解属性的相关祖先 = 对应层级祖先（是否含自身以实现为准，但 **BRANCH 关注实体自身被改时必须命中**）；
  - 命名个体的相关集合 = 其**命名类断言**上的类 **以及**这些类的祖先（**含这些类自身**；与旧 `IndirectlyWatchedEntitiesFinder` 对个体走 `getAncestors(cls)` 且匹配阶段把实体自身并入查询集合的效果一致：关注某类的 BRANCH 会命中该类的实例）；
  - Datatype：只匹配自身；匿名个体首期不作为关注目标（API 拒绝）。
- 读写关注需要 `WATCH_CHANGES`（归入 `CAN_VIEW`，见 BS-10）。**新系统修正**：设置 / 清除关注的接口必须声明该动作（旧 `SetEntityWatches` / `RemoveWatches` 未声明）。
- 关注写路径**不**产生修订。成功增删后发 `WatchAdded` / `WatchRemoved`（见 BS-11；载荷含 userId、实体、watchType；`revNo` = 当前 head）。
- **首期不做**：按关注扫描全部修订的变更列表 API（旧 `GetWatchedEntityChanges` 全表扫描）；合并实体时转移或清理关注（旧系统也不做）。

## BS-22 关注变更邮件通知

旧出处：`LEG/watches/WatchManagerImpl.java`（Frame 事件入口）、`WatchTriggeredHandlerImpl.java`、`templates/watch-notification-email-template.html`；邮件 API `LEG/mail/SendMailImpl.java`。详解见 `watches.md`。

- **触发源（新系统）**：outbox 消费者在修订已提交且内存模型已应用到该修订后，求出受影响实体，再按 BS-21 匹配关注。模型尚未应用到该修订时抛 `OutboxRetryLater`，中继**不**前进该消费者进度（同投影表 P6-12）。**不**在写路径进程内按 Frame 事件直接发邮件。
- **受影响实体（新系统钉死）**：与写入 `revision_subjects` / `projection.AffectedSubjects` **同一主语集合**——公理 `getSignature()` 中的实体 IRI，外加 `OWLAnnotationAssertion` 的 **IRI 主体**（因此改 `rdfs:label` 等会触发）。**不**包括导入声明、本体注解、前缀；匿名个体注解主体不纳入；**不**在此步预扩展子孙或祖先。P6-88 **优先**按 `project_id+rev_no` 读 `revision_subjects`（若 `RevisionHistoryQueries` 尚无按修订列主语的方法，本卡在 revision 模块**最小增补**只读查询；避免依赖 `projection`）；禁止为本功能改 ontology 公共接口。
- **合并粒度（新系统）**：同一修订、同一收件用户至多一封邮件，正文列出全部命中的实体（旧系统按 Frame 事件逐实体发送）；列表过长时可截断并附剩余条数（实现卡设上限，例如 50）。
- **收件人**：匹配到的关注用户，去掉**本修订的作者**（**新系统修正**：旧关注通知不排除修改者；对齐讨论邮件排除作者的做法）；再过滤无 `VIEW_PROJECT` 或无邮箱的用户。邮箱来自 `access` 模块 `UserService.findById` → `UserAccount.email()`（可空）。**每个收件人单独一封**（单 `To`），**不**把多位用户放在同一封的 TO 里互见地址。
- **投递**：登记到邮件投递表后由独立投递线程发送（登记 + 认领 + 指数退避重试，对齐 BS-15 / P6-21）。`wp.mail.enabled=false` 或未配置 SMTP 时：消费者仍完成 outbox 进度，投递记为 `SKIPPED`（或明确不登记并记日志，二选一由 P6-83 钉死为 **SKIPPED**），**不得**用 `OutboxRetryLater` 表示邮件关闭，也不得阻塞中继。
- **退订与频率（首期）**：退订 = 用户清除对应关注；不做全局退订表、不做跨修订摘要/限频。邮件脚注说明“因你关注了…收到此信”，并链到项目。
- **首期不做**：站内通知中心、移动推送、讨论评论邮件（可日后复用同一投递队列）。

## BS-23 项目标签与实体显式标签

旧出处：`LEG_SHARED/tag/Tag.java`、`TagId.java`、`TagData.java`、`*Action.java`；`LEG_API/tag/EntityTags.java`、`TagRepository.java`、`EntityTagsRepository.java`；`LEG/tag/TagsManager.java`、`*ActionHandler.java`；客户端 `LEG_CLIENT/tag/*`。详解见 `tags.md`。条件自动打标签见同文件 §2.3 / §6（**首期不做**）。

- 每个项目有一组标签定义：`tagId`（UUID）、`label`（非空、项目内唯一）、`description`、`color`、`backgroundColor`、可选 `criteria`（json；首期**只存不求值**）。
- 实体显式标签：`(project, entity iri+type)` 上挂一组 `tagId`；只允许引用本项目仍存在的标签。
- **设置项目标签目录**：整表替换。新项无 id 则服务端分配；旧项不在新列表中则删除定义，并**从本项目全部实体剥离**该 tagId。读目录：`VIEW_PROJECT`。写目录：`EDIT_PROJECT_TAGS`（`CAN_MANAGE`）。
- **更新实体显式标签**：客户端提交 `from` / `to` TagId 集合，服务端对显式集合做差分（remove `from∖to`，add `to∖from`），**不**抹掉未出现在 from 中的其它显式标签。读：`VIEW_PROJECT`。写：`EDIT_ENTITY_TAGS`（`CAN_COMMENT`）。**新系统修正**：写接口必须在命令总线声明该动作（旧 `UpdateEntityTags` 未声明）。
- 读实体标签（首期）= **仅显式**标签（解析为完整 Tag；孤儿 id 丢弃）。**不**并上 MatchingEngine 条件命中。
- 标签写路径**不**产生修订、不经 `ChangePipeline`。成功后发 BS-24 事件。
- **首期不做**：条件编辑 UI、按条件自动打标签、标签 usage 计数、按项目 Tag 的服务端实体搜索过滤、项目 webhook。

## BS-24 标签高层事件

旧出处：`LEG_SHARED/tag/EntityTagsChangedEvent.java`、`ProjectTagsChangedEvent.java`；旧投递经 `EventManager`。新系统经 `ProjectEventHub`（ADR-007）。

| 事件 | 触发 | 载荷（要点） |
|---|---|---|
| ProjectTagsChanged | 项目标签目录整表替换且有变化 | 当前项目标签目录摘要（id、label、颜色等；可不含完整 criteria） |
| EntityTagsChanged | 实体显式标签差分成功；或因删除目录项被剥离后该实体显式集合变化 | 实体、当前显式标签集合（完整 Tag 或 id+展示字段） |

- 全部实现 `ProjectEvent`：`type` + `revNo`（取发布时项目 head，与 `PermissionsChanged` 相同），不新增修订。
- 标签模块持久化成功后发 Spring 域事件；`events` 模块翻译并 `ProjectEventHub.publish`。Web / 长轮询客户端必须穷尽处理新 `type`。
- 标签事件**不**写入项目 webhook 投递。
- **首期不做**：因本体变更导致条件命中变化而重算发出的 `EntityTagsChanged`（依赖 MatchingEngine）。
