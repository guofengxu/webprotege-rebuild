---
id: P6-97
title: Web：项目标签设置、实体打标、chips、显示过滤与标签事件穷尽处理
phase: P6
module: wp-web
type: impl
size: M
work_type: web
ecc_extra: []
status: todo
depends_on: [P6-46, P6-95, P6-96]
---

# P6-97 Web 标签 UI

## 目标
Web 端可管理项目标签目录、给实体打显式标签、在层级/Frame 展示 chips，并支持「显示的标签」可见性过滤；`applyProjectEvent` 穷尽处理标签事件。

## 参考
- BS-23、BS-24；旧 `ProjectTagsPresenter`、`EntityTagsSelectorPresenter`、`TagListPresenter`、`TagVisibilityPresenter`
- EPIC「标签（P6-46）」；`@wp/api-types`（P6-94~96）

## 产出
- 项目设置：标签目录编辑（label/颜色/描述；**无**条件编辑器）；保存走整表替换 API。
- 实体打标：checkbox 选择器（from=当前显式，to=勾选）→ update API。
- 层级 / Frame：展示 EntityNode / 实体标签 chips。
- 「显示的标签」过滤：对齐旧 CSS 可见性（可不做服务端过滤）。
- `applyProjectEvent` 增加 `ProjectTagsChanged` / `EntityTagsChanged` 分支并刷新相关查询。
- 测试：权限不足时写入口禁用；打标后 chips 更新；事件到达后列表刷新。

## 实现要点
- 只用 `@wp/api-types`；不手写请求/响应类型。
- 首期不做 MatchingEngine 条件 UI。
- 本卡表面较多：优先做设置目录 + 实体打标 + chips + `applyProjectEvent`；「显示的标签」过滤可最简实现。改动量明显超过 400 行（不含测试）时按 AGENTS.md 第 6 节 `block` 拆卡，勿硬塞。

## ECC 技能
工作项类型：`web` —— Web 端（Vue 3.5 + Vite 8 + Element Plus）。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P6-97` 查看。

- 开工前加载的 skill：`vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow`
- 提交前调用的审查 agent：`vue-reviewer`、`typescript-reviewer`、`a11y-architect`
- 可用命令：`/vue-review`
- 构建或测试失败时：agent `build-error-resolver`；命令 `/build-fix`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认

## 验收
```bash
cd frontend && pnpm --filter wp-web test --run src/features/tags
```

## 完成判据
- [ ] 产出清单中的每一项都已存在
- [ ] 验收命令全部通过
- [ ] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [ ] 执行记录已填写

## 不在本卡范围
- 条件自动打标 UI、移动端、服务端 search-by-tag
