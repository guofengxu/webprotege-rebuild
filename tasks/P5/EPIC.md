# P5 移动端 MVP（uni-app）：卡片清单

> **已暂停（2026-11-13，人的决定，记录于 P6-00）**：移动端暂时停止开发，`P5-00` 的状态为 `blocked`，`python tools/task.py next` 不再列出它。下表保留，作为恢复时的依据；恢复方式见 `P5-00-mobile-planning.md` 的「阻塞」小节。P6 不依赖 P5。

M3 达成且 P3-27 完成后，由 `P5-00` 把下列每一项展开为任务卡。可以与 P4 并行。技术栈见 ADR-014；平台限制见 `.cursor/rules/31-uniapp.mdc`；ECC 使用规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`（`mobile` / `e2e` 类）。

首期目标平台：H5 与 Android App。功能以查阅和跟踪为主（ADR-012）。

| # | 卡片 | 验收要点 |
|---|---|---|
| 1 | 工程骨架 | `frontend/wp-mobile/`：uni-app Vue3 版 CLI 工程 + TypeScript 5.9 + wot-design-uni + Vitest；`build:h5`、`build:app` 能执行；按 P3-27 的验证结果作为工作区成员或单独安装（ADR-014 风险项的“只给 wp-mobile 设 hoisted”不可行，见 overview 第 1 节）；`@wp/api-types` 用 `link:../packages/api-types` 引用 |
| 2 | 网络层与令牌 | 封装 `uni.request`，类型来自 `@wp/api-types`；登录换取访问令牌；令牌过期时自动用刷新令牌续期，失败则回到登录页；处理 `REFRESH_TOKEN_REUSED` |
| 3 | 登录与项目列表 | 登录页、项目列表、下拉刷新 |
| 4 | 层级浏览 | 逐级进入的层级列表（不做树形展开），显示名称，面包屑返回 |
| 5 | 实体详情 | 只读展示类 / 属性 / 个体的 Frame |
| 6 | 轻量编辑 | 只允许修改标签与注解；冲突（409 `FRAME_CHANGED`）时提示并重新加载 |
| 7 | 搜索 | 搜索页，结果按实体类型分组 |
| 8 | 修订历史与变更提醒 | 项目修订列表；在前台时用长轮询接收事件，并在相关页面显示“有更新”提示 |
| 9 | 我的设备 | 查看并吊销自己的设备会话 |
| 10 | 端到端验收（M5） | H5 端的 Playwright 用例覆盖登录 → 浏览 → 搜索 → 查看历史；Android 安装包在真机或模拟器上完成一次人工冒烟，结果写入执行记录 |
