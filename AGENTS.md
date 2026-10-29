# AGENTS.md — AI 编码 Agent 执行协议

本工程的代码全部由 Agent（Cursor）按任务卡实现。这份协议的目的只有一个：**每次只做一张卡，做完能被验证，做不完就停下来说清楚；提交由人确认。**

完整的分支与提交流程见 `docs/04-dev-workflow.md`；本卡用哪些 ECC skill 见 `.cursor/skills/wp-ecc-routing/SKILL.md`。

## 1. 选卡与开工（流程 ①②）

1. 运行 `python tools/task.py next`，从列出的卡中选编号最小的一张。人在对话里指定了卡号时，以人的指定为准。
2. 运行 `python tools/task.py start <卡号>`。它会从 GitHub 主分支创建本卡的分支、拉取到本地，并打印本卡要用的 ECC skill。`start` 报错时（依赖未合并、工作区不干净等）**不要绕过**，把报错原文告诉人。
3. 不要手工创建分支、不要直接在 `main` 上改动。

## 2. 开工前必读

- 卡片全文，包括「ECC 技能」和「不在本卡范围」。
- 卡片「参考」里列出的 ADR、`docs/01-rea/` 规格、旧工程文件。旧工程在 `../webprotege`，**只读**。
- `docs/02-architecture/overview.md` 的模块依赖矩阵（只读一次即可）。
- 按「ECC 技能」加载列出的 skill。ECC skill 的建议与本仓库的 ADR、规则或卡片冲突时，**以本仓库为准**（冲突清单见 `wp-ecc-routing` skill 第 3 节）。

## 3. 实现规则（流程 ③）

- **只做卡片「产出」里写的东西。** 发现需要额外改动（另一个模块、另一个接口），停下，见第 6 节。
- 包名、类名、方法签名：卡片写了就照写；卡片没写的，遵循 `.cursor/rules/20-coding-conventions.mdc`（Java）或 `30-vue-web.mdc` / `31-uniapp.mdc`（前端）。
- 先写测试，再写实现（`tdd-workflow`）。实现行为规格的测试类名以规格编号开头，例如 `BS01_ChangeListMinimiserTest`。
- 旧工程是**行为参照**，不是代码来源：可以读它理解语义、移植测试断言，**不要整段复制**旧代码。
- 每张卡改动量预期 ≤ 400 行（不含测试与测试数据）。明显超出时，停下，见第 6 节。
- 不得修改 `docs/02-architecture/adr/` 下已接受的 ADR；不得新增卡片未提到的第三方依赖。确有必要时写入「阻塞」。

## 4. 测试（流程 ④）

1. 运行 `python tools/task.py test <卡号>`。它会执行卡片「验收」中的每一条命令，再执行全量检查。
2. 失败时修复并重新运行。构建或依赖错误按「ECC 技能」调用对应的 build resolver agent 或 `/build-fix`；修了两轮仍失败且原因不在本卡范围内，见第 6 节。
3. 测试通过后，按「ECC 技能」调用列出的审查 agent，处理它们提出的问题。**审查导致代码改动后，必须重新运行 `test`**（`finish` 会检查代码在测试之后有没有变化）。

## 5. 完成与提交前人工确认（流程 ⑤⑥）

**Agent 不提交、不推送。** 提交和推送由人在自己的终端完成，Agent 做到 `finish` 为止。

1. 逐条核对「完成判据」，把已满足的项改成 `- [x]`。“验收命令全部通过”“全量检查通过”“执行记录已填写”三项由脚本自动勾选；以“人已”开头的项（例如“人已评审新卡片”）由人确认提交时勾选，**Agent 不要勾**。
2. 用 `conventional-commit-review` skill（或 `commit-reviewer` agent）起草提交信息，格式与 scope 见 `.cursor/skills/git-naming-conventions/SKILL.md` 第 3 节。**不要写 `Task:` / `Confirmed-by:` 尾注。**
3. 运行：
   ```bash
   python tools/task.py finish <卡号> -m "<提交信息>" --summary "<一两句话说明实际改动>" --reviews "<已运行的审查 agent>" [--deviation "<与卡片描述不一致之处>"]
   ```
   它会更新卡片状态为 `done`、追加执行记录、暂存全部改动，并生成提交前确认单 `.task-runs/<卡号>.review.md`。卡片要求“写进执行记录”的内容放进 `--summary` 或 `--deviation`；内容较长时，可以先在卡片末尾手工写一个「执行记录」小节。
4. **停止。** 按 `git-naming-conventions` skill 第 4 节的模板，在对话里报告确认单要点（提交信息、改动规模、测试、审查、脚本提示的风险），请人在自己的终端运行 `python tools/task.py commit <卡号>`。之后不再执行任何命令，等人回复。
5. 人提出修改意见时：先运行 `python tools/task.py reject <卡号> --reason "<人的原话>"`，再修改 → `test` → `finish`，重新报告。人已自己运行 `reject` 时，读 `.task-runs/<卡号>.feedback.md` 后照此处理。
6. 人确认并推送后，可以按人的要求用 `/pr` 或 `gh pr create` 发起 PR（`commit --pr` 已创建时跳过）。**合并到 `main` 由人决定。**

Agent 不得执行 `git commit` / `push` / `merge` / `rebase` / `reset --hard`、`task.py commit`、`repo_setup.py init` / `protect`，不得使用 `--no-verify` / `--force` / `--amend`；不得修改 `.github/CODEOWNERS` 中列出的开发标准文件（见 `CONTRIBUTING.md` 第 4 节）。Cursor 钩子会拒绝这些命令；被拒绝时不要换一种写法重试。

## 6. 停下来的情况（不要硬做）

出现以下任一情况，**停止编码**，运行 `python tools/task.py block <卡号> --reason "<卡在哪、看到了什么、建议怎么拆或改>"`，然后在对话里告诉人（`block` 也只准备提交，由人运行 `task.py commit` 确认后才推送）：

- 卡片描述有歧义，两种理解会导致不同的接口或行为。
- 需要改动卡片范围以外的模块或公共接口。
- 改动量明显超过 400 行，或需要超过一天。
- 验收命令在修了两轮之后仍然失败，且原因不在本卡范围内。
- 发现依赖卡的产出与本卡假设不符。
- ECC skill 的要求与本仓库的规则冲突，而 `wp-ecc-routing` 没有给出处理方式。

**宁可停下拆卡，也不要扩大范围。** 上一个项目失败的直接原因就是任务太粗、Agent 自由发挥。

## 7. 规划类卡片

`type: planning` 的卡（例如 `P4-00`）不写业务代码，产出是**新的任务卡**。新卡必须：

- 使用 `tasks/TEMPLATE.md` 的全部小节，frontmatter 中填写 `work_type`（取值见 `tools/ecc-routing.json`）；
- 每张卡单模块、可在一天内完成、有可执行的验收命令；
- 「ECC 技能」小节的内容与 `python tools/task.py ecc <新卡号>` 的输出一致；
- 通过 `python tools/plan_lint.py --check` 校验。
