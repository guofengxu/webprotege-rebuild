---
id: P6-27
title: 后端：上传合并的准备——新增两个动作、带归属与过期时间的上传暂存、列出项目本体的接口
phase: P6
module: wp-app
type: impl
size: M
work_type: app-service
ecc_extra: [security]
status: done
depends_on: [P6-24]
---

# P6-27 后端：上传合并的准备

## 目标
为“上传并合并到已有项目”（BS-14）准备应用层基础：新增 `UPLOAD_AND_MERGE`、`UPLOAD_AND_MERGE_ADDITIONS` 两个动作，提供带归属与过期时间的上传暂存，以及列出项目本体的接口（“只追加”方式要选择目标本体）。预览、应用与放弃接口在 P6-50。（P6-19 评审后从原 P6-27 拆出，避免超过 400 行。）

## 参考
- BS-14；BS-10 的第二轮补充（两个动作归入 CAN_MANAGE，与旧系统的 PROJECT_MANAGER 一致）
- P3-09：`ontology/upload/OntologySourceParser`、`UploadProperties`（`wp.upload.max-size`）、`web/ProjectController.java#upload`（临时文件的写入与清理）
- `access/Action.java`、`access/ProjectRole.java`、`backend/wp-app/src/test/java/wprebuild/app/access/BS10_AccessServiceTest.java`；P6-24（上一张修改这些文件、`ProjectController.java` 与 OpenAPI 生成物的卡）
- 内核：`ontology/ProjectOntologies`（`keys()`、`info(key)` → `OntologyInfo(key, ontologyIri, versionIri)`、`axioms(key)`）

## 产出
- `access/Action.java`：增加项目级动作 `UPLOAD_AND_MERGE`、`UPLOAD_AND_MERGE_ADDITIONS`；`access/ProjectRole.java`：归入 `CAN_MANAGE`；`BS10_AccessServiceTest` 增加断言（`CAN_EDIT` 没有、`CAN_MANAGE` 有）。
- `ontology/upload/UploadStaging.java`：
  - `UUID stage(ProjectId projectId, UserId userId, Path source, StagedUploadMeta meta)`：把文件移入 `wp.upload.staging-dir`（默认系统临时目录下的 `wp-staging/`），磁盘上的文件名就是 `uploadId`，不使用上传的原始文件名；`StagedUploadMeta` 记录方式、目标本体、预览时的修订号与创建时间；
  - `Optional<StagedUpload> find(ProjectId projectId, UserId userId, UUID uploadId)`：不存在、已过期，或不属于该用户与项目时为空；`void discard(...)` 删除文件与登记；
  - 过期时间 `wp.upload.staging-ttl`（默认 30 分钟），定时任务删除过期暂存；同一用户同时最多 3 个暂存，超出时 409 `TOO_MANY_STAGED_UPLOADS`；时钟可注入，便于测试。
- `ontology/ListProjectOntologies.java` 与处理器：`requiredAction()` 为 `VIEW_PROJECT`，在读锁内返回 `List<ProjectOntologySummary(String key, String ontologyIri, String versionIri, long axiomCount)>`（IRI 为空时为 `null`）。
- `web/ProjectController.java`：`GET /api/projects/{projectId}/ontologies`，operationId `listProjectOntologies`。
- `docs/api/openapi.json` 更新快照；`OpenApiResponseSchemasTest` 增加该响应；`pnpm -C frontend gen` 重新生成 `frontend/packages/api-types/src/schema.d.ts`。
- 测试：
  - `ontology/upload/UploadStagingTest.java`（单元测试，临时目录，可注入时钟）：归属校验（别的用户、别的项目找不到）；过期后找不到且文件已删除；每用户上限；放弃后文件删除；磁盘文件名不含原始文件名；
  - `web/ProjectOntologiesTest.java`（Testcontainers）：`CAN_VIEW` 成员可以读取，返回 key、IRI 与公理数；非成员 403；项目不存在 404。

## 实现要点
- 暂存只在本机磁盘，单实例有效；多实例下的处理留给 P6-40，在 `UploadStaging` 的 Javadoc 中说明。
- 本卡不解析上传内容，也不计算合并计划（P6-50）。
- 依赖 P6-24 是为了串行修改 `Action.java`、`ProjectRole.java`、`ProjectController.java` 与 OpenAPI 生成物（见 EPIC「共享文件与迁移号（第二轮）」）。本卡不新增 Flyway 迁移。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口；附加：`security`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-27` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`、`security-review`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`、`security-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='UploadStagingTest,ProjectOntologiesTest,BS10_AccessServiceTest,OpenApiContractTest,OpenApiResponseSchemasTest,ModularityTest'
pnpm -C frontend install
pnpm -C frontend --filter @wp/api-types run check
pnpm -C frontend --filter wp-web run typecheck
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写

## 不在本卡范围
- 预览、应用与放弃接口（P6-50）；Web 端（P6-28）
- 多实例下的暂存共享（P6-40）

## 执行记录
- 日期：2026-11-19
- 分支：feature/p6-27-upload-merge-api
- 测试：2026-11-19T15:03:00+08:00 通过（6 项）
- 实际改动：Added UPLOAD_AND_MERGE / UPLOAD_AND_MERGE_ADDITIONS under CAN_MANAGE; UploadStaging (UUID filenames, ownership, clock-stamped TTL, per-user limit 3, POSIX owner perms); ListProjectOntologies + GET /api/projects/{id}/ontologies; OpenAPI and api-types regenerated.
- 偏差：无
- 审查：java-reviewer, code-reviewer, security-reviewer
