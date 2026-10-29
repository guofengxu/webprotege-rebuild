---
id: P3-12
title: 下载导出：任意修订、多格式（BS-12）
phase: P3
module: wp-app/ontology, web
type: impl
size: M
work_type: app-service
ecc_extra: []
status: todo
depends_on: [P3-08]
---

# P3-12 下载导出：任意修订、多格式（BS-12）

## 目标
用户可以把项目的任意历史版本下载为常用格式。

## 参考
- BS-12
- 旧实现：`../webprotege/webprotege-server-core/src/main/java/edu/stanford/bmir/protege/web/server/download/`、`../webprotege/webprotege-shared/src/main/java/edu/stanford/bmir/protege/web/shared/download/DownloadFormatExtension.java`

## 产出
- `GetProjectDownload(ProjectId, Optional<RevisionNumber>, DownloadFormat)` 命令（需要 `DOWNLOAD_PROJECT`）；`DownloadFormat`：`owl`、`ttl`、`owx`、`omn`、`ofn`。
- 修订号等于 head 时直接从 `ProjectRuntime` 写出（持读锁）；小于 head 时，取不晚于该修订的最近快照，重放到目标修订，得到一个**临时的**本体集合（不放进注册表）。
- 只有一个本体时直接返回文件；多个本体时返回 zip，文件名为 `<key>-<本体 IRI 最后一段>.<扩展名>`。`Content-Disposition` 文件名为 `<项目名>-r<修订号>.<扩展名或 zip>`，项目名中的非法字符替换为 `_`。
- `GET /api/projects/{id}/download?format=ttl&revision=12`。
- 测试 `DownloadTest`：上传后下载再解析，公理集合相等；下载修订 1 与 head 的内容不同且都正确；多本体项目返回 zip；CAN_VIEW 用户可以下载，未分配角色的用户得到 403。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P3-12` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=DownloadTest
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 导出为 OBO 格式
