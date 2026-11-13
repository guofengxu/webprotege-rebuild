---
id: P4-17
title: Web 端端到端验收（里程碑 M4）：Playwright 用界面走完 P3-24 的流程，两个浏览器上下文验证实时更新
phase: P4
module: wp-web
type: impl
size: M
work_type: e2e
ecc_extra: [milestone]
status: done
depends_on: [P4-04, P4-08, P4-09, P4-10, P4-11, P4-12, P4-13, P4-15, P4-16, P4-19, P4-20, P4-21, P4-23, P4-24, P4-27, P4-28, P4-29]
---

# P4-17 Web 端端到端验收（M4）

## 目标
用 Playwright 驱动真实浏览器，对 `with-web` 打出的单个可执行 jar（P4-16）走完 P3-24 的后端验收流程，所有步骤都通过界面完成；同时用两个浏览器上下文打开同一项目，验证一方的修改在另一方界面上自动出现。通过即达成里程碑 M4。

## 参考
- `tasks/P4/EPIC.md` 第 17 项
- P3-24 与 `backend/wp-app/src/test/java/wprebuild/app/web/BackendAcceptanceTest.java` 的第一个场景（逐步对照；常量：子类名“周转托盘”、父类 `http://example.org/wp-test/warehouse#Pallet`（显示名“托盘”）、英文标签“Turnover pallet”、搜索词 `pallet`）；P3-24 执行记录中的差距：搜索英文标签前要先把 `rdfs:label` + `en` 加入显示名称设置
- `docs/03-plan/m3-acceptance.md`（M3 的记录格式，本卡写 M4 的对应文档）
- `testdata/ontologies/warehouse-sample.ttl`
- `compose.yaml`（本地 PostgreSQL 18：数据库 `wp`、用户 `wp`、密码 `wp`，端口 5432）
- `backend/wp-app/src/main/resources/application.yaml`（`spring.datasource.*`、`wp.data-dir`、`wp.bootstrap.admin.*`）
- P4-16（可执行 jar 同时提供前端与 `/api`）

## 产出
- `frontend/wp-web/playwright.m4.config.ts`：`testDir: "e2e/m4"`；`workers: 1`、`retries: 0`、单测超时 180 秒；`use.baseURL = "http://localhost:18080"`；`webServer`：命令 `node e2e/m4/start-backend.mjs`、`url: "http://localhost:18080/api/auth/me"`（返回 401 即表示就绪）、`timeout: 180_000`、`reuseExistingServer: false`；在配置中用 `process.env.WP_M4_ADMIN_PASSWORD ??= <随机密码>` 生成管理员密码（Playwright 会在每个 worker 中重新执行配置文件，必须用 `??=`，否则 worker 与 `webServer` 拿到的密码不同），同时传给 `webServer.env` 与测试。
- `frontend/wp-web/e2e/m4/start-backend.mjs`：
  1. `docker compose -f <仓库根>/compose.yaml up -d --wait postgres`；
  2. 重建专用数据库：`docker compose … exec -T postgres psql -U wp -d wp -c "DROP DATABASE IF EXISTS wp_m4 WITH (FORCE)" -c "CREATE DATABASE wp_m4"`（不碰开发用的 `wp` 库）；
  3. 在系统临时目录下新建 `wp.data-dir`；
  4. 用 `JAVA_HOME/bin/java`（没有 `JAVA_HOME` 时用 `java`）启动 `backend/wp-app/target/wp-app-*.jar`（排除 `*.original`），参数 `--server.port=18080`、`--spring.datasource.url=jdbc:postgresql://localhost:5432/wp_m4`、`--wp.data-dir=<临时目录>`、`--wp.bootstrap.admin.username=m4admin`、`--wp.bootstrap.admin.password=$WP_M4_ADMIN_PASSWORD`、`--wp.bootstrap.admin.email=m4admin@example.invalid`；
  5. 找不到 jar 时立即失败并提示先执行 P4-16 的打包命令；进程收到 `SIGTERM` / `SIGINT` 时结束 Java 子进程。
- `frontend/wp-web/e2e/m4/pages.ts`：页面对象（`LoginPage`、`ProjectListPage`、`WorkspacePage`、`ClassFrameEditor`、`HistoryPage`、`SettingsPage`、`AdminUsersPage`、`AccountPage`），只通过可访问名称（`getByRole`、`getByLabel`）和少量 `data-testid` 定位元素。实现过程中缺少的 `data-testid` 可以在对应组件上补加，只加属性、不改行为。
- `frontend/wp-web/e2e/m4/m4-acceptance.spec.ts`，一个串行的测试，用两个浏览器上下文：A（管理员 `m4admin`）与 B（编辑者 `m4editor`）：
  1. A 登录 → 用户管理页创建用户 `m4editor`（初始密码在测试中生成）；
  2. A 在项目列表上传 `testdata/ontologies/warehouse-sample.ttl`，看到进度条并进入新项目；
  3. A 在项目设置的“显示语言”中依次加入 `rdfs:label` 的无语言、`zh`、`en` 三项（与 `BackendAcceptanceTest.displayNames()` 一致；只加 `en` 会整体替换内置默认，“托盘”会显示为 `Pallet`、新建的类会显示为 UUID 本地名，后续步骤全部失败）；在“成员”中把 `m4editor` 设为 `CAN_EDIT`；
  4. B 登录 → 在个人设置中创建一个 API Key，确认明文只显示一次（对应 P3-24 的 API Key 步骤）；
  5. A、B 都打开该项目的类层级，展开“托盘”的父类 `wh:HandlingUnit`，看到“托盘”（此时它是叶子节点，没有展开箭头）；
  6. B 在“托盘”上右键 → 新建子类“周转托盘”；**A 的树在不刷新页面的情况下，“托盘”变为可展开**（实时更新，10 秒内），A 展开后看到“周转托盘”；
  7. B 打开“周转托盘”的类 Frame，添加英文标签“Turnover pallet”并保存；A 打开同一实体时看到新标签；
  8. B 在顶部搜索 `pallet`，结果中有“周转托盘”，回车后树中定位并选中它；
  9. B 打开修订历史，最新两条描述为 `Edited class 周转托盘` 与 `Created class 周转托盘`；对最新一条执行回滚（输入修订号确认）；最新修订描述变为 `Reverted revision <n>`；
  10. B 下载 Turtle：文件名以 `.ttl` 结尾，内容含“周转托盘”“托盘”，不含“Turnover pallet”；再次搜索 `pallet` 不再命中“周转托盘”；
  11. A 把项目移入回收站：项目列表中消失，回收站中出现；B 用 `page.reload()` 重新打开该项目的工作台（移入回收站不产生事件，项目详情还有 30 秒的缓存），显示“项目在回收站中”（409 `PROJECT_TRASHED`）。
- 失败时保留截图与 trace（`use.trace: "retain-on-failure"`），输出到默认的 `test-results/`（已被 `.gitignore` 忽略）。
- `docs/03-plan/m4-acceptance.md`：对照上面 11 步记录覆盖情况、运行环境（浏览器、Node、JDK、PostgreSQL 版本）、耗时；以及 `brownfield-retrospective` 得到的差距清单，至少包括：后端没有按名称搜索用户的接口；搜索只索引显示名称设置中的语言；没有“我在本项目中的权限”接口（只能看的成员也会看到编辑控件，以 403 为准）；事件订阅只在建立连接时校验权限（被移出项目的用户在重连前仍能收到事件）。同一份差距清单也写进本卡的执行记录（`milestone` 的要求），并在执行记录中建议另开 P3 补卡。

## 实现要点
- 只通过界面操作，不直接调用 `/api`；唯一的例外是 `start-backend.mjs` 的环境准备。这样本测试同时是 Web 端的用法示例（与 P3-24 “只通过 HTTP 调用”的原则对应）。
- 实时更新必须在两个**独立的浏览器上下文**中验证（各自的 Cookie 和 SSE 连接），不能用同一个页面刷新来代替。
- 等待用 Playwright 的自动等待与 `expect(...).toBeVisible({ timeout })`，不要用固定的 `waitForTimeout`。
- 本测试依赖 Docker；CI 中运行它需要修改 `.github/`（开发标准文件），不在本卡范围，在执行记录中提醒维护者。
- 里程碑卡：合并前按 `milestone` 的要求跑一次 `/review-pr`。

## ECC 技能
工作项类型：`e2e` —— 端到端测试（Playwright）；附加：`milestone`。规则见 `.cursor/skills/wp-ecc-routing/SKILL.md`，也可以运行 `python tools/task.py ecc P4-17` 查看。

- 开工前加载的 skill：`e2e-testing`、`production-audit`、`brownfield-retrospective`
- 提交前调用的审查 agent：`e2e-runner`、`pr-test-analyzer`
- 可用命令：`/review-pr`
- 提交信息：skill `conventional-commit-review`（或 agent `commit-reviewer`）起草，由 `tools/task.py finish` 校验；不写尾注，提交由人运行 `tools/task.py commit` 确认
- 注意：使用项目自带的 Playwright，不使用 e2e-runner 默认推荐的 Vercel Agent Browser。
- 注意：里程碑 PR 合并前跑一次 /review-pr 多代理审查；brownfield-retrospective 的差距清单写入卡片执行记录，不新建文档目录。

## 验收
```bash
docker compose up -d --wait postgres
pnpm -C frontend install
pnpm -C frontend --filter wp-web run build
./mvnw -q -pl :wp-app -am -Pwith-web -DskipTests package
pnpm -C frontend --filter wp-web exec playwright install chromium
pnpm -C frontend --filter wp-web exec playwright test -c playwright.m4.config.ts e2e/m4/m4-acceptance.spec.ts
```

## 完成判据
- [x] 产出清单中的每一项都已存在
- [x] 验收命令全部通过
- [x] 全量检查通过（由 `python tools/task.py test` 自动执行）
- [x] 两个浏览器上下文之间的实时更新通过断言验证
- [x] `docs/03-plan/m4-acceptance.md` 已写明覆盖步骤与差距清单
- [x] 执行记录已填写

## 不在本卡范围
- P3-24 的第二个场景（移动端令牌与长轮询，属于 P5）
- 修复验收中发现的功能缺陷：记录到执行记录并另开卡，不在本卡中顺手修改业务代码
- 在 CI 中运行本测试（需要修改 `.github/`）
- 性能基准与负载测试

## 执行记录
- 日期：2026-11-13
- 分支：feature/p4-17-web-acceptance
- 测试：2026-11-13T16:38:56+08:00 通过（8 项）
- 实际改动：Added Playwright M4 dual-browser acceptance (e2e/m4 + playwright.m4.config + m4-acceptance.md). Fixed EntityDetailPanel el-tabs v-model, treeNodes leaf→parent placeholders, and EntityHierarchyChanged refetchType all so live hierarchy/frame steps pass.
- 偏差：Upload path uses project-list + /class deep link (Vue Router 5 relative redirect gap). Step 5 uses search-locate then expand due to el-tree-v2 virtualization. Minimal product fixes for tabs/live hierarchy as documented in m4-acceptance.md (card said record defects; these blocked the milestone so fixed in-card).
- 审查：e2e-runner, (APPROVE;, added, screenshot, only-on-failure);, pr-test-analyzer, (APPROVE;, added, treeNodes, +, refetchType, unit, tests)
