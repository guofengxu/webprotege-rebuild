# M4 Web 端验收

日期：2026-11-13。驱动：Playwright Chromium，`frontend/wp-web/e2e/m4/m4-acceptance.spec.ts`，对 `with-web` 打出的单个 jar（端口 18080）走完界面流程；两个独立浏览器上下文验证实时更新。最近一次完整跑通约 40 秒（含 webServer 启停）。

## 场景：编辑闭环（对照 P3-24 / BackendAcceptanceTest 第一场景）

| 步骤 | 界面操作 | 结果 |
|---|---|---|
| 1 | 管理员 `m4admin` 登录 → 用户管理创建 `m4editor` | 用户出现在列表 |
| 2 | 上传 `testdata/ontologies/warehouse-sample.ttl` | 见到上传进度并进入新项目 |
| 3 | 项目设置「显示语言」写入 `rdfs:label` 的无语言 / `zh` / `en`；「成员」将 `m4editor` 设为 `CAN_EDIT` | 保存成功；成员角色为编辑 |
| 4 | `m4editor` 登录 → 个人设置创建 API Key | 明文只在签发对话框显示一次 |
| 5 | A、B 打开类层级，展开「搬运单元」，看到叶子「托盘」 | 「托盘」无展开箭头 |
| 6 | B 在「托盘」上右键新建子类「周转托盘」 | A 在 10 秒内看到「托盘」变为可展开，展开后有「周转托盘」（不刷新页面） |
| 7 | B 给「周转托盘」加英文标签 `Turnover pallet` 并保存 | A 打开同一实体可见该标签 |
| 8 | B 顶部搜索 `pallet`，点选结果「周转托盘」 | 树中定位并选中 |
| 9 | B 打开修订历史；对最新一条回滚 | 最新两条含 `Edited class 周转托盘` 与 `Created class 周转托盘`；回滚后最新为 `Reverted revision <n>` |
| 10 | B 下载 Turtle；再搜 `pallet` | 文件名以 `.ttl` 结尾；内容含「周转托盘」「托盘」，不含 `Turnover pallet`；搜索不再命中「周转托盘」 |
| 11 | A 把项目移入回收站；B `reload` 工作台 | 项目列表消失、回收站出现；B 显示「项目已在回收站中…」（409 `PROJECT_TRASHED`） |

默认显示名称只索引无语言的 `rdfs:label`。语言标签为 `en` 的英文标签要先写进显示名称设置，搜索才会命中。只加 `en` 会整体替换内置默认，「托盘」会显示为 `Pallet`。

## 运行环境

| 项 | 值 |
|---|---|
| 浏览器 | Playwright Chromium（`playwright install chromium`） |
| Node | v22.23.4 |
| JDK | OpenJDK 25.0.5 |
| PostgreSQL | `postgres:18`（`compose.yaml`），专用库 `wp_m4` |
| 后端 | `backend/wp-app/target/wp-app-*.jar`（`-Pwith-web`） |
| 耗时 | ~40s（含 webServer；用例本体 ~28s） |

## 差距清单（brownfield-retrospective）

对照 M4 界面验收与后端能力，以下差距建议另开补卡（本卡内为跑通验收做了最小产品修复，见下）：

1. **Vue Router 5 相对 redirect 破坏项目入口** — `workspace/routes.ts` 中 `{ path: "", redirect: "class" }` 在 Vue Router 5.4 下会导航到 `/class` 而非 `/projects/:id/class`。上传成功后的 `router.push(/projects/:id)`、项目列表链接均受影响。M4 测试用「列表取 id + `/class` 深链」绕过；应改为具名/函数式 redirect。建议 P4 补卡。
2. **后端没有按名称搜索用户的接口** — 成员页只能输入精确 `userId` 添加成员；管理员无法按显示名查找用户。建议 P3 补卡。
3. **搜索只索引显示名称设置中的语言** — 未把 `en` 写入显示语言时，英文标签 `Turnover pallet` 不会被 `pallet` 命中（与 M3 记录一致，属产品约束，但易踩坑）。
4. **没有「我在本项目中的权限」接口** — 只能看的成员仍会看到编辑控件，最终以 403 为准；前端无法在渲染前按权限隐藏操作。建议 P3 补卡。
5. **事件订阅只在建立连接时校验权限** — 被移出项目的用户在重连前仍可能收到 SSE 事件；移入回收站也不推送事件，故步骤 11 必须 `reload`（项目详情还有约 30 秒缓存）。建议 P3 补卡。

本卡为里程碑验收发现并修掉的阻塞缺陷（不另开卡）：

- **详情区 `el-tabs` 无 `v-model` 时两个 pane 均为 `display:none`** — `EntityDetailPanel` 在注册了「历史」tab 后，未绑定活动 tab，Frame 编辑器挂载但不可见。已用 `v-model="activeTab"`（默认 `detail`）修复。
- **层级实时更新：叶子变父节点后展开箭头仍为 `is-leaf`** — SSE `EntityHierarchyChanged` 需 `refetchType: "all"`，且 `treeNodes` 在原叶子获得 `hasChildren` 时要换成可展开占位；否则步骤 6 的 10 秒内实时展开失败。

## 维护者提醒

- 本测试依赖 Docker（PostgreSQL）；要在 CI 中运行需改 `.github/`（开发标准文件），不在 P4-17 范围。
- 里程碑 PR 合并前按 `milestone` 要求跑一次 `/review-pr`。
