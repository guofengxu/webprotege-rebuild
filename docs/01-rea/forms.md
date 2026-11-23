# 表单（Forms）逆向分析

对象：`../webprotege` 的表单子系统。方法见 `README.md`（五层）。本文件只写结论与出处；可测行为见 `behavior-specs.md` 的 BS-16 ~ BS-18。

路径缩写：`LEG` / `LEG_SHARED` / `LEG_TEST` 同 `behavior-specs.md`；客户端为  
`LEG_CLIENT = ../webprotege/webprotege-client/src/main/java/edu/stanford/bmir/protege/web/client`。

规模（约）：server-core `form/` ~83 文件；shared `form/` ~146；client `form/` ~237。

---

## 1. 边界与上下文

| 边界 | 内容 |
|---|---|
| 对内 | 项目级**表单描述符**与**适用条件**；按实体渲染/编辑表单值；保存时转为公理变更 |
| 对外入口 | GWT RPC（实体表单读写、描述符 CRUD）；Jersey REST `/data/projects/{id}/forms`（**仅描述符**） |
| 不越界 | 不另存“表单值”真源；不替代 Frame 编辑器（独立 portlet）；不负责 Collections 遗留表单（见偶然实现） |
| 权限切分 | 改描述符 / 选择器：`EDIT_FORMS`；看实体表单：`VIEW_PROJECT`；改实体值：`EDIT_ONTOLOGY` |

与新工程：Frame 读写（P3-15 ~ P4-22）已提供 `from`/`to` 乐观编辑与 `ChangePipeline`；表单保存必须复用同一写路径，不得另开旁路。

---

## 2. 静态结构

### 2.1 概念层次

```
Project
├── FormDescriptor*          （布局：字段、控件、可选 subject factory）
├── EntityFormSelector*      （criteria → FormId；与描述符分存）
└── （运行时）FormData        （由本体 + 描述符投影，不持久化）
```

- **`FormDescriptor`**：`formId`（UUID）、`LanguageMap` 标签、`FormFieldDescriptor` 列表、可选 `FormSubjectFactoryDescriptor`。  
  出处：`LEG_SHARED/form/FormDescriptor.java`。
- **`FormFieldDescriptor`**：字段 id、可选 `OwlBinding`、标签/帮助、`Optionality` / `Repeatability` / `readOnly` / `FieldRun` / 展开状态、**一个** `FormControlDescriptor`。  
  出处：`LEG_SHARED/form/field/FormFieldDescriptor.java`。
- **八种控件**（Jackson `type`）：`TEXT`、`NUMBER`、`SINGLE_CHOICE`、`MULTI_CHOICE`、`ENTITY_NAME`、`IMAGE`、`GRID`、`SUB_FORM`。  
  出处：`LEG_SHARED/form/field/FormControlDescriptor.java` 及各 `*ControlDescriptor`。
- **四种 OWL 绑定**（`OwlBinding.type`）：`PROPERTY`（可带值过滤条件）、`CLASS`（父类/类型）、`INSTANCE`（类上的个体）、`SUBCLASS`（直接子类）。  
  出处：`LEG_SHARED/form/field/OwlBinding.java`；读：`LEG/form/BindingValuesExtractor.java`；写：`LEG/form/FormFrameBuilder.java`。
- **`EntityFormSelector`**：`(projectId, formId, CompositeRootCriteria)`。条件语言复用实体匹配 DSL（`EntityTypeIsOneOf`、`SubClassOf`、`InstanceOf`、注解/关系等）。  
  出处：`LEG_SHARED/form/EntityFormSelector.java`；解析：`LEG/form/EntityFormManager.java`。
- **选择列表**：固定（`FixedChoiceListSourceDescriptor`）或动态（`DynamicChoiceListSourceDescriptor` + `EntityMatchCriteria`）。
- **Grid**：列描述符可再嵌套控件（含嵌套 GRID）；行可带 `FormSubjectFactoryDescriptor` 创建新主体。
- **SubForm**：字段控件内嵌完整 `FormDescriptor`（可递归）。

孤儿类型（定义但无引用，不必移植）：`FormGroup`、`CompositeFieldDescriptorEntry`、`NodeType`。

### 2.2 动作一览（实体表单主线）

| 动作 | 用途 | 旧权限 |
|---|---|---|
| `GetProjectFormDescriptors` / `Set*` / `DeleteForm` / `GetFreshFormId` / `CopyFormDescriptorsFromProject` | 描述符管理 | 多为 `EDIT_FORMS`（见缺陷） |
| `GetEntityFormDescriptor` / `SetEntityFormDescriptor` | 单表单 + 选择器 | 读 `VIEW_PROJECT`；写 `EDIT_FORMS` |
| `GetEntityForms` | 适用表单 + 填好的 `FormDataDto`（分页/过滤/排序/语言） | `VIEW_PROJECT` |
| `SetEntityFormsData` | 提交 pristine/edited 映射 | `EDIT_ONTOLOGY` |

Collections 相关 `GetFormDescriptor` / `SetFormData` **不纳入**新工程。

### 2.3 客户端分层

- **设计时**：`FormsPlace` / `EditFormPlace`、`FormsManagerPresenter`、`FormEditorPresenter`（需 `EDIT_FORMS`）。
- **运行时**：portlet `portlets.form` → `EntityFormStackPresenter`（按匹配表单分 tab；Edit/Apply 需 `EDIT_ONTOLOGY`）。

---

## 3. 动态行为

### 3.1 按实体取表单（读）

1. `GetEntityFormsAction` → `GetEntityFormActionHandler`。
2. `EntityFormManager`：读项目全部 `FormSelectors`，用 `MatchingEngine` 过滤命中实体的 `formId`，再加载对应 `FormDescriptor`。
3. `EntityFrameFormDataDtoBuilder` 按字段/`OwlBinding` 从内存索引投影值（`BindingValuesExtractor`）；可分页（默认页大小 10）、语言过滤、区域过滤与排序（主要用于 GRID）。
4. 客户端把结果存为 **pristine** 快照，供之后保存求差。

**注意**：`EntityFrameMapper` 在旧树中已无注入调用方；行为以 `BindingValuesExtractor` 为准。

### 3.2 保存实体表单（写）

1. 客户端提交 `SetEntityFormsDataAction(projectId, entity, pristineFormsData, editedFormsData)`。
2. `FormDataUpdateSanityChecker`：两边 `FormId` 集合一致，且每对 `FormDescriptor` 相等。
3. `EntityFormChangeListGenerator`：对每个表单，`FormData` → `FormFrame`（`FormDataConverter` / `FormDataProcessor`）→ `FormFrameFlattener` 按主体摊平 → `FormFrameConverter` → `PlainEntityFrame` → **与 Frame 编辑相同的** `FrameChangeGenerator`；类主体额外用类断言处理 `instances` 集合。
4. 经 `AbstractProjectChangeHandler` → `ChangeManager.applyChanges`（生成锁 → 临时 IRI 固化 → 写锁 → 修订 → 事件）。
5. 新行 / 子表单无主体时：`FormSubjectFactoryDescriptor` + `EntityFormSubjectFactory` 生成临时 IRI，由变更管线铸造正式 IRI。

**本质**：表单值**从不**写入 Mongo；唯一持久化结果是修订日志中的公理变更。

**新系统并发（相对旧系统）**：请求同时带 `pristine`/`edited` 与项目 `expectedHead`。在生成锁内：（1）head 必须仍等于 `expectedHead`；（2）由 pristine 推出的各主体 Frame 必须等于当前运行时（对齐 Frame 编辑的 `from` 校验），否则分别以 `STALE_HEAD` / `FORM_DATA_CHANGED`（或与 Frame 共用的 `FRAME_CHANGED`）拒绝。详见 BS-18。

### 3.3 描述符生命周期

- 单条 upsert：`saveFormDescriptor` 保留已有 `ordinal`；整表替换：`setProjectFormDescriptors` 按序重写 ordinal。
- 选择器按 `(projectId, formId)` upsert；与描述符分集合存储。
- 跨项目复制（`FormsCopier`）：目标尚无同 id 时分配**新** `FormId` 并复制选择器。

### 3.4 并发

旧系统表单保存**不**带期望修订号；依赖客户端 pristine 与生成锁内 `FrameChangeGenerator` 的“服务器 Frame 已等于目标则跳过”。源码有三路合并 TODO。  
**新系统**：保存必须带期望 head（`ChangePipeline.apply(..., expectedHead)`），冲突返回可识别错误（与 BS-14 上传合并一致），见 BS-18。

---

## 4. 数据与状态

| 集合 / 状态 | 内容 | 迁移 |
|---|---|---|
| Mongo `Forms` | `FormDescriptorRecord`：`projectId`、`ordinal`、`formDescriptor`（Jackson 图） | 是（P6） |
| Mongo `FormSelectors` | `EntityFormSelector` JSON；无额外索引 | 是（P6） |
| 本体公理 | 字段值的真源 | 已由修订迁移覆盖 |
| `FormDataRepository` | Morphia 记录，**无装配、IT 已注释** | 否 |

建议 PostgreSQL 形状（实现卡细化）：

- `project_form(project_id, form_id, ordinal, descriptor jsonb)`，唯一 `(project_id, form_id)`
- `project_form_selector(project_id, form_id, criteria jsonb)`，唯一 `(project_id, form_id)`

JSON 形状应尽量与旧 Jackson 多态 `type` 字段兼容，便于 mongoexport 直写。

**迁移注意**：首期选择器匹配器只认 `EntityTypeIsOneOf` / `SubClassOf`。导出/导入须在 `migration-report.json` 中警告含未支持节点的选择器（导入后这些表单在运行时不会命中任何实体，描述符仍可通过管理 API 看到）。完整 MatchingEngine 见 P6-46。

---

## 5. 演进证据

- `docs/01-rea/README.md`：2019 年后 server-core 中 `form/` 文件变更次数仅次于 `index`，是后期扩张最快的业务子系统之一。
- 读路径从早期“存 FormData”演进为“只存描述符、值从公理投影”；`FormDataRepository_IT` 整文件注释是直接证据。
- REST 只覆盖描述符，实体读写仍走 GWT——典型的双通道遗留。

---

## 6. 本质约束与偶然实现

| 类别 | 内容 | 新工程 |
|---|---|---|
| 本质 | 描述符与选择器是项目元数据；值是公理；保存 = Frame 差分 → 修订 | **继承**（BS-16 ~ BS-18） |
| 本质 | 设计权限与编辑本体权限分离（`EDIT_FORMS` vs `EDIT_ONTOLOGY`） | **继承**；补全旧系统漏检 |
| 本质 | 八种控件 + 四种绑定 + 选择器条件的**语义** | 首期子集见 EPIC；完整语义保留在规格与 JSON 中 |
| 本质 | 嵌套主体摊平后再差分；新实体走临时 IRI | **继承** |
| 偶然 | GWT RPC、DTO 双树、Mongo/Morphia、Jersey 仅描述符 | REST + PostgreSQL jsonb |
| 偶然 | 客户端持有 pristine、无期望修订号 | 改为 `expectedHead` |
| 偶然 | `FormGroup` 等死类型、Collections 表单、未接线的 `FormDataRepository` | **不移植** |
| 偶然 | `OwlClassBinding`/`OwlInstanceBinding` 工厂接受但不持久化的 value filter | **不移植**该死接口 |
| 缺陷 | `SetProjectFormDescriptors` / `UpdateFormDescriptor` 等未声明 `EDIT_FORMS` | **必须修复** |
| 缺陷 | `FormFrameConverter` 忽略 class 的 `subClasses`（子类绑定可能写不进差分） | 首期若支持 `SUBCLASS` 绑定须按测试钉死或标明不做 |
| 缺陷 | 类 `instances` 公理只写默认本体 | 新工程跟随项目默认本体约定并写清 |

---

## 7. 与现有新工程能力的衔接

| 能力 | 衔接点 |
|---|---|
| Frame 更新 | 表单差分应落到与 `Update*FrameGenerator` 同等的公理语义；可复用内核变更管线 |
| 乐观并发 | `ChangePipeline` 三参数 `expectedHead`（P6-26） |
| 权限 | `Action` / `ProjectRole` 增加 `EDIT_FORMS` → `CAN_MANAGE`（对齐旧 PROJECT_MANAGER） |
| Web | `useFrameEditor` 的 Edit/Apply、`LiteralValueInput`、`EntityPicker`；表单需独立面板与多 tab |
| 条件语言 | 完整 `MatchingEngine` 规模大（标签卡 P6-46 也依赖）；表单首期只用子集 |
| 迁移包 | 扩展 interchange（项目下增加 forms 产物）；无“表单值”文件 |

---

## 8. 测试锚点（移植断言时优先）

- 仓储往返：`LEG_TEST/form/EntityFormRepositoryImpl_IT.java`、`EntityFormSelectorRepositoryImpl_IT.java`
- 序列化：`FormDescriptor_Serialization_IT`、`form/field/*_Serialization_TestCase`
- 写路径：`FormFrameFlattener_TestCase`、`processor/FormDataProcessor_TestCase`、`Grid*Processor_TestCase`
