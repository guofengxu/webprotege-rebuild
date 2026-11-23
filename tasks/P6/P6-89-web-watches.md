---
id: P6-89
title: Web：实体关注控件、我的关注列表与 Watch 事件穷尽处理
phase: P6
module: wp-web
type: impl
size: M
work_type: web
ecc_extra: []
status: todo
depends_on: [P6-45, P6-87]
---

# P6-89 Web 关注界面

## 目标
用户可在实体上下文设置无/实体/分支关注，查看自己的关注列表，并在收到 `WatchAdded`/`WatchRemoved` 时刷新相关 UI。

## 参考
- BS-21；旧 `WatchPresenter` / `WatchedEntitiesPortletPresenter`（行为参照）
- EPIC「关注（P6-45）」；`@wp/api-types` 已含关注 API 与事件

## 产出
- 入口钉死：实体上下文（层级/实体页）三态关注控件 + 「我的关注」列表（实体显示名 + 类型）；调用 P6-86 API；无 `WATCH_CHANGES` 时隐藏或禁用（权限用现有权限接口，不手写动作联合类型）。
- `applyProjectEvent`（及类型联合）穷尽处理新事件；相关 `queryKeys` 失效刷新。
- i18n 文案；基础组件测或页面测覆盖主路径。

## 实现要点
- 不手写 API 类型；只用 `@wp/api-types`。
- 不做关注变更历史全表扫描 UI。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-89` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
pnpm --dir frontend/wp-web test -- --run
pnpm --dir frontend/wp-web exec vue-tsc --noEmit
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 邮件预览、站内通知中心、移动端
