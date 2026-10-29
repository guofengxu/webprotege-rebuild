# 旧系统的数据：布局、格式、集合

P2 迁移工具按本文读取旧数据。旧出处路径的缩写见 `behavior-specs.md` 开头。

## 1. 文件系统

`webprotege.properties` 中的 `data.directory`（Docker 部署默认挂载为 `./.protegedata/protege/`）之下：

```
<data.directory>/
  data-store/
    project-data/
      <projectId>/                       projectId 为 UUID
        change-data/
          change-data.binary             ★ 本体真源：BinaryOWL 变更日志（追加写）
  lucene-indexes/<projectId>/            派生，不迁移
  uploads/                               临时上传文件，不迁移
  uploads-lucene-indexes/                派生，不迁移
```

出处：`LEG/inject/ChangeHistoryFileFactory.java`、`LEG/inject/project/ProjectDirectoryFactory.java`。

## 2. `change-data.binary`

- 格式：BinaryOWL（`org.semanticweb.binaryowl:binaryowl:2.0.1`，依赖 OWL API 4.x）。
- 读取：`BinaryOWLOntologyChangeLog.readChanges(inputStream, dataFactory, handler, SkipSetting.SKIP_NONE)`，每个回调对应一个修订（`OntologyChangeRecordList`）。
- 每个修订包含：
  - `getTimestamp()`：毫秒时间戳
  - `getMetadata()`（`BinaryOWLMetadata`），属性见下表
  - `getChangeRecords()`：`OWLOntologyChangeRecord` 列表，每条包含 `OWLOntologyID` 和变更数据：添加 / 删除公理、本体注解、导入。BinaryOWL 格式本身还支持“设置本体 ID”（`SetOntologyIDData`），但旧系统读到它会抛 `UnsupportedOperationException`（`LEG/change/OntologyChangeRecordTranslatorImpl.java`），所以正常数据里不应出现；迁移工具遇到时报错并列出位置。

| 元数据属性 | 类型 | 含义 |
|---|---|---|
| `username` | String | 提交用户的用户名（旧系统的 UserId 就是用户名） |
| `revision` | long | 修订号 |
| `description` | String | 高层描述 |
| `revisionType` | String | 写入时固定为 `EDIT` |

出处：`LEG/revision/RevisionStoreImpl.java`（`load`）、`RevisionSerializationTask.java`、`RevisionSerializationVocabulary.java`。

**注意（逆向发现的缺陷）**：旧系统 `load()` 遇到异常只记日志，不会报错退出，可能**静默地只加载了部分历史**。迁移工具必须遇错即失败，并报告出错的修订号和文件偏移。

## 3. MongoDB 集合

数据库名默认 `webprotege`。迁移工具不直接连 Mongo，而是读取 `mongoexport --jsonArray` 导出的 JSON 文件（避免把 Mongo 驱动带进新工程）。

| 集合 | 用途 | MVP 是否迁移 |
|---|---|---|
| `ProjectDetails` | 项目元数据：`_id`、`displayName`、`description`、`owner`、`inTrash`、`createdAt`、`createdBy`、`modifiedAt`、`modifiedBy`、`defaultLanguage`、`defaultDisplayNameSettings` | 是 |
| `Users` | 用户：用户名、`realName`、`emailAddress`、`avatarUrl`、盐值、加盐摘要 | 是，**不迁移密码**（见下） |
| `RoleAssignments` | 用户在项目上的角色分配 | 是 |
| `ProjectAccess` | 最近访问记录 | 否 |
| `PrefixDeclarations` | 项目前缀 | 是 |
| `EntityCrudKitSettings` | 新实体 IRI 生成设置 | 是 |
| `Forms`、`FormSelectors` | 表单 | 否（P6） |
| `Tags` | 标签 | 否（P6） |
| `Watches` | 关注 | 否（P6） |
| `EntityDiscussionThreads` | 讨论 | 否（P6） |
| `EntityGraphSettings`、`EntitySearchFilters`、`CollectionItemData` | 其它 | 否 |

**密码**：旧系统用加盐 MD5（`Md5DigestAlgorithmProvider`，见 `../webprotege/webprotege-cli/src/main/java/edu/stanford/bmir/protege/web/server/cmdline/SetupTools.java`），而且摘要是在浏览器端计算的。新系统不继承这种做法：迁移后的账户没有密码，首次登录前由管理员重置。

> 待确认：如果必须保留旧密码，需要另开一张卡，实现“首次登录时校验旧摘要并升级为 BCrypt”。默认不做。
