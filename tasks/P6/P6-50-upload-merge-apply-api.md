---
id: P6-50
title: 后端：上传并合并到已有项目的预览、应用与放弃接口（在生成锁内校验预览时的修订号）
phase: P6
module: wp-app
type: impl
size: M
work_type: app-service
ecc_extra: [security]
status: todo
depends_on: [P6-27, P6-26]
---

# P6-50 后端：上传合并的预览、应用与放弃接口

## 目标
项目管理者上传一个本体文件（或 zip），选择“新版本差异合并”或“只追加”，先看到预览（每个本体新增 / 删除多少公理、本体注解变化、未配对的上传本体、前若干条公理的文本），确认后作为一个修订应用；预览之后项目有了新修订时拒绝应用，要求重新预览。（P6-19 评审后从原 P6-27 拆出。）

## 参考
- BS-14
- P6-26：`UploadMergePlanner`、`UploadMergeGenerator`、`UploadMergePlan`、`UploadMergeMode`；`ChangePipeline.apply(UserId, ChangeListGenerator, RevisionNumber expectedHead)` 与 `StaleHeadException`
- P6-27：`UploadStaging`、两个动作、`listProjectOntologies`
- P3-09：`ontology/upload/OntologySourceParser#parse(Path)`、`UploadProperties`；P3-20：`ontology/RevisionDiffRenderer.java`（把公理渲染成一行文本，预览沿用同一种渲染）

## 产出
- 命令与处理器（`ontology` 模块）：
  - `PreviewUploadMerge(projectId, Path file, mode, Optional<OntologyKey> target)`：`requiredAction()` 按方式取 `UPLOAD_AND_MERGE` 或 `UPLOAD_AND_MERGE_ADDITIONS`；在读锁之外解析文件，在读锁内记录当前修订号并调用 `UploadMergePlanner`，然后用 `UploadStaging` 暂存文件；返回 `UploadMergePreview(uploadId, mode, baseRevision, List<OntologyPatchSummary> ontologies, List<String> unmatchedUploads, long totalAdded, long totalRemoved)`，每个 `OntologyPatchSummary` 含本体 key 与 IRI、四个计数，以及最多 200 条新增与 200 条删除公理的文本；
  - `ApplyUploadMerge(projectId, uploadId, long baseRevision, String description)`：取出暂存并重新解析，调用 `pipeline().apply(user, new UploadMergeGenerator(...), new RevisionNumber(baseRevision))`；`StaleHeadException` 映射为 409 `PROJECT_CHANGED_SINCE_PREVIEW`（暂存保留，可以重新预览）；成功后删除暂存，返回 `{ revision }`（没有有效变更时为 `null`）；
  - `DiscardUploadMerge(projectId, uploadId)`。
- `web/ProjectController.java`：
  - `POST /api/projects/{projectId}/merge-uploads`（multipart：`file`、`mode`、可选 `targetOntology`），operationId `previewUploadMerge`；
  - `POST /api/projects/{projectId}/merge-uploads/{uploadId}/apply`（`{ baseRevision, description? }`），operationId `applyUploadMerge`；
  - `DELETE /api/projects/{projectId}/merge-uploads/{uploadId}`，operationId `discardUploadMerge`，204；
  - 暂存不存在、已过期或不属于当前用户与项目时 404 `UPLOAD_NOT_FOUND`；文件无法解析时 400（沿用 P3-09 的错误码）；超过大小上限 413；只追加时 `targetOntology` 缺失或不存在 400。
- `docs/api/openapi.json` 更新快照；`OpenApiResponseSchemasTest` 增加响应；`pnpm -C frontend gen` 重新生成类型。
- 测试 `web/UploadMergeTest.java`（Testcontainers，用 `testdata/ontologies/` 中的样例本体构造“修改过的新版本”）：
  - 新版本方式：预览的计数与公理文本正确；应用后项目本体与上传版本一致，修订历史中有一个修订；回滚后恢复；
  - 只追加方式：只有新增；目标本体不存在 400；
  - 预览后另一个用户编辑了项目：应用得到 409 且项目没有新修订、另一个用户的编辑仍在；重新预览后可以应用；
  - 别的用户或别的项目使用该 `uploadId` 404；放弃后再应用 404；
  - `CAN_EDIT` 成员 403，`CAN_MANAGE` 成员成功；回收站中的项目 409；
  - zip 中含有路径穿越条目（`../`）时拒绝，且没有在暂存目录之外写出文件。

## 实现要点
- 预览之后的并发编辑只能在生成锁内发现（P6-26 的 `expectedHead` 重载），处理器里不要先读 head 再调用两参数的 `apply`。
- 上传文件可能很大：预览只返回计数与前 200 条文本，完整差异在应用后可以从修订差异（P3-20）查看。
- 解析在读锁之外进行；应用时由写入流水线负责加锁（ADR-006），处理器里不手动加锁。
- 依赖 P6-27 是为了串行修改 `ProjectController.java` 与 OpenAPI 生成物，并使用它的暂存。本卡不新增 Flyway 迁移。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口；附加：`security`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-50` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`、`security-review`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`、`security-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='UploadMergeTest,UploadImportTest,OpenApiContractTest,OpenApiResponseSchemasTest,ModularityTest'
pnpm -C frontend install
pnpm -C frontend --filter @wp/api-types run check
pnpm -C frontend --filter wp-web run typecheck
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- Web 端（P6-28）
- 多实例下的暂存共享（P6-40）
- 逐条勾选要应用的公理（旧系统也不支持）
