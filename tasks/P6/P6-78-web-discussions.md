---
id: P6-78
title: Web 端：实体讨论面板与讨论事件的 applyProjectEvent 处理
phase: P6
module: wp-web
type: impl
size: M
work_type: web
ecc_extra: []
status: todo
depends_on: [P6-77]
---

# P6-78 Web 讨论面板

## 目标
工作台可为当前实体展示讨论线程并完成首期读写操作；`applyProjectEvent` / `parseProjectEvent` 穷尽处理 BS-20 全部讨论事件类型。

## 参考
- BS-19、BS-20；EPIC「讨论（P6-44）」决定第 1、5 条
- 旧客户端：`DiscussionThreadListPresenter`、`CommentedEntitiesPresenter`
- `@wp/api-types`（P6-77）；现有 `applyProjectEvent.ts`、`projectEvents.ts`、工作台注册表

## 产出
- 功能目录（如 `features/discussions/`）：按实体加载线程、新建、回复、编辑/删除自己的评论、开关状态；展示服务端 `renderedBody`（消毒后 HTML）；i18n zh-CN / en-US。
- **有评论实体列表**（最小可用：可打开列表并跳到实体；不必复刻旧 portlet 全部排序选项）。
- 将实体讨论面板挂到实体详情区域（注册表或现有壳，不改其它功能目录的业务逻辑）。
- 更新 `applyProjectEvent` / 相关 spec：讨论事件使讨论与列表 query 失效；保持 `never` 穷尽检查通过。
- `queryKeys` 增加讨论相关键（仅本卡改）。
- 组件与事件单测（Vitest）。

## 实现要点
- 类型一律来自 `@wp/api-types`，不手写响应类型。
- 不要用客户端 Markdown 引擎渲染他人评论正文。
- 可访问性：表单标签、按钮名称、状态变化可感知。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-78` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm -C frontend --filter wp-web run verify
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 邮件、移动端
