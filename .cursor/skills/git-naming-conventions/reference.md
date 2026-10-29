# Git 命名与提交确认 —— 参考

正则以 `scripts/validate_names.py` 为准；本文件只做说明和举例。

## 正则

| 对象 | 正则 |
|---|---|
| 任务卡分支 | `^(feature\|fix\|test\|refactor\|docs\|chore)/(p[0-9]-[0-9]{2})-[a-z0-9]+(-[a-z0-9]+)*$` |
| 其它分支 | `^(feature\|fix\|hotfix\|test\|refactor\|docs\|chore\|release)/(([A-Za-z]+-[0-9]+\|[0-9]+)-)?[a-z0-9]+(-[a-z0-9]+)*$` |
| 提交 header | `^(feat\|fix\|docs\|style\|refactor\|perf\|test\|chore\|ci\|build\|revert)(\([a-z0-9][a-z0-9./-]*\))?(!)?: [a-z].{0,71}$` |
| 尾注 Task | `^Task: (P[0-9]-[0-9]{2})$`，且必须与分支中的卡号一致 |
| 尾注 Confirmed-by | `^Confirmed-by: \S.* <[^<>\s@]+@[^<>\s]+>$` |
| 评论 header | `^(praise\|nitpick\|suggestion\|issue\|todo\|question\|thought\|chore\|note)( \([a-z0-9-]+\))*: .{3,120}$` |

其它校验：header 与 body 之间必须空一行；subject 不能是 `update` / `fix` / `wip` / `updates` / `misc` / `temp`；`Merge …` 与 `Revert "…"` 开头的提交（GitHub 合并、`git revert` 生成）不校验。任务分支上的提交、以及 `--require-trailers` 时，两个尾注都必须存在。

## 分支示例

| 卡片 | 分支 |
|---|---|
| P0-01 `repo-skeleton`（build） | `feature/p0-01-repo-skeleton` |
| P0-05 `ci`（ci-devops） | `chore/p0-05-ci` |
| P1-03 `change-minimiser`（kernel） | `feature/p1-03-change-minimiser` |
| P4-00 `web-frontend-planning`（planning） | `docs/p4-00-web-frontend-planning` |
| 人临时修线上问题（非任务卡） | `hotfix/session-leak` |

## 提交示例

Agent 传给 `finish -m` 的内容（没有尾注）：

```
feat(kernel): port change list minimiser

Collapse add/remove pairs before persisting so that only effective changes
reach the revision log (BS-01, BS-02).
```

人确认后，实际写入 Git 的提交：

```
feat(kernel): port change list minimiser

Collapse add/remove pairs before persisting so that only effective changes
reach the revision log (BS-01, BS-02).

Task: P1-03
Confirmed-by: 张三 <zhangsan@example.com>
```

其它常见 header：

```
build: add maven multi-module skeleton and wrapper
feat(revision): persist revisions before applying them in memory
fix(access): reject project actions when the role lacks the capability
test(kernel): add property tests for change list minimiser
feat(web): add class hierarchy tree with lazy loading
feat(mobile): add refresh-token flow for uni-app requests
ci: add pull request workflow with trailer check
docs(tasks): add p4 web frontend task cards
docs(tasks): mark p1-03 as blocked
feat(web)!: require bearer token on project endpoints

BREAKING CHANGE: anonymous project listing is removed.
```

## 提交信息反例

| 反例 | 问题 | 改为 |
|---|---|---|
| `update` | 空主题 | `chore(deps): bump owlapi to 5.5.1` |
| `Fixed bug.` | 大写、过去时、句号 | `fix(revision): keep revision numbers gapless on retry` |
| `feat: Add tree` | subject 大写开头 | `feat(web): add class hierarchy tree` |
| `feat(kernel): port minimiser` 后面紧跟正文（没有空行） | header 后缺空行 | 在 header 与 body 之间空一行 |
| `-m` 里自己写了 `Task: P1-03` | 尾注只能由 `task.py commit` 添加 | 删掉尾注，`finish` 会拒绝 |
| 正文罗列 `A.java, B.java, C.java` | 文件列表不是“为什么” | 写改动的原因与取舍 |
| 卡片 P1-03 的分支上 `Task: P1-04` | 卡号不一致 | 由 `task.py commit` 自动生成，不手写 |

## 分支反例

| 反例 | 问题 | 改为 |
|---|---|---|
| `Feature/ChangeMinimiser` | 大写、驼峰 | 由 `task.py start` 生成 |
| `feature_p1_03` | 下划线、没有 `/` | `feature/p1-03-change-minimiser` |
| `feature/P1-03-change-minimiser` | 卡号大写 | 卡号小写 |
| 开工后改卡片文件名 | 分支名与卡片对不上 | 不改；必须改时先 `block` 让人决定 |

## 人工确认：一次完整的往返

```text
Agent> python tools/task.py test P1-03            # 通过
Agent> （调用 java-reviewer，处理意见，重新 test）
Agent> python tools/task.py finish P1-03 -m "feat(kernel): port change list minimiser" \
         --summary "移植 ChangeListMinimiser 与 BS-01 测试" --reviews "java-reviewer"
       → 生成 .task-runs/P1-03.review.md，打印【需要人工确认】，Agent 停止并在对话里报告

人   > python tools/task.py commit P1-03
       → 显示确认单；输入 d 看完整 diff；输入 n 写原因：
         “minimiser 不应依赖 OWLOntology，按 ADR-005 只用公理列表”
       → 撤回准备，原因写入 .task-runs/P1-03.feedback.md

Agent> 读 feedback.md → 修改 → test → finish（自动撤回旧准备并重新生成确认单）→ 报告
人   > python tools/task.py commit P1-03 --pr
       → 输入 P1-03 → 提交（带 Task / Confirmed-by）→ 推送 → 创建 PR
```

## Agent 被钩子拒绝时

Cursor 钩子返回 `deny` 时，Agent 收到的提示大意是“提交与推送必须经过人工确认，不要重试”。正确做法：

1. 如果还没运行 `finish`，运行它；
2. 在对话里按 SKILL.md 第 4 节的模板报告；
3. 停止。

错误做法：改用 `bash -c "git commit …"`、写 Python 脚本调用 git、加 `--no-verify`、修改 `.cursor/hooks.json`。

## 评论示例

```
suggestion: extract the null check into Optional to match nearby handlers

The surrounding index readers already return Optional; this keeps the NPE path consistent.
```

```
issue (blocking): revision is applied to memory before the insert commits

RevisionWriter.append updates the index first. A failed insert leaves memory ahead of the log (WP-1).
```

```
nitpick (non-blocking): rename tmp to revisionNumber for clarity
```

```
question: which legacy project reproduces the missing prefix declarations?
```

```
praise: clean split between the codec and the revision writer — easy to test in isolation
```

不允许的整条评论（忽略大小写）：`lgtm`、`+1`、`fix this`、`same`、`ok`、`okay`。

## 评论反例

| 反例 | 问题 | 改为 |
|---|---|---|
| `LGTM` | 没有信息 | `praise: clear separation of snapshot and replay paths` |
| `fix this` | 没有 label 和证据 | `issue (blocking): NPE when the short form is null` |
| `这里写得不行` | 含糊 | `suggestion: align error handling with the other command handlers` |
| 一条评论里塞多个无关问题 | 无法逐条解决 | 一条评论一个关注点 |
