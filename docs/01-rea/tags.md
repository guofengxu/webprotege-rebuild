# 标签（Tags）逆向分析

对象：`../webprotege` 的项目标签定义与实体打标签子系统。方法见 `README.md`（五层）。本文件只写结论与出处；可测行为见 `behavior-specs.md` 的 BS-23 ~ BS-24。

路径缩写：`LEG` / `LEG_SHARED` / `LEG_TEST` 同 `behavior-specs.md`；客户端为  
`LEG_CLIENT = ../webprotege/webprotege-client/src/main/java/edu/stanford/bmir/protege/web/client`。  
另：`LEG_API = ../webprotege/webprotege-server-api/src/main/java/edu/stanford/bmir/protege/web/server`。

规模（约）：server-core `tag/` 11 文件；shared `tag/` 17；client `tag/` 28；server-api 仓储接口 3；另有 `server/match/`（MatchingEngine，约 38 matcher）与 client `match/`（约 126 文件，与 Query 共用）。

---

## 1. 边界与上下文

| 边界 | 内容 |
|---|---|
| 对内 | 项目定义一组标签（名称、颜色、可选条件）；实体可挂显式标签；读时还可并上「按条件命中」的标签 |
| 对外入口 | 仅 GWT RPC（无 Jersey REST）；项目设置页改标签目录；实体右键 / 选择器改显式标签；层级节点与 Frame 头展示标签 chips |
| 不越界 | **不是**本体内容：不写修订、不经 ChangePipeline；与项目设置同类的编辑器元数据 |
| 权限 | 改项目标签目录：`EDIT_PROJECT_TAGS`（服务端校验）；改实体标签：`EDIT_ENTITY_TAGS`（**仅客户端校验**，服务端 Update 未声明——缺陷） |

与新工程：标签存 PostgreSQL（编辑器库）；写路径不产生修订；变更经 `ProjectEventHub`（`revNo` = head）通知其它客户端。首期**不做**条件自动打标签（见 §6）。

---

## 2. 静态结构

### 2.1 概念层次

```
Project
├── Tag*                         （项目标签目录；label 在项目内唯一）
│   ├── tagId, label, description
│   ├── color, backgroundColor
│   └── criteria[]               （RootCriteria；空 = 不自动打）
└── EntityTags*                  （每实体一条；仅显式 TagId 列表）
    ├── entity
    └── tags[]
```

运行时「实体的标签」= **显式** ∪ **条件命中**（`TagsManager.getTags`）。

- **`Tag`**：共享领域对象。出处：`LEG_SHARED/tag/Tag.java`。
- **`TagId`**：UUID 字符串；`createTagId()` 仅服务端。出处：`LEG_SHARED/tag/TagId.java`。
- **`TagData`**：设置目录用的 DTO（`tagId` 可空表示新建；`usageCount` 信息字段）。出处：`LEG_SHARED/tag/TagData.java`。
- **`EntityTags`**：Morphia 文档。出处：`LEG_API/tag/EntityTags.java`。

### 2.2 动作一览

| 动作 | 用途 | 旧权限 |
|---|---|---|
| `GetProjectTags` | 列出项目标签目录 | `VIEW_PROJECT`（usage 恒为 0，计算已禁用） |
| `SetProjectTags` | **整表替换**标签目录 | `EDIT_PROJECT_TAGS` |
| `GetEntityTags` | 实体有效标签 + 全目录 | **未声明** BuiltInAction |
| `UpdateEntityTags` | 按 from→to 差分更新显式标签 | **未声明**（缺陷） |
| `AddProjectTag` | 共享层有 Action | **无 Handler**（死代码；活路径是 SetProjectTags） |

### 2.3 条件语言（MatchingEngine）规模

`CriteriaBasedTagsManager`：对每个带 criteria 的 Tag，调用  
`matchingEngine.matchesAny(entity, tag.getCriteria())`（列表内 **OR**）。

- 标签可用的根类型：`RootCriteria` → 仅 `EntityMatchCriteria`（JSON 子类型约 **13** 种）。
- 完整 criteria 包约 **66** 个 `*Criteria.java`（注解 / 字面量 / 关系 / 层级 / 日期等嵌套）。
- 服务端 `server/match/` 约 38 matcher；客户端 `client/match/` 约 **126** 文件；与 **Query** 共用同一套 presenter。
- **只在读路径计算**，从不写入 `EntityTags`。本体变更可通过 `EntityTagsChangedEventComputer` 发出「有效标签变了」事件。

结论：条件语言是独立的大型子系统；仅为标签首期移植不划算（见 §6）。

### 2.4 客户端

- 项目标签设置：`ProjectTagsPresenter` / `TagEditor` / `TagCriteriaListPresenter` → `SetProjectTags`。
- 实体打标签：`EntityTagsSelectorPresenter`（checkbox；from=当前有效标签，to=勾选）→ `UpdateEntityTags`。
- 展示：`TagListPresenter`（Frame）、`EntityNodeHtmlRenderer` + `ProjectTagsStyleManager`（层级 chips）。
- 「显示的标签」：`TagVisibilityPresenter` 用 CSS 隐藏带某标签的节点——**不是**实体搜索过滤条件。
- 实体搜索：`SearchPresenter` 按**语言标签**（lang tag）过滤，**不**按项目 Tag 过滤。

---

## 3. 动态行为

### 3.1 设置项目标签目录（整表替换）

`TagsManager.setProjectTags`：

1. 清空内存项目标签缓存；
2. 对「旧有、新列表不再出现」的 TagId：`entityTagsRepository.removeTag(tagId)`（从**全部**实体剥离）+ `tagRepository.deleteTag`；
3. 将 `TagData` 映射为 `Tag`（无 id 则 `createTagId()`），`saveTags`；
4. 若目录有变：对受影响实体发 `EntityTagsChangedEvent`，并发 `ProjectTagsChangedEvent`。

出处：`LEG/tag/TagsManager.java`、`SetProjectTagsActionHandler.java`。

### 3.2 更新实体显式标签

`TagsManager.updateTags(entity, from, to)`：

```
next = existing ∪ ∅
remove from∖to; add to∖from
save EntityTags; if changed → EntityTagsChangedEvent(getTags(entity))  // 载荷含条件并集
```

不是「无关标签一律抹掉」的全量替换：只对 from/to 差分动刀。客户端把当前有效标签当作 from，因此取消勾选会去掉显式标签；条件命中的标签若仍在 from 且不在 to，也会尝试 remove（对仅条件命中、未写入 EntityTags 的 TagId，remove 无持久化效果）。

出处：`LEG/tag/TagsManager.java`、`UpdateEntityTagsActionHandler.java`、`LEG_CLIENT/tag/EntityTagsSelectorPresenter.java`。

### 3.3 读取有效标签

`getTags(entity)` = 显式 TagId ∪ `CriteriaBasedTagsManager.getTagsForEntity`，再解析为现存 `Tag`（孤儿 TagId 丢弃）。

### 3.4 与搜索 / 层级

- 层级 / 个体 / 搜索结果节点经 `EntityNodeRenderer` 带上 `getTags(entity)`。
- `TagVisibilityPresenter` 只改 CSS 可见性，**不**改变搜索命中集合。
- **旧系统没有「按项目标签过滤实体搜索」**；P6-19 / P3-19 的「标签可能作搜索过滤」属假设，逆向不成立。

---

## 4. 数据与状态

| 集合 / 状态 | 内容 | 迁移 |
|---|---|---|
| Mongo `Tags` | `_id`(TagId)、projectId、label、description、color、backgroundColor、criteria | 是 |
| 唯一索引 | `(projectId, label)` | 新库等价唯一约束 |
| Mongo `EntityTags` | projectId、entity、tags[]；唯一 `(projectId, entity)`；tags 上有索引 | 是 |
| 本体公理 | 无关 | — |
| 条件命中结果 | **不落库** | — |

建议 PostgreSQL 形状（实现卡细化）：

- `project_tag(tag_id uuid pk, project_id uuid, label text, description text, color text, background_color text, criteria jsonb not null default '[]', …)`  
  唯一 `(project_id, label)`；`label` 非空。
- `entity_tag(project_id, entity_iri, entity_type, tag_id, …)`  
  唯一 `(project_id, entity_iri, entity_type, tag_id)`；索引 `(project_id, tag_id)`。  
  （多行代替旧文档内数组，便于按标签剥离。）

`criteria` 列首期**只存不跑**（便于迁移保真；匹配引擎另议）。

---

## 5. 演进证据

- 条件语言与 Query 同源、体量巨大；usage 统计已因成本关掉——产品侧已对「全量匹配」谨慎。
- `AddProjectTag` 无 Handler；活路径收敛为 `SetProjectTags` 整表替换。
- `UpdateEntityTags` / `GetEntityTags` 服务端不校验 `EDIT_ENTITY_TAGS`；项目设置页权限曾误用 `EDIT_ENTITY_TAGS` 而菜单用 `EDIT_PROJECT_TAGS`（客户端不一致）。
- Caching 仓储实现存在但 `ProjectModule` 注入的是非缓存 Impl，有效缓存主要在 `TagsManager` 自身 map。

---

## 6. 本质约束与偶然实现

| 类别 | 内容 | 新工程 |
|---|---|---|
| 本质 | 项目可定义带颜色的标签目录；label 项目内唯一 | **继承**（BS-23） |
| 本质 | 实体可挂显式标签；删目录项时从所有实体剥离 | **继承**（BS-23） |
| 本质 | 标签是项目元数据，不产生修订，可迁移 | **继承** |
| 本质 | 目录 / 实体标签变更需通知其它客户端 | **继承**为 `ProjectEvent`（BS-24） |
| 偶然 | GWT RPC、Morphia / 原生 Mongo 双栈、Caffeine 包装未接入 | REST + PostgreSQL JDBC |
| 偶然 | 读时并上 MatchingEngine 条件标签；本体变更重算事件 | **首期不做**；criteria 可落库不求值 |
| 缺陷 | 实体标签写接口无服务端权限 | **必须修复**（`EDIT_ENTITY_TAGS`） |
| 缺陷 | 项目设置页权限动作与菜单不一致 | 统一为 `EDIT_PROJECT_TAGS` |
| 不做（首期） | MatchingEngine / 条件编辑 UI / 按条件自动打标签；实体搜索按 Tag 服务端过滤；标签 usage 计数；项目 webhook | 见 EPIC |

**MatchingEngine 是否值得为首期标签移植：**  
否。约 13 个实体根匹配类型 + ~66 criteria + ~126 客户端 match 文件，且与 Query 共享。首期显式标签即可覆盖「工作流打标」；条件自动打标签应在 Query/匹配引擎规划卡落地后再挂回 Tag.criteria。

---

## 7. 与现有新工程能力的衔接

| 能力 | 衔接点 |
|---|---|
| 权限 | `EDIT_PROJECT_TAGS` → `CAN_MANAGE`；`EDIT_ENTITY_TAGS` → `CAN_COMMENT`（对齐旧 OBJECT_COMMENTER / PROJECT_MANAGER） |
| Modulith | 新模块 `tag`：deps `bus, access, shared`；`web` / `migration` / `events` → `tag`；**不**依赖 `revision` / `ontology`（元数据、不写修订） |
| EntityNode | 层级 / 搜索节点增加显式标签摘要（仅显式；enrich 在 web 或独立卡，避免 `ontology`→`tag`） |
| 事件 | `ProjectTagsChanged` / `EntityTagsChanged` 进 `ProjectEventHub`（`revNo` = head）；同 head 唤醒对齐讨论 / 关注 |
| 迁移包 | 导出/导入 `Tags` + `EntityTags`；criteria 原样 jsonb；`formatVersion` 仍为 1 |
| 搜索 | 首期**不**做按 Tag 的服务端搜索过滤；Web 可做层级「显示的标签」CSS/过滤（对齐旧 TagVisibility） |

---

## 8. 测试锚点（移植断言时优先）

- 项目标签：同项目重复 label → 拒绝；整表替换后删除项从所有 `entity_tag` 剥离
- 实体标签：差分 from→to；无 `EDIT_ENTITY_TAGS` → 403（**新系统**）
- 读实体标签：仅返回显式（首期无条件并集）
- 事件：改目录 / 改实体标签后订阅方收到对应 `ProjectEvent`，`revNo` = head
- 迁移：有 criteria 的 Tag 导入后 criteria jsonb 非空，但 getTags 仍不自动命中
