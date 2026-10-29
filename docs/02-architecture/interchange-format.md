# 迁移包格式 v1

生产者：`wp-legacy-migrator`（P2）。消费者：`wp-app` 的 `migration` 模块（P3-10）。
两边只通过本格式交换数据，不共享代码（ADR-003）。

## 1. 目录结构

```
<输出目录>/
  users.json                       全部用户（不含密码）
  projects/
    <projectId>/
      manifest.json                项目元数据、本体登记表、统计、设置
      revisions.jsonl              每行一个修订，按修订号升序
      head.ofn-lines.gz            head 修订时的全部公理 / 注解 / 导入，每个物理行一条规范形式变更行（op 固定为 +，按 ADR-011 做行转义），按字符串排序
      SHA256SUMS                   以上三个文件的 SHA-256
  migration-report.json            迁移报告：成功 / 失败项目、警告
```

## 2. `users.json`

```json
[
  { "username": "alice", "realName": "Alice", "email": "alice@example.com" }
]
```

## 3. `manifest.json`

```json
{
  "formatVersion": 1,
  "projectId": "2f0b8a6e-6c1e-4c2d-9a51-0b5e0c3c1d11",
  "exportedAt": "2026-11-05T08:00:00Z",
  "source": { "tool": "wp-legacy-migrator", "toolVersion": "0.1.0", "legacyDataDir": "/srv/webprotege" },
  "details": {
    "displayName": "Warehouse",
    "description": "",
    "owner": "alice",
    "inTrash": false,
    "createdAt": 1690000000000,
    "createdBy": "alice",
    "modifiedAt": 1700000000000,
    "modifiedBy": "bob"
  },
  "ontologies": [
    { "key": "o1", "ontologyIri": "http://example.org/wh", "versionIri": null },
    { "key": "o2", "ontologyIri": null, "versionIri": null }
  ],
  "stats": { "revisionCount": 412, "headRevision": 412, "changeCount": 58231 },
  "prefixes": { "wh": "http://example.org/wh#" },
  "entityCrudKitSettings": { "raw": { } },
  "displayNameSettings": { "raw": { } },
  "roleAssignments": [ { "username": "bob", "roles": ["CAN_EDIT"] } ]
}
```

- **本体登记表 `ontologies`**：按本体在修订日志中**首次出现的顺序**分配 key（`o1`、`o2`……）。命名本体用 `(ontologyIri, versionIri)` 识别；匿名本体每个分配一个独立的 key，`ontologyIri` 为 `null`。
- `entityCrudKitSettings`、`displayNameSettings`：原样保留 Mongo 文档（`raw`），由 P3-10 在导入时转换。转换规则在 P3-10 中写明。
- `roleAssignments`：把旧角色映射到 BS-10 的 MVP 角色，映射表由 P2-04 生成，无法映射的角色写入迁移报告的警告。

## 4. `revisions.jsonl`

每行一个 JSON 对象：

```json
{"rev":1,"user":"alice","ts":1690000000000,"desc":"Uploaded project","changes":["+\tAXIOM\to1\tDeclaration(Class(<http://example.org/wh#Pallet>))","+\tANNOTATION\to1\tAnnotation(<http://www.w3.org/2000/01/rdf-schema#comment> \"demo\")","+\tIMPORT\to1\t<http://example.org/other>"]}
```

- `changes` 中每个元素是 ADR-011 定义的**规范形式**变更行 `<op>\t<kind>\t<ontologyKey>\t<ofn>`，作为 JSON 字符串保存；字面量中的换行由 JSON 转义，不使用 ADR-011 的行转义。
- `ofn` 只要求能被 OWL API 4 和 5 解析为结构相同的对象，不要求两边渲染出的字符串逐字相同。
- `kind = AXIOM`：`ofn` 为单条公理，使用完整 IRI。
- `kind = ANNOTATION`：`ofn` 为单个本体注解 `Annotation(...)`。
- `kind = IMPORT`：`ofn` 为被导入本体的 IRI，用尖括号括起来。
- 修订号必须从 1 开始连续递增；出现空洞或乱序时，迁移工具报错退出。

## 5. 校验规则（P2、P3-10 都要实现）

| 编号 | 规则 |
|---|---|
| IC-1 | `SHA256SUMS` 与文件内容一致 |
| IC-2 | `formatVersion == 1` |
| IC-3 | 修订号连续，且 `stats.headRevision` 等于最后一行的 `rev` |
| IC-4 | 所有变更的条数之和等于 `stats.changeCount` |
| IC-5 | 每个 `ontologyKey` 都在 `ontologies` 中登记过 |
| IC-6 | 每个 `ofn` 都能被解析，失败时报告修订号和变更序号 |
| IC-7 | 新系统重放全部修订后的内容，与 `head.ofn-lines.gz` 用 OWL API 5 解析出的内容**按 OWL 对象结构相等**（不比较渲染后的字符串，因为 OWL API 4 和 5 的渲染细节可能不同） |
| IC-8 | 每个本体中各类公理（按 `AxiomType`）的数量，与 `head.ofn-lines.gz` 中的统计一致；不一致时报告差异最大的前 20 个公理类型 |

`head.ofn-lines.gz` 由迁移工具在 OWL API 4 下重放全部修订后输出（P2-05），它是 IC-7、IC-8 的比对基准。
