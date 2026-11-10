---
id: P3-19
title: 实体搜索（Lucene 10）
phase: P3
module: wp-app/search, web
type: impl
size: M
work_type: app-service
ecc_extra: []
status: done
depends_on: [P3-13]
---

# P3-19 实体搜索（Lucene 10）

## 目标
用户输入几个字就能找到实体，结果实时反映最新修订。

## 参考
- ADR-009
- BS-09
- 旧实现：`../webprotege/webprotege-server-lucene/`

## 产出
- 依赖：`org.apache.lucene:lucene-core`、`lucene-analysis-common`、`lucene-queryparser`（版本 `${lucene.version}`，在根 POM 声明）。
- `wprebuild.app.search.ProjectSearchIndex`：每个项目一个目录 `${wp.data-dir}/search/<projectId>`；文档字段：IRI、实体类型、本地名、每个显示名称设置对应的名称、已处理到的修订号（存在索引的 commit user data 中）。
- 中文检索：名称字段同时用 `StandardAnalyzer` 和 `CJKAnalyzer` 建两个子字段，查询时两者都查；英文支持前缀匹配。
- `SearchIndexMaintainer`：监听 `RevisionCommittedEvent`，根据变更的签名增量更新受影响的实体；打开索引时若其修订号落后于 head，从 `ProjectRuntime` 全量重建。
- 命令 `SearchEntities(ProjectId, String query, Set<EntityType> types, int limit)`（需要 `VIEW_PROJECT`）；`GET /api/projects/{id}/search?q=...&types=class,object-property&limit=20`；结果为 `EntityNode` 列表，按相关度排序。
- 测试 `SearchTest`：中文“托盘”能搜到“周转托盘”；英文前缀 `pal` 能搜到 `Pallet`；创建新实体后立即可以搜到；删除索引目录后重启能自动重建；按类型过滤。

## 实现要点
- 无特别要求，遵循 `AGENTS.md` 与 `.cursor/rules/`。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P3-19` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest=SearchTest
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 执行记录已填写

## 不在本卡范围
- 跨项目搜索
- 按注解值全文检索

## 执行记录
- 日期：2026-11-10
- 分支：feature/p3-19-search
- 测试：2026-11-10T11:58:27+08:00 通过（3 项）
- 实际改动：每个项目一个 Lucene 索引，中文用 CJK 二元组、英文用前缀匹配；修订提交后增量更新，索引落后或目录被删时从运行时重建。GET /api/projects/{id}/search 返回按相关度排序的 EntityNode。
- 偏差：生产代码超过 M 卡 400 行预期。查询按分析器词元做合取，不用 QueryParser.parse，以避免 AND/OR 和中文二元组误命中；lucene-queryparser 仍按卡片声明为依赖。ontology.dto 标为 NamedInterface，search 的允许依赖和 overview 增加 ontology :: dto，以便返回 EntityNode。
- 审查：java-reviewer, code-reviewer
