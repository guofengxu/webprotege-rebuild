# P4 Web 端 MVP（Vue 3.5）：卡片清单

M3 达成且 P3-27 完成后，由 `P4-00` 把下列每一项展开为任务卡。每项都给出了验收要点，展开时不能弱化。技术栈见 ADR-002、ADR-008；ECC 使用规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`（`web` / `e2e` 类）。

| # | 卡片 | 验收要点 |
|---|---|---|
| 1 | 工程骨架 | `frontend/wp-web/`：Vue 3.5 + Vite 8 + TypeScript（strict）+ ESLint + Vitest + Playwright；依赖 `@wp/api-types`；`verify` 脚本（vue-tsc、lint、vitest、build）全部通过；`vite.config.ts` 把 `/api` 代理到 `http://localhost:8080`，会话 Cookie 与 CSRF Cookie 保持同源；命令从仓库根目录写成 `pnpm -C frontend --filter wp-web …` |
| 2 | API 客户端与认证 | 基于 `openapi-fetch` 的客户端；读取 CSRF Cookie；登录页；未登录跳转登录页；401 统一处理；TanStack Vue Query 的全局配置 |
| 3 | 布局、主题与国际化 | Element Plus 主题、设计令牌（由 `design-system` skill 产出）、`vue-i18n`（zh-CN / en-US）、顶部项目栏 |
| 4 | 项目列表 | 列表、新建空项目、上传导入（进度条、错误展示）、回收站 |
| 5 | 项目工作台 | 左侧层级树、右侧详情面板；URL 中保存当前项目、层级类型和选中实体，刷新后恢复 |
| 6 | 类层级树 | `el-tree-v2` 虚拟滚动（1 万节点流畅）、懒加载子节点、显示名称、已弃用实体的样式、通过 `paths` 接口定位选中实体 |
| 7 | 属性层级树 | 三种属性层级，复用第 6 项的树组件 |
| 8 | 个体列表 | 按选中类分页列出个体 |
| 9 | 类 Frame 编辑器 | 注解（多语言）、父类、三种属性值；`otherAxioms` 只读显示；提交时带 `from`，收到 409 `FRAME_CHANGED` 时提示并允许刷新 |
| 10 | 属性与个体 Frame 编辑器 | 对应 P3-17、P3-18 |
| 11 | 新建 / 删除实体 | 在树上右键或工具栏操作；一次输入多个名称；删除前确认 |
| 12 | 搜索 | 顶部搜索框，结果按实体类型分组，回车跳转并在树中定位 |
| 13 | 修订历史 | 项目级历史列表、单个修订的差异、实体历史、回滚（需二次确认） |
| 14 | 实时事件 | 订阅 SSE；根据事件让 Vue Query 缓存失效；收到 `resync` 时重新加载数据；断线自动重连并带 `Last-Event-ID` |
| 15 | 项目设置与共享 | 前缀、新实体设置、显示语言、成员管理 |
| 16 | 单包打包（module `wp-app`，work_type `build`） | `backend/wp-app/pom.xml` 增加默认关闭的 profile `with-web`：用 `maven-resources-plugin` 把 `${maven.multiModuleProjectDirectory}/frontend/wp-web/dist` 复制到 `target/classes/static`。`copy-resources` 遇到不存在的目录只会跳过，所以在 profile 内用 `maven-enforcer-plugin`（P0-03 已引入）的 `requireFilesExist` 检查 `frontend/wp-web/dist/index.html`，绑定在 `process-resources` 之前，缺失时失败并提示先运行 `pnpm -C frontend --filter wp-web run build`。不引入 `frontend-maven-plugin`，Maven 不调用 Node，默认 `./mvnw verify` 不需要前端工具链。SPA 路由回退到 `index.html`，`/api/**` 不回退。验收：先构建 wp-web，再 `./mvnw -q -pl :wp-app -am -Pwith-web package`，断言 `backend/wp-app/target/classes/static/index.html` 存在、可执行 jar 中有 `BOOT-INF/classes/static/index.html` |
| 17 | 端到端验收（M4） | Playwright 用界面操作走完 P3-24 的流程；两个浏览器上下文同时打开同一项目，验证实时更新 |

## 展开结果（P4-00）

第 1 ~ 17 项依次对应 P4-01 ~ P4-17，编号与上表的序号相同。另有 12 张卡排在 P4-18 之后，原因如下：

| 卡片 | 来源 | 原因 |
|---|---|---|
| P4-18 补齐 OpenAPI 成功响应的 schema | 前置条件 | P3-23 的快照中，层级、Frame（GET）、搜索、修订、个体、设置、事件的成功响应是无类型的 `object`；`ValueNode` 的判别值生成成了 `"IriValue"` / `"LiteralValue"`（实际 JSON 是 `"iri"` / `"literal"`），事件的 `type` 无法收窄。前端只能手写类型或强转，违反 ADR-008。本卡只补契约描述，不改接口行为。P4-06、P4-13、P4-14、P4-15、P4-22 依赖它 |
| P4-19 下载导出菜单 | `docs/03-plan/mvp-scope.md` | P3-12 已提供接口，MVP 范围中 Web 端有“下载菜单”，上表没有列出；P4-17 的验收流程要用到 |
| P4-20 个人设置（API Key、设备） | `docs/03-plan/mvp-scope.md` | P3-05、P3-26 已提供接口，MVP 范围中 Web 端有“个人设置” |
| P4-21 用户管理页（列表、创建） | `docs/03-plan/mvp-scope.md` | P3-25 已提供接口，MVP 范围中 Web 端有“管理页”；P4-17 用它创建第二个用户 |
| P4-22 Frame 编辑机制 | 第 9、10 项拆出 | 编辑草稿、`from` / 409 / 403 处理、校验约定、未保存提醒、标题与其它公理分区由四种编辑器共用 |
| P4-23 个体 Frame 编辑器 | 第 10 项拆出 | 第 10 项包含三种属性和个体共四个编辑器，个体部分单独成卡 |
| P4-24 成员管理 | 第 15 项拆出 | 第 15 项包含四类设置和成员管理，成员管理单独成卡 |
| P4-25 登录页与路由守卫 | 第 2 项拆出 | 第 2 项包含客户端基础设施与认证界面，合在一起超过 400 行；P4-02 保留客户端、错误处理、查询键与测试支持，本卡负责登录页、路由守卫（含 `meta.requiresSystemAdmin`）和 401 跳转 |
| P4-26 Frame 输入组件 | 第 9、10 项拆出（由 P4-22 再拆） | 实体选择器（含新项目里搜不到的内置数据类型与注解属性）、实体引用列表、字面量输入、注解编辑器；P4-22 合在一起估计超过 600 行 |
| P4-27 新实体设置与显示语言 | 第 15 项拆出（由 P4-15 再拆） | P4-15 合在一起估计约 600 行；显示名称设置为空时使用内置默认、非空时整体替换，界面要说明 |
| P4-28 修改应用角色、重置密码 | 由 P4-21 拆出 | P4-21 合在一起估计约 500 行 |
| P4-29 实时事件的连接与重连 | 第 14 项拆出（由 P4-14 再拆） | P4-14 保留解析与缓存失效规则（纯函数），本卡负责连接、`Last-Event-ID`、`resync` 后的游标、权限变化后重连、连接状态与接入 `ProjectShell` |

依赖关系（`A → B` 表示 B 依赖 A）：P4-01 → P4-02 → P4-25 → P4-03 → {P4-04、P4-05、P4-20、P4-21}；P4-21 → P4-28；P4-05 → P4-19；P4-05 + P4-18 → {P4-06、P4-13、P4-15、P4-22}；P4-02 + P4-18 → P4-14；P4-05 + P4-14 → P4-29；P4-06 → {P4-07、P4-08、P4-12}；P4-07 + P4-08 → P4-11；P4-22 → P4-26；P4-22 + P4-26 → {P4-09、P4-10、P4-23}；P4-15 → P4-24；P4-15 + P4-26 → P4-27；P4-01 → P4-16；P4-17 依赖其余全部功能卡。P4-18 与 P4-01 ~ P4-05 互不依赖，可以并行，但应尽早完成：它会收紧生成类型，验收中会在 `wp-web` 已存在时运行其 `verify`。

并行卡之间不改同一个共享文件：路由与文案按目录自动收集（P4-01），工作台的层级面板、层级工具栏、详情编辑器、详情标签页、工具栏和修订操作通过注册表扩展（P4-05，注册的组件收到哪些 props 也在 P4-05 中规定），设置页的标签页通过 `*.tab.ts` 注册（P4-15）；功能卡原则上只新增自己目录下的 `routes.ts`、`locales/`、`register.ts`、`*.tab.ts`。P4-06 之后仍需修改其它卡文件的情况如下，每一处都排在被改文件的所有者之后，且没有可并行的卡改同一个文件：P4-07 改 `hierarchy/register.ts`（及必要时 `HierarchyTree.vue`）；P4-11 给 `HierarchyTree.vue`、`IndividualList.vue` 和三个层级面板加右键事件与菜单；P4-12 在 `AppLayout.vue` 中放入搜索框；P4-28 改 `AdminUsersPage.vue` 与 `admin/locales/`；P4-29 在 `ProjectShell.vue` 中挂事件订阅，并给 `ProjectShell.spec.ts`（必要时 `WorkspacePage.spec.ts`）补事件接口的桩；P4-17 只给组件补 `data-testid`。新增这类修改时要重新核对这张清单。查询键全部在 P4-02 中定义，失效一律用其中的前缀键。

后端缺口（P4 不修，记入 P4-17 的差距清单，建议另开 P3 补卡）：没有“我在本项目中的权限”接口，编辑控件对只能看的成员也显示，以 403 为准；事件订阅只在建立连接时校验权限；没有按名称搜索用户的接口。
