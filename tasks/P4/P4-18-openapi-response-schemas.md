---
id: P4-18
title: 补齐 OpenAPI 中无类型的成功响应（层级、Frame、搜索、修订、个体、设置、事件）并重新生成 @wp/api-types
phase: P4
module: wp-app, packages/api-types
type: impl
size: M
work_type: api-contract
ecc_extra: []
status: todo
depends_on: [P3-23, P3-27]
---

# P4-18 补齐成功响应的契约类型

## 目标
`docs/api/openapi.json` 中 Web 端要调用的每个成功响应都有具体的 schema，`@wp/api-types` 重新生成后，前端不需要手写任何响应类型（ADR-008）。接口的 URL、参数、JSON 形状和 HTTP 行为**一律不变**。

## 参考
- ADR-008（禁止手写请求和响应类型）、ADR-007
- P3-23 执行记录的偏差：GET 的 Frame、层级、搜索、修订等成功响应在快照中是无类型的 `{"type": "object"}`，原因是 `web` 模块不能引用 `ontology.dto` / `ontology.frame` 中的类型；当时只给 `Update*Frame.Request` 加了命名 `@Schema`，所以 `ClassFrame`、`EntityNode` 等已经作为独立 schema 存在
- 当前快照中无类型或缺失的成功响应（`docs/api/openapi.json`）：
  - `getHierarchyRoots`、`getHierarchyChildren`、`searchEntities` → 实际返回 `EntityNode[]`；`getHierarchyPaths` → `EntityNode[][]`
  - `getClassFrame`、`getObjectPropertyFrame`、`getDataPropertyFrame`、`getAnnotationPropertyFrame`、`getNamedIndividualFrame` → 对应的 `*Frame`
  - `updateClassFrame` 等 5 个更新 → `{frame: <对应 Frame>, revision: long}`（`UpdateClassFrame.Body` 与泛型的 `UpdatedFrame.Body<F>`）
  - `listRevisions`、`listEntityRevisions` → `RevisionPage{revisions: RevisionSummary[], page, size, total}`（`revision` 模块）；`getRevisionDiff` → `RevisionDiff{revNo, changes: RevisionChange[], limit, total}`；`revertRevision`、`deleteEntities` → `ProjectController.RevisionResponse{revision}`
  - `createEntities` → `CreateEntities.Body{entities: EntityNode[], revision}`（快照中的 201 内容没有 schema）
  - `listIndividualsByType` → `IndividualPage{individuals, page, size, total}`
  - `get/put PrefixSettings`、`get/put NewEntitySettings`、`get/put DisplayNameSettings`（请求体与响应都是 `JsonNode`）→ 形状见 P3-22 与 `backend/wp-app/src/main/java/wprebuild/app/project/ProjectSettingsDocuments.java`
  - `pollProjectEvents`（`List<Object>`）、`streamProjectEvents` 的 `data` → `events` 模块的 `ProjectChanged`、`EntityHierarchyChanged`、`EntityFrameChanged`、`BrowserTextChanged`、`PermissionsChanged`、`ResyncSignal`，用 `type` 字段区分
- 判别字段与实际 JSON 不一致（P3-27 已生成的类型）：`ontology/frame/ValueNode.java` 的子类型名是 `iri` / `literal`，但快照中 `ValueNode.discriminator` 只有 `propertyName: "type"`、没有 `mapping`，生成的 `schema.d.ts` 是 `IriValue: { type: "IriValue" }`、`LiteralValue: { type: "LiteralValue" }`；事件 record 的 `type` 是 `String`，生成 `type: string`，联合类型无法按 `type` 收窄
- `EntityNode.entityType` 在快照中只是 `string`；实际取值有 `CLASS`、`OBJECT_PROPERTY`、`DATA_PROPERTY`、`ANNOTATION_PROPERTY`、`NAMED_INDIVIDUAL`、`DATATYPE`（`events/EventTranslator.java`）、`IRI`（`ontology/frame/FrameRendering.java`）、`ONTOLOGY`（`revision/RevisionDiffRenderer.java`）
- `backend/wp-app/src/main/java/wprebuild/app/web/package-info.java`：`web` 可以依赖 `ontology`（根包）、`revision`、`events`，**不能**依赖 `ontology :: dto` 和 `ontology.frame`；由 `ModularityTest` 强制
- `docs/api/README.md`：刷新快照与生成类型的命令

## 产出
- `ProjectController`、`ProjectSettingsController`、`ProjectEventsController`：为上面列出的每个操作补 `@ApiResponse`（`responseCode` 与现有状态码一致，`mediaType = "application/json"`）；三个设置的 `PUT` 同时补 `@io.swagger.v3.oas.annotations.parameters.RequestBody` 的 schema。写法按能否直接引用类型区分：
  - 可以引用的类型（`ontology` 根包的 `RevisionDiff`、`IndividualPage`、`CreateEntities.Body`，`revision` 模块的 `RevisionPage`，`events` 模块的事件 record，`web` 中的 `RevisionResponse`）：用 `@Schema(implementation = …)` / `@ArraySchema`；
  - 不能引用的类型（`EntityNode`、各 `*Frame`）：用 `@Schema(ref = "#/components/schemas/EntityNode")` 这种字符串引用，不产生编译期依赖；
  - 注解表达不了的结构（`EntityNode[][]`、5 个泛型更新结果）：放进下面的 `OpenApiCustomizer`。
- `backend/wp-app/src/main/java/wprebuild/app/web/openapi/ResponseSchemasCustomizer.java`（`OpenApiCustomizer` Bean，在 `OpenApiConfiguration` 中注册）：
  - 注册 `ClassFrameUpdateResult`、`ObjectPropertyFrameUpdateResult`、`DataPropertyFrameUpdateResult`、`AnnotationPropertyFrameUpdateResult`、`NamedIndividualFrameUpdateResult` 五个 schema（`{frame: $ref <Frame>, revision: integer(int64)}`，两项都 `required`），并设为对应更新操作的 200 响应；
  - 把 `getHierarchyPaths` 的 200 响应设为 `array<array<$ref EntityNode>>`；
  - 给 `ValueNode.discriminator` 补 `mapping`：`iri` → `#/components/schemas/IriValue`、`literal` → `#/components/schemas/LiteralValue`，并把两个子 schema 的 `type` 设为单值 `enum`（`["iri"]`、`["literal"]`）；
  - 六个事件 schema 的 `type` 设为单值 `enum`（与 JSON 中的取值一致，例如 `ProjectChanged`、`resync`）；`streamProjectEvents` / `pollProjectEvents` 的 `oneOf` 加 `discriminator`（`propertyName: "type"`）与 `mapping`；
  - `EntityNode.entityType` 设为上面列出的 8 个取值的 `enum`；
  - `EntityNode` 的五个字段以及下面“必填字段”中列出的字段标为 `required`。领域模块（`ontology`、`revision`、`events`）中的 record 尽量不加注解，`required` / `enum` / `mapping` 都放在本 customizer 里完成。
- `backend/wp-app/src/main/java/wprebuild/app/web/openapi/SettingsSchemas.java`：只用于文档的 record（不参与运行时序列化）：`PrefixSettings`（`type: object`，`additionalProperties: string`）、`NewEntitySettings{iriPrefix, kit, labelProperty, defaultLang, targetOntology}`、`EntityCrudKitSettings`（`oneOf` UUID / SUPPLIED_NAME{whiteSpace: CAMEL_CASE | UNDERSCORES} / OBO{totalDigits, userRanges}，`discriminator` 为 `type`）、`OboUserRange{userId, start, end}`、`DisplayNameSetting{annotationProperty, lang}`；字段名与 `ProjectSettingsDocuments` 写出的 JSON 完全一致。
- schema 命名（只加注解，不改字段）：`CreateEntities.Body` → `@Schema(name = "CreatedEntities")`；`UpdateClassFrame.Body` 若因命名冲突出现在快照中，同样给出唯一名称；`ProjectController.RevisionResponse` → `RevisionResponse`；`ResyncSignal` → `ResyncSignal`。
- 必填字段：`EntityNode` 的五个字段、`RevisionPage` / `RevisionSummary` / `RevisionDiff` / `RevisionChange` / `IndividualPage` / `CreatedEntities` / `RevisionResponse` 和六种事件中构造器做了 `requireNonNull` 或是基本类型的字段，标为必填；可空字段（如 `lang`）保持可选。优先在 `ResponseSchemasCustomizer` 中设置；确实只能用注解的（例如 schema 改名），按 P3-23 的先例做**仅注解**修改，并在执行记录中列出改到的领域模块。
- `docs/api/openapi.json`：按 `docs/api/README.md` 的命令刷新。
- `frontend/packages/api-types/src/schema.d.ts`：`pnpm -C frontend --filter @wp/api-types run gen` 重新生成；`src/index.ts` 增加别名：`RevisionPage`、`RevisionSummary`、`RevisionDiff`、`RevisionChange`、`IndividualPage`、`CreatedEntities`、`RevisionResponse`、五个 `*FrameUpdateResult`、`PrefixSettings`、`NewEntitySettings`、`EntityCrudKitSettings`、`DisplayNameSetting`、六个事件类型，以及联合类型 `ProjectEvent`（五种项目事件，与 Java 的 `ProjectEvent` 一致，不含 `ResyncSignal`）与 `ProjectStreamMessage`（`ProjectEvent | ResyncSignal`，事件流中 `data` 的类型）。
- 测试：
  - `backend/wp-app/src/test/java/wprebuild/app/web/OpenApiResponseSchemasTest.java`（`@DatabaseTest` + `@AutoConfigureMockMvc`）：读取 `/v3/api-docs`，对上面列出的每个 operationId 断言成功响应在 `application/json` 下有 schema，且不是只有 `type: object` 的空 schema（必须有 `$ref`、`items`、`oneOf` 或 `properties` 之一）；断言 `EntityNode.required` 含五个字段。
  - `frontend/packages/api-types/src/__tests__/responses.test-d.ts`：`getHierarchyRoots` 的 200 响应类型等于 `EntityNode[]`；`getHierarchyPaths` 等于 `EntityNode[][]`；`getClassFrame` 等于 `ClassFrame`；`listRevisions` 等于 `RevisionPage`；`EntityNode["iri"]` 是 `string`（不再是可选）；`IriValue["type"]` 等于 `"iri"`、`LiteralValue["type"]` 等于 `"literal"`；`EntityNode["entityType"]` 是 8 个字面量的联合；`ProjectStreamMessage` 在 `if (m.type === "EntityFrameChanged")` 分支中收窄为 `EntityFrameChanged`。
  - `OpenApiResponseSchemasTest` 另外断言：`ValueNode.discriminator.mapping` 含 `iri`、`literal`；事件 schema 的 `type` 是单值 `enum`。

## 实现要点
- 这是一张**只改契约描述**的卡：不改控制器的映射、参数、返回值和状态码，不改任何 JSON 字段；`BackendAcceptanceTest` 和现有全部测试不改一行仍然通过，就是“行为不变”的证明。
- 不在 `web` 中 `import` `ontology.dto` 或 `ontology.frame` 的类型；`ModularityTest` 必须通过。不修改 `web/package-info.java` 的 `allowedDependencies`。
- `streamProjectEvents` 的内容类型是 `text/event-stream`，OpenAPI 只能描述 `data` 的结构；用 `oneOf` 列出六种事件即可，SSE 的帧格式在接口描述中用一句话说明。
- 本卡与 P4-01 ~ P4-05 可以并行，而它会改变生成类型（`EntityNode` 必填、`IriValue["type"]` 等）。所以验收最后一条在 `wp-web` 已存在时运行它的 `verify`：已合并的前端代码若因类型收紧而编译失败，在本卡中一并修正（只改类型用法，不改行为），修不了时按 `AGENTS.md` 第 6 节停下。
- 生成的 `schema.d.ts` 是生成物，不计入行数预期；手写部分（注解、customizer、文档 record、别名）预计在 400 行以内。

## ECC 技能
工作项类型：`api-contract` —— API 契约（OpenAPI 快照与契约测试）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P4-18` 查看。

- 开工前加载的 skill：`contract-first`、`api-design`
- 提交前调用的审查 agent：`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='OpenApiContractTest,OpenApiResponseSchemasTest,ModularityTest,BackendAcceptanceTest'
pnpm -C frontend install
pnpm -C frontend --filter @wp/api-types run check
pnpm -C frontend --filter @wp/api-types exec vitest run --typecheck
[ ! -f frontend/wp-web/package.json ] || pnpm -C frontend --filter wp-web run verify
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 快照中 Web 端要用的成功响应没有 `{"type": "object"}` 的空 schema
- [ ] 没有修改任何接口的 URL、参数、状态码或 JSON 形状
- [ ] 执行记录已填写

## 不在本卡范围
- 修改任何接口的行为或响应形状（发现必须改时按 `AGENTS.md` 第 6 节停下）
- `downloadProject` 的二进制响应（保持现状）
- 移动端令牌、API Key、管理接口（已有类型）
- 前端代码（P4-01 起）
