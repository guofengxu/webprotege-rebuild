---
id: P3-09
title: 上传导入：多格式与 zip（BS-12）
phase: P3
module: wp-app/ontology, web
type: impl
size: M
work_type: app-service
ecc_extra: [security]
status: todo
depends_on: [P3-08]
---

# P3-09 上传导入：多格式与 zip（BS-12）

## 目标
用户上传本体文件即可创建项目，修订 1 就是上传的内容。

## 参考
- BS-03、BS-12
- 旧实现：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/project/ProjectImporter.java`、`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/project/ZipArchiveProjectSourcesExtractor.java`、`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/project/RootOntologyDocumentMatcherImpl.java`、`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/upload/`

## 产出
- `wprebuild.app.ontology.upload.OntologySourceParser`：`List<OWLOntology> parse(Path file)`；支持 BS-12 的五种格式（由 OWL API 自动识别）；zip：解压到临时目录，用 `OWLOntologyIRIMapper` 把导入映射到 zip 内的文件，根文档 = 没有被其它文档导入的那个文档，有多个根时报错；**缺失的导入一律忽略，不访问网络**。
- `ImportOntologyFromUpload(String name, String description, Path file)` 命令：创建项目（复用 P3-08 的逻辑，但不写空本体的修订 1），用 `InitialImportGenerator` 写修订 1；需要 `UPLOAD_PROJECT`。
- `POST /api/projects/upload`（multipart：`file`、`name`、`description`）；大小上限 `wp.upload.max-size`（默认 200MB），超出返回 413。
- 解析失败返回 400，ProblemDetail 中带解析器给出的行号和消息。
- 测试 `UploadImportTest`：`testdata/ontologies/formats/` 下的五种格式各上传一次，公理数一致；`imports/` 打包为 zip 后上传，得到 3 个本体；导入了不可解析 IRI（`http://invalid.invalid/`）的文件能在 2 秒内导入成功；损坏的文件返回 400；超过大小返回 413。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口；附加：`security`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P3-09` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`、`security-review`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`、`security-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=UploadImportTest
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 上传并合并到已有项目（P6）
