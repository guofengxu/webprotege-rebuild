# 参与开发（同事必读）

> **当前阶段由一人维护。** 本文第 1、2、5、6 节适用于现在；涉及同事评审、认领与 `CODEOWNERS` 的内容（第 3、4 节与多人分支保护）待项目具备多人维护条件后重新规划，届时以更新后的本文为准。

本仓库所有人用同一套标准开发：同一份任务卡、同一个流程脚本、同一组 Cursor 规则与 skill、同一版本的 ECC。这些标准本身也在仓库里做版本管理，**改标准和改代码一样走 PR**。

## 1. 第一次参与（每人一次，约 15 分钟）

1. 安装：Git for Windows（自带 Git Bash）、Python 3.9+（安装时勾选 “Add to PATH”，保证终端里 `python` 可用）、Cursor；可选 GitHub CLI `gh`。按卡片阶段再装 JDK 25（P0 起）、Docker Desktop（P0-04 起）、Node.js 24 与 pnpm（P3-27 起）。
2. 设置提交身份（会写入每个提交的 `Confirmed-by` 尾注，请用真实姓名和公司邮箱）：
   ```bash
   git config --global user.name "张三"
   git config --global user.email "zhangsan@example.com"
   git config --global core.autocrlf false
   ```
3. 克隆仓库，并把旧工程放在同级目录（任务卡里的 `../webprotege/...` 路径依赖这个位置）：
   ```bash
   cd D:\00.workspace\00.ProjectCode
   git clone https://github.com/guofengxu/webprotege-rebuild.git
   git clone https://github.com/guofengxu/webprotege.git      # 只读参照，不要修改
   cd webprotege-rebuild
   ```
4. 检查并配置本机环境：
   ```bash
   python tools/repo_setup.py dev
   ```
   它会启用仓库自带的 Git 钩子（`core.hooksPath=.githooks`），并检查工具链、提交身份、Cursor 提交闸门与 ECC。有“缺失”时按提示处理后重新运行。
5. 用 Cursor 打开 `webprotege-rebuild`（建议多根工作区，同时打开只读的 `webprotege`）。**重启一次 Cursor**，让 `.cursor/hooks.json` 生效。
6. 读 `docs/04-dev-workflow.md`（流程）和 `.cursor/skills/git-naming-conventions/SKILL.md` 第 0、4 节（提交前人工确认）。

## 2. 日常开发

```bash
python tools/task.py next                 # 看哪些卡可以开工、哪些已被同事认领
```

然后在 Cursor 里让 Agent 执行，例如：“按 AGENTS.md 开始 P1-03”。Agent 会依次运行 `start`、开发、`test`、审查、`finish`，然后停下来报告确认单。你：

```bash
python tools/task.py commit P1-03 --pr    # 在你自己的终端里：核对确认单，输入卡号后提交、推送、创建 PR
python tools/task.py reject P1-03 --reason "…"   # 不同意时
```

之后在 GitHub 上请一位同事评审 PR，CI 与评审通过后合并。

规则要点：

- **一人一卡。** `task.py start` 会在 GitHub 上新建本卡的分支，分支存在即表示已被认领；同事再 `start` 同一张卡会被拒绝。`next` 会列出已被认领的卡。
- **谁确认，谁负责。** `Confirmed-by` 记录的是你，提交前请按确认单的清单逐项看过。
- **你自己的终端不受 Cursor 钩子约束**，但仍有 Git 钩子与 CI：任务分支上没有 `Task` / `Confirmed-by` 尾注的提交推不上去，也合不进 `main`。不要用 `--no-verify` 绕过。
- 中途要停，Agent 会运行 `block`；同样由你 `commit` 后推送，并在团队里说明阻塞原因。
- 换电脑继续：`git fetch`，然后 `python tools/task.py start <卡号> --resume`。注意人工确认之前的改动只在本地。

## 3. 评审同事的 PR

- 看 PR 正文与 `.github/pull_request_template.md` 中的“评审人检查”。
- 评论用 Conventional Comments（`issue (blocking): …`、`suggestion: …`），规范见 `git-naming-conventions` skill 第 7 节。
- 有未解决的 `issue` / `todo` 时不要批准。
- 业务代码任意一位同事批准即可；改动 `.github/CODEOWNERS` 中列出的标准文件时，需要维护者批准。

## 4. 修改开发标准

以下文件构成“开发标准”，由维护者负责（见 `.github/CODEOWNERS`）：

| 内容 | 文件 |
|---|---|
| Agent 执行协议 | `AGENTS.md` |
| Cursor 规则、skill、钩子 | `.cursor/rules/`、`.cursor/skills/`、`.cursor/hooks*` |
| 流程脚本与 ECC 映射 | `tools/`（`task.py`、`plan_lint.py`、`ecc-routing.json`、`check_ecc.py`、`repo_setup.py`） |
| Git 钩子、CI、分支保护、PR 模板 | `.githooks/`、`.github/` |
| 卡片模板、规划原则、ADR、开发流程 | `tasks/TEMPLATE.md`、`docs/00-planning-principles.md`、`docs/02-architecture/adr/`、`docs/04-dev-workflow.md` |

修改方式：人手工建分支（不是任务卡分支，例如 `docs/clarify-review-checklist`、`chore/bump-ecc-2-3-0`），提交信息照常用 Conventional Commits，发 PR 由维护者评审。合并后通知大家 `git pull`；涉及 `.cursor/hooks.json` 或 ECC 的改动还要重启 Cursor。Agent 不得修改这些文件（`finish` 的确认单会把它们标为风险）。

## 5. ECC 的版本

ECC 由维护者安装到本仓库的 `.cursor/` 并**提交进仓库**，所有人克隆后用的就是同一版本，不需要各自安装（版本记录在 `tools/ecc-routing.json` 的 `eccBaseline`）。维护者安装或升级 ECC 的步骤：

1. 新建分支，例如 `chore/vendor-ecc-2-2-3`。
2. 按 `.cursor/skills/wp-ecc-routing/SKILL.md` 第 4 节安装。
3. 运行 `python tools/check_ecc.py --fix-hooks`（ECC 安装会覆盖 `.cursor/hooks.json`，这一步把提交闸门放回第一位），再运行 `python tools/check_ecc.py`，退出码应为 0。
4. 更新 `eccBaseline`，提交并发 PR。

如果个人在 `~/.cursor/` 下另装了 ECC，与仓库版本冲突时以仓库版本为准；`check_ecc.py` 两处都会查找。

## 6. 维护者：首次建仓（只做一次）

```bash
python tools/repo_setup.py init --gh-create guofengxu/webprotege-rebuild    # 默认私有；公开仓库加 --public
python tools/repo_setup.py protect                                         # 单人维护配置；多人协作时加 --team
```

`init` 会删除规划改版留下的空壳文件、从 `tools/repo-templates/github/` 生成 `.github/`（PR 模板、CODEOWNERS、分支保护配置）、建立 Git 仓库、提交基线、在 GitHub 上建仓并推送 `main`、启用 Git 钩子。然后在 GitHub 仓库 Settings → Collaborators（或组织的团队）中邀请同事。

注意：GitHub 免费账户的**私有**仓库不支持分支保护，`protect` 会失败。要在私有仓库上强制“只能经 PR 合并、必须评审”，需要把仓库放进 Team 计划的组织或升级账户；否则这一层只能靠约定。
