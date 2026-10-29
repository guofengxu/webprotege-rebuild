---
name: git-naming-conventions
description: >-
  webprotege-rebuild 的分支命名、Conventional Commits 提交信息、提交前人工确认、PR 与
  GitHub 评论（Conventional Comments）规范。在创建分支、起草或校验提交信息、准备提交与推送、
  发起 PR、撰写 Issue/PR/Review 评论时使用；用户提到 分支命名、提交备注、commit message、
  提交、推送、push、人工确认、PR title、review comment、评论规范 时也使用。
---

# Git 命名、提交确认与评论规范

本 skill 是 webprotege-rebuild 中**分支、提交、PR、评论**的唯一规范。完整开发流程见 `docs/04-dev-workflow.md`，常驻摘要见 `.cursor/rules/05-git-branch-commit.mdc`，示例见 [reference.md](reference.md)。所有规则由 [scripts/validate_names.py](scripts/validate_names.py) 实现，`tools/task.py`、Git 钩子与 CI 都调用它。

## 0. 三条铁律

1. **分支只由 `python tools/task.py start <卡号>` 创建**。Agent 不手工 `git checkout -b`、不改分支名。
2. **Agent 永远不提交、不推送。** Agent 做到 `python tools/task.py finish` 为止：它暂存改动、生成《提交前确认单》，然后 Agent **停下**。提交和推送只由人在自己的终端运行 `python tools/task.py commit <卡号>` 完成。
3. **提交信息由 Agent 起草、由脚本校验、由人确认。** 尾注 `Task:` 与 `Confirmed-by:` 只能由 `task.py commit` 添加，Agent 不写。

## 1. 谁做什么

| 流程步骤 | 执行者 | 命令 |
|---|---|---|
| ①② 从主分支建分支并拉取 | Agent | `python tools/task.py start P1-03` |
| ③ 开发 | Agent | 按卡片与「ECC 技能」 |
| ④ 本地测试 | Agent | `python tools/task.py test P1-03` |
| ④ 审查 | Agent | 卡片「ECC 技能」列出的审查 agent；审查后有改动必须重新 `test` |
| ⑤ 更新卡片状态、暂存、生成确认单 | Agent | `python tools/task.py finish P1-03 -m "…" --summary "…" --reviews "…"` |
| **⑥ 人工确认：提交并推送** | **人** | `python tools/task.py commit P1-03`（不同意：`reject P1-03 --reason "…"`） |
| ⑦ PR、评审、合并到 `main` | 人（Agent 可按人的要求起草或创建 PR） | `commit --pr`、`gh pr create` 或 `/pr`；合并只由人做 |

## 2. 分支命名

### 任务卡分支（本项目的全部日常分支）

格式：`<type>/<卡号小写>-<卡片文件名中的短描述>`，例如 `feature/p1-03-change-minimiser`。由 `task.py start` 生成，正则：

```
^(feature|fix|test|refactor|docs|chore)/(p[0-9]-[0-9]{2})-[a-z0-9]+(-[a-z0-9]+)*$
```

| 卡片 | 分支 type |
|---|---|
| `type: planning` | `docs` |
| `work_type: ci-devops` | `chore` |
| 其余 | `feature` |
| frontmatter 写了 `branch_type: fix` 等 | 以 `branch_type` 为准（只在规划卡中由人指定） |

短描述来自卡片文件名（kebab-case），所以**改卡片文件名就是改分支名**，开工后不要改。

### 其它分支（只由人创建）

格式：`<type>/<可选工单>-<短描述>`，type 取 `feature | fix | hotfix | test | refactor | docs | chore | release`，全小写 kebab-case，例如 `hotfix/session-leak`、`release/0-1-0`。不要编造工单号。

## 3. 提交信息

```
<type>(<scope>): <subject>

<body：为什么这样改、关键取舍；可省略>

Task: P1-03                                   ← task.py commit 自动添加
Confirmed-by: 张三 <zhangsan@example.com>      ← task.py commit 自动添加（取 git config）
```

**type**：`feat | fix | docs | style | refactor | perf | test | chore | ci | build | revert`；破坏性变更在 type 后加 `!` 并在 body 写 `BREAKING CHANGE: …`。

| 分支 type | 常用提交 type |
|---|---|
| `feature/` | `feat`（只有测试时 `test`，只有构建配置时 `build`） |
| `fix/` | `fix` |
| `docs/` | `docs` |
| `chore/` | `ci`、`build`、`chore` |
| `refactor/` | `refactor` |

**scope**：取卡片的模块，用下表的名字；跨模块时取主要模块或省略。表外的 scope 只给警告。

| scope | 范围 | scope | 范围 |
|---|---|---|---|
| `kernel` | wp-kernel | `web` | wp-web（Vue）或 wp-app 的 web 子包 |
| `app` | wp-app 整体 | `mobile` | wp-mobile（uni-app） |
| `access` `bus` `project` `revision` `ontology` `search` `events` `migration` `shared` | wp-app 各子模块 | `api-types` | packages/api-types |
| `migrator` | wp-legacy-migrator | `testdata` | testdata/ |
| `build` `deps` | Maven / pnpm 构建与依赖 | `ci` | GitHub Actions |
| `tasks` | 任务卡（含阻塞） | `docs` `tools` | 文档、流程脚本 |

**subject**：英文、祈使语气、小写字母开头、不加句号、整行 ≤ 72 字符；写“做了什么”，body 写“为什么”。禁止 `update`、`fix`、`wip`、`misc`、`temp` 这类空主题，禁止在 body 里罗列文件名。

**一张卡一个提交。** Agent 不能中途提交；`finish` 把全部改动作为一个提交交给人确认。阻塞时 `block` 生成的提交固定为 `docs(tasks): mark p1-03 as blocked`（同样需要人确认）。

**起草方法**：用 `conventional-commit-review` skill（或 `commit-reviewer` agent）按 `git diff` 起草，再先校验草稿：

```bash
python .cursor/skills/git-naming-conventions/scripts/validate_names.py --message "feat(kernel): port change list minimiser"
```

## 4. 提交前人工确认

### Agent 的步骤

1. `test` 通过，审查 agent 已运行且问题已处理（处理后重新 `test`）。
2. 运行 `finish`。`-m` 只写 header 和可选 body，**不写尾注**；`--reviews` 写实际运行过的审查 agent：
   ```bash
   python tools/task.py finish P1-03 -m "feat(kernel): port change list minimiser" \
     --summary "移植 ChangeListMinimiser 及 BS-01 测试" --reviews "java-reviewer"
   ```
   `finish` 会：核对测试记录与代码指纹 → 校验提交信息 → 勾选自动判据、追加执行记录、状态改为 `done` → `git add -A` → 记录暂存树 → 写 `.task-runs/P1-03.pending.json` 与确认单 `.task-runs/P1-03.review.md`。
3. 读确认单，在对话里按下面的模板报告，然后**停止，不再执行任何命令**：
   ```markdown
   P1-03 已准备好提交，等待你确认。
   - 提交信息：`feat(kernel): port change list minimiser`
   - 改动：N 个文件，+A / -D（明细见 `.task-runs/P1-03.review.md`）
   - 测试：验收 X 条、全量检查 Y 条全部通过
   - 审查：java-reviewer，已处理 K 条意见
   - 脚本提示的风险：<逐条照抄确认单里的 ⚠，没有就写“无”>
   请在你自己的终端运行 `python tools/task.py commit P1-03`；不同意请运行 `python tools/task.py reject P1-03 --reason "…"` 或直接告诉我原因。
   ```
4. 人在对话里提出修改意见时：先运行 `python tools/task.py reject P1-03 --reason "<人的原话>"`，再修改 → `test` → `finish`，重新报告。人已运行 `reject` 时，读 `.task-runs/P1-03.feedback.md` 后照此处理。

`finish` 之后**任何**文件改动都会让确认失效（`commit` 会比对暂存树并检查未暂存、未跟踪的文件），必须重新 `test` 与 `finish`。重复 `finish` 会先自动撤回上一次准备。

### 人的步骤

1. 打开 `.task-runs/<卡号>.review.md`，必要时 `git diff --cached`。
2. 在自己的终端运行 `python tools/task.py commit <卡号>`：它显示确认单，输入 `d` 看完整改动，输入卡号才会提交并推送（加 `--pr` 同时用 `gh` 创建 PR，加 `--no-push` 只提交）；输入 `n` 写原因即拒绝。
3. 以“人已”开头的完成判据（例如规划卡的“人已评审新卡片”）在你确认时自动勾选。

人工核对清单（确认单中同样列出）：

- [ ] 改动只涉及卡片「产出」，没有「不在本卡范围」里的内容
- [ ] 测试确实覆盖了卡片描述的行为（打开测试文件看断言，而不只看“通过”）
- [ ] 没有新增卡片未提到的第三方依赖，没有修改已接受的 ADR、规则或流程脚本
- [ ] 提交信息准确描述了改动（type、scope、subject）
- [ ] 确认单中的每一条 ⚠ 都已判断过

## 5. 校验命令

```bash
V=.cursor/skills/git-naming-conventions/scripts/validate_names.py
python $V --branch "$(git branch --show-current)"                       # 分支名
python $V --message "feat(kernel): port change list minimiser"         # 提交信息草稿
python $V --branch feature/p1-03-change-minimiser --message-file .git/COMMIT_EDITMSG --require-trailers
python $V --range origin/main..HEAD --require-trailers                  # 分支上全部提交（CI 使用）
python $V --comment "suggestion: extract the null check into Optional"  # 评论草稿
```

任何 `FAIL` 都要先修正。`WARN` 只是提示（例如未知 scope）。

## 6. PR 标题与正文

- **标题**：该卡提交的 header，例如 `feat(kernel): port change list minimiser`。
- **正文**（`task.py commit --pr` 自动生成；手工创建时用 HEREDOC）：

```markdown
## Summary
- <实际改动，来自 --summary>

## Deviation
- <与卡片描述不一致之处，没有写“无”>

## Test plan
- [x] `<验收命令 1>`
- [x] `<全量检查命令>`

## Review
- 审查 agent：<--reviews>
- 人工确认：已在本地核对确认单（<时间>）

Task: P1-03
Confirmed-by: 张三 <zhangsan@example.com>
```

- 合并方式用 **Squash and merge** 或普通合并均可，但合并只由人在 GitHub 上操作。CI 会检查 PR 上每个提交都带 `Task` 与 `Confirmed-by` 尾注。

## 7. GitHub 评论与评审（Conventional Comments）

```
<label> [decorations]: <subject>

[body：证据、原因、建议的改法]
```

| 意图 | label | 默认 |
|---|---|---|
| 合并前必须修 | `issue` / `todo` | 阻塞 |
| 具体改进 | `suggestion` | 不阻塞，除非加 `(blocking)` |
| 风格琐事 | `nitpick` | 不阻塞 |
| 需要澄清 | `question` | 等回答 |
| 想法 | `thought` | 不阻塞 |
| 值得保留的写法 | `praise` | 不阻塞 |
| 流程 / CI 说明 | `chore` / `note` | 视情况 |

decorations：`(blocking)` `(non-blocking)` `(if-minor)` `(security)` `(perf)`。一条评论一个关注点，先结论后证据。

用 `gh` 发评论时正文一律走 HEREDOC：

```bash
gh pr comment <n> --body "$(cat <<'EOF'
suggestion: move the Optional unwrap next to the hit mapping

Keeps null handling consistent with the other index readers.
EOF
)"

gh pr review <n> --request-changes --body "$(cat <<'EOF'
issue (blocking): revision is applied to memory before the insert commits

This breaks WP-1 (persist first). Move the in-memory apply after the transaction commits.
EOF
)"
```

评审结论：存在未解决的 `issue` / `todo` → `--request-changes`；只有 `suggestion` / `nitpick` / `thought` / `praise` → `--comment` 或 `--approve`；有未解决的阻塞意见时不得 approve。Agent 只起草评审意见，`gh pr review` / `gh pr merge` 由人执行。

## 8. 硬性禁止（Agent）

- 执行 `git commit`、`git push`、`git merge`、`git rebase`、`git cherry-pick`、`git revert`、`git tag`、`git reset --hard`、`git clean -f`，或 `task.py commit`、`repo_setup.py init` / `protect`。
- 使用 `--no-verify`、`--force` / `--force-with-lease`、`--amend`，或修改 `core.hooksPath`。
- 在提交信息中自己写 `Task:` / `Confirmed-by:` 尾注；在卡片中勾选以“人已”开头的判据。
- 被 Cursor 钩子拒绝后换一种写法重试（例如套 `bash -c`、写脚本调用 git）。被拒绝就停下，按第 4 节报告。
- 手工建分支、改分支名、直接在 `main` 上改动。
- 发只有 `LGTM`、`+1`、`fix this` 的评论。

## 9. 由谁强制执行

| 层 | 机制 | 拦住什么 |
|---|---|---|
| Cursor 钩子 | `.cursor/hooks.json` → `.cursor/hooks/commit_gate.py`（`beforeShellExecution`，`failClosed`） | Agent 直接执行第 8 节列出的命令 |
| `task.py commit` | 要求交互式终端、人输入卡号；比对暂存树；拒绝暂存区外的改动 | 未经确认或确认后又改动的提交 |
| Git `commit-msg` 钩子 | `.githooks/commit-msg`（`start` 自动启用） | 格式不对、任务分支上缺尾注的提交 |
| Git `pre-push` 钩子 | `.githooks/pre-push` | 推送到 `main`；推送缺尾注的提交 |
| CI（P0-05） | `validate_names.py --range origin/main..HEAD --require-trailers` | 绕过本地钩子的提交 |
| GitHub 分支保护 | `main` 只能经 PR 合并，CI 必须通过 | 直接写 `main` |

这些措施防的是**误操作和跳步**，不是有意绕过：本地钩子可以被 `--no-verify` 跳过，尾注也可以手写。真正的底线是 GitHub 分支保护加 CI，以及人在合并前看 PR。

安装或更新 ECC 后运行 `python tools/check_ecc.py`：ECC 会覆盖 `.cursor/hooks.json`，提交闸门缺失或不在第一位时用 `--fix-hooks` 修复。

## 参考

- 示例与反例：[reference.md](reference.md)
- 常驻规则：`.cursor/rules/05-git-branch-commit.mdc`
- 开发流程：`docs/04-dev-workflow.md`
