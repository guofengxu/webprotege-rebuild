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
| CAN_VIEW | VIEW_PROJECT、VIEW_CHANGES、DOWNLOAD_PROJECT |
| CAN_COMMENT | （MVP 暂无评论功能，保留角色占位） |
| CAN_EDIT | EDIT_ONTOLOGY、EDIT_ONTOLOGY_ANNOTATIONS、CREATE_CLASS、DELETE_CLASS、CREATE_PROPERTY、DELETE_PROPERTY、CREATE_INDIVIDUAL、DELETE_INDIVIDUAL、REVERT_CHANGES |
| CAN_MANAGE | EDIT_PROJECT_SETTINGS、EDIT_SHARING_SETTINGS、EDIT_NEW_ENTITY_SETTINGS、EDIT_PROJECT_PREFIXES、MOVE_ANY_PROJECT_TO_TRASH（仅本项目） |

| 应用角色 | 动作 |
|---|---|
| PROJECT_CREATOR | CREATE_EMPTY_PROJECT、UPLOAD_PROJECT |
| SYSTEM_ADMIN | 全部应用动作（CREATE_ACCOUNT、DELETE_ANY_ACCOUNT、RESET_ANY_USER_PASSWORD、EDIT_APPLICATION_SETTINGS 等），并对所有项目拥有 CAN_MANAGE |

- 项目创建者自动获得该项目的 CAN_MANAGE。
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

- 事件从**已提交的修订**派生，修订号作为事件流的游标，客户端断线后可以凭修订号补齐。
- 静默生成器（旧系统的 `SilentChangeListGenerator`）产生的修订只发 ProjectChanged。

## BS-12 支持的格式

- 导入：RDF/XML、Turtle、OWL/XML、OWL 函数式语法、Manchester 语法；也支持包含多个本体文档（含导入闭包）的 zip。
- 导出（旧出处：`LEG_SHARED/download/DownloadFormatExtension.java`）：`owl`（RDF/XML）、`ttl`、`owx`、`omn`、`ofn`。项目包含多个本体时打包成 zip。
- 可以导出任意历史修订（重放到该修订）。
