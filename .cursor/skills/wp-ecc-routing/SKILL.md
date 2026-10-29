---
name: wp-ecc-routing
description: >-
  webprotege-rebuild 项目中“哪类工作用 ECC 的哪些 skill、agent、命令”的路由规则。
  在开始任何任务卡（python tools/task.py start）、编写或审查代码、提交前审查、
  起草提交信息、发起 PR、编写规划卡，或者用户问到 ECC、skill、agent、/命令 该用哪个时使用。
---

# ECC 路由规则（webprotege-rebuild）

本项目在 Cursor 中使用 ECC（everything-claude-code）提供的 skill、agent 和命令。本 skill 规定**每类工作项必须用哪些 ECC 项**，以及 ECC 的哪些默认做法在本项目中**不适用**。

- 机器可读的映射：`tools/ecc-routing.json`（唯一真源）。本文件中的表格由它生成。
- 查看某张卡的具体清单：`python tools/task.py ecc <卡号>`。卡片中的「ECC 技能」小节由同一份映射生成；两者不一致时，以 `task.py ecc` 的输出为准。
- 映射基于 everything-claude-code v2.2.3（GitHub affaan-m/everything-claude-code，提交 25278e7，2026-10-26）。本地安装的 ECC 版本不同时，运行 `python tools/check_ecc.py` 检查缺失项。

## 1. 每张卡的固定流程与 ECC 的对应关系

| 流程步骤 | 做什么 | ECC |
|---|---|---|
| ①② 开工 | `python tools/task.py start <卡号>` | 不使用 ECC 的 `/plan`（卡片就是计划）；不使用 `git-workflow` 的分支命名 |
| 读卡 | 读卡片、参考文档；需要对照旧工程时阅读 `../webprotege` | 附加 `rea` 的卡：`spec-miner` agent、`code-tour` skill 辅助阅读 |
| ③ 开发 | 先写测试，再实现 | 按工作项类型加载 skill（第 2 节）；`tdd-workflow` 的测试先行做法 |
| ④ 测试 | `python tools/task.py test <卡号>` | 构建失败：`java-build-resolver`（Java）或 `build-error-resolver`（TS / Vue），也可用 `/build-fix` |
| 审查 | 测试通过后调用审查 agent，处理意见后**重新运行 test** | 按工作项类型调用审查 agent（第 2 节）；前端可用 `/vue-review` |
| ⑤ 准备提交 | `python tools/task.py finish <卡号> -m "…" --summary "…" --reviews "…"`，然后**停止**并报告确认单 | 用 `conventional-commit-review` skill 或 `commit-reviewer` agent **起草**提交信息（不执行提交） |
| ⑥ 人工确认 | **人**运行 `python tools/task.py commit <卡号>`，确认后提交并推送 | 不使用 ECC（Agent 不参与） |
| ⑦ PR | 发起 PR，人工评审后合并 | `/pr` 创建 PR；里程碑卡合并前跑 `/review-pr` |

## 2. 工作项类型 → ECC

每张卡的 frontmatter 有 `work_type`（必填）和 `ecc_extra`（可选）。

| work_type | 适用的工作 | 开工前加载的 skill | 提交前调用的审查 agent | 可用命令 | 构建或测试失败时 |
|---|---|---|---|---|---|
| `build` | 工程构建与配置（Maven、POM、Wrapper、质量门禁） | `java-coding-standards` | `code-reviewer` | — | `java-build-resolver`、`/build-fix` |
| `kernel` | 内核领域代码（wp-kernel，纯 Java + OWL API 5） | `java-coding-standards`、`tdd-workflow`、`hexagonal-architecture` | `java-reviewer`、`type-design-analyzer`、`silent-failure-hunter` | — | `java-build-resolver`、`/build-fix` |
| `migrator` | 旧数据迁移工具（wp-legacy-migrator，OWL API 4） | `java-coding-standards`、`tdd-workflow` | `java-reviewer`、`silent-failure-hunter` | — | `java-build-resolver`、`/build-fix` |
| `app-service` | Spring Boot 应用服务、命令处理器与 REST 接口 | `springboot-patterns`、`springboot-tdd`、`api-design`、`java-coding-standards` | `java-reviewer`、`code-reviewer` | — | `java-build-resolver`、`/build-fix` |
| `app-security` | 认证、授权、令牌与其它安全相关接口 | `springboot-security`、`security-review`、`springboot-tdd`、`java-coding-standards` | `security-reviewer`、`java-reviewer` | — | `java-build-resolver`、`/build-fix` |
| `db` | 数据库 schema、Flyway 迁移与 JDBC 仓储 | `postgres-patterns`、`database-migrations`、`springboot-tdd` | `database-reviewer`、`java-reviewer` | — | `java-build-resolver`、`/build-fix` |
| `api-contract` | API 契约（OpenAPI 快照与契约测试） | `contract-first`、`api-design` | `code-reviewer` | — | `java-build-resolver`、`/build-fix` |
| `frontend-workspace` | 前端 pnpm 工作区与共享 API 类型包 | `contract-first` | `typescript-reviewer` | — | `build-error-resolver`、`/build-fix` |
| `web` | Web 端（Vue 3.5 + Vite 8 + Element Plus） | `vue-patterns`、`vite-patterns`、`frontend-design-direction`、`accessibility`、`i18n-sync`、`tdd-workflow` | `vue-reviewer`、`typescript-reviewer`、`a11y-architect` | `/vue-review` | `build-error-resolver`、`/build-fix` |
| `mobile` | 移动端（uni-app Vue3 版） | `vue-patterns`、`i18n-sync`、`tdd-workflow` | `vue-reviewer`、`typescript-reviewer` | `/vue-review` | `build-error-resolver`、`/build-fix` |
| `e2e` | 端到端测试（Playwright） | `e2e-testing` | `e2e-runner`、`pr-test-analyzer` | — | — |
| `benchmark` | 性能基准 | `benchmark` | `performance-optimizer` | — | — |
| `ci-devops` | CI、Docker Compose 与部署配置 | `deployment-patterns`、`docker-patterns`、`github-ops` | `code-reviewer` | — | — |
| `test-data` | 测试语料与测试支持代码 | `java-coding-standards` | `code-reviewer` | — | — |
| `planning` | 规划卡（产出任务卡，不写业务代码） | `blueprint`、`intent-driven-development` | `planner`、`architect` | — | — |

所有 impl 卡还要：用 `conventional-commit-review`（或 `commit-reviewer`）起草提交信息，传给 `finish -m`；人确认并推送后，可按人的要求用 `/pr` 创建 PR。

补充说明：

- `mobile`：ECC 没有 uni-app 专用 skill：以 .cursor/rules/31-uniapp.mdc 为准；vue-patterns 中关于 DOM、Vue Router、Nuxt 的内容不适用。
- `e2e`：使用项目自带的 Playwright，不使用 e2e-runner 默认推荐的 Vercel Agent Browser。
- `benchmark`：performance-optimizer 只用于分析报告，本类卡片不做任何优化（见卡片「实现要点」）。
- `planning`：blueprint 产出的步骤必须转写成 tasks/TEMPLATE.md 的格式，并通过 plan_lint；不要另建 plans/ 目录。

### 附加项（`ecc_extra`）

| ecc_extra | 适用的卡 | 追加 skill | 辅助 agent（读代码时用） | 追加审查 agent | 追加命令 | 说明 |
|---|---|---|---|---|---|---|
| `rea` | 需要对照旧工程行为的卡 | `code-tour` | `spec-miner` | — | — | spec-miner 只用来辅助阅读 ../webprotege；不要生成 openspec/ 目录。行为以 docs/01-rea/behavior-specs.md 为准。 |
| `concurrency` | 涉及锁、并发或写路径不变量的卡 | — | — | `silent-failure-hunter` | — | 审查重点：锁顺序（ADR-006）、WP-1 ~ WP-8（docs/01-rea/write-path.md）。 |
| `security` | 处理上传、外部输入或批量创建用户等敏感操作的卡 | `security-review` | — | `security-reviewer` | — |  |
| `docker` | 涉及 Docker Compose 的卡 | `docker-patterns` | — | — | — |  |
| `milestone` | 里程碑验收卡 | `production-audit`、`brownfield-retrospective` | — | — | `/review-pr` | 里程碑 PR 合并前跑一次 /review-pr 多代理审查；brownfield-retrospective 的差距清单写入卡片执行记录，不新建文档目录。 |

## 3. 与本项目冲突、不要使用的 ECC 做法

ECC 的 skill 是通用建议；与本仓库的 ADR、`.cursor/rules/` 或任务卡冲突时，**一律以本仓库为准**。已知冲突：

| ECC 项 | 原因与处理 |
|---|---|
| /plan | 任务卡就是计划；/plan 会停下来等待确认，打断流程。规划卡可以使用 planner agent。 |
| git-workflow（分支命名、提交与合并策略部分） | 以 git-naming-conventions skill 和 docs/04-dev-workflow.md 为准。Agent 不执行 git commit / push / merge，提交由人运行 tools/task.py commit 确认。 |
| conventional-commit-review、commit-reviewer 中执行提交的步骤 | 只用来起草和审查提交信息，然后传给 tools/task.py finish -m；不执行 git commit，不写 Task / Confirmed-by 尾注。 |
| architecture-decision-records | 其默认目录是 docs/adr/；本项目 ADR 在 docs/02-architecture/adr/，并且 Agent 不得新建或修改已接受的 ADR。 |
| jpa-patterns、mybatis-patterns、prisma-patterns | 本项目不使用 ORM（ADR-002，使用 JdbcClient）。 |
| frontend-patterns、react-patterns、react-testing、react-performance、frontend-a11y、nextjs-turbopack、nuxt4-patterns | 这些面向 React / Next.js / Nuxt；本项目前端是 Vue + uni-app。无障碍用 accessibility skill。 |
| springboot-verification、verification-loop 中的 SpotBugs / PMD / Checkstyle / OWASP 步骤 | 本项目没有配置这些插件，不要为了跑通这些步骤而新增依赖；验收以 tools/task.py test 为准。 |
| tdd-workflow、tdd-guide 的 80% 覆盖率要求 | 作为参考，不作为门禁；门禁是卡片的验收命令。 |
| security-scan | 它扫描 .claude/ 配置，与本项目代码无关。 |

另外注意 Cursor hook：
- 本项目自带提交闸门 `.cursor/hooks/commit_gate.py`，必须是 `.cursor/hooks.json` 中 `beforeShellExecution` 的**第一个**钩子（ECC 的调度脚本注释写明 Cursor 可能只执行同一事件的第一个钩子）。ECC 安装会覆盖 `hooks.json`，装完运行 `python tools/check_ecc.py --fix-hooks`。
- `pre:bash:block-no-verify` 会拦截 `git commit --no-verify` 等跳过钩子的命令——本项目本来就不允许这样做；ECC 的 “git push review” 提醒在本项目中不会触发，因为 Agent 不推送。
- 开发服务器拦截（要求在 tmux 中运行 `npm run dev` 一类命令）在 Windows 上不生效，无需处理。
- ECC 的记忆数据默认写到 `~/.cursor/ecc`，与 Claude Code 的 `~/.claude` 分开，不要改成共享。

## 4. 安装与检查

1. 获取 ECC（建议固定在本映射所依据的版本或之后的版本）：`git clone https://github.com/affaan-m/everything-claude-code.git`。
2. 运行 `python tools/check_ecc.py --print-install`，按输出的命令在**本仓库根目录**下安装：先装核心模块（规则、agent、命令、hook），再按清单安装需要的 skill。每条命令先加 `--dry-run` 确认写入位置是本仓库的 `.cursor/`。
3. 运行 `python tools/check_ecc.py --fix-hooks`，把提交闸门放回 `.cursor/hooks.json` 第一位；退出码为 0 表示 ECC 齐全且闸门已启用。重启 Cursor 使钩子生效。
4. ECC 安装进本仓库的 `.cursor/` 后**提交进 Git**（在人手工建的 `chore/` 分支上，经 PR 合并），同事克隆即用同一版本，不需要各自安装；版本记在 `tools/ecc-routing.json` 的 `eccBaseline`。详见 `CONTRIBUTING.md` 第 5 节。
5. ECC 会在 `.cursor/rules/` 中写入 `common-*.mdc` 等规则文件。它们与本项目的 `00-` ~ `31-` 规则冲突时，以本项目规则为准；不要删除或修改本项目的规则来迁就 ECC。

## 5. 维护

- 新增工作项类型或调整映射：只改 `tools/ecc-routing.json`，然后运行 `python tools/plan_lint.py --check`（它会校验每张卡的 `work_type` / `ecc_extra` 是否存在于映射中）和 `python tools/check_ecc.py`。
- 规划卡产出的新卡，「ECC 技能」小节照抄 `python tools/task.py ecc <新卡号>` 的输出。
