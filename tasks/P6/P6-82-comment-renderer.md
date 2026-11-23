---
id: P6-82
title: 讨论：服务端 CommentRenderer（CommonMark + 提及 span + HTML 消毒）
phase: P6
module: wp-app/discussion
type: impl
size: S
work_type: app-service
ecc_extra: [security, rea]
status: todo
depends_on: [P6-74]
---

# P6-82 评论 Markdown 渲染器

## 目标
`discussion` 模块可把评论 Markdown 渲染为消毒后的 HTML（含 `@user` 提及 span），供 REST 写入与迁移导入复用。

## 参考
- BS-19；`docs/01-rea/discussions.md` §2.3–2.4
- EPIC「讨论（P6-44）」决定第 2、3、10 条
- 旧出处：`LEG/issues/CommentRenderer.java`、`MentionParser`

## 产出
- `wprebuild.app.discussion.CommentRenderer`（或同包等价）：输入 `body` → 输出消毒后的 HTML。
  - CommonMark + Autolink / Strikethrough / Tables / Ins；
  - 仅 `@userId` / `@{name}` 换成 `<span class="wp-comment__user-mention">…</span>`；
  - **必须**经 HTML 消毒，禁止用户原始 HTML 原样输出。
- **本卡允许新增的第三方依赖**（写入 `wp-app` POM，版本锁定并写进执行记录）：
  - `org.commonmark:commonmark` 及 `commonmark-ext-autolink`、`commonmark-ext-gfm-strikethrough`、`commonmark-ext-gfm-tables`、`commonmark-ext-ins`；
  - `com.googlecode.owasp-java-html-sanitizer:owasp-java-html-sanitizer`（或同等并在执行记录写明）。
  不得顺带升级 Spring Boot / OWL API。
- 测试：`CommentRendererTest` 覆盖提及 span、表格等扩展、以及 XSS 样例（如 `<script>`、`javascript:`）被去掉或转义。

## 实现要点
- 无 HTTP、无仓储写入；只提供可注入的渲染组件。
- 域名事件类型不在本卡。

## ECC 技能
工作项类型：`app-service` —— Spring Boot 应用服务、命令处理器与 REST 接口；附加：`security`、`rea`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-82` 查看。

- 开工前加载的 skill：`springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards`、`security-review`、`code-tour`
- 开工时可用的辅助 agent：`spec-miner`
- 提交前调用的审查 agent：`java-reviewer`、`code-reviewer`、`security-reviewer`
- 构建或测试失败时：agent `java-build-resolver`；命令 `/build-fix`
- 注意：spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
./mvnw -q -pl :wp-app -am test -Dsurefire.failIfNoSpecifiedTests=false -Dtest='CommentRendererTest'
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- REST、SSE、迁移、Web
