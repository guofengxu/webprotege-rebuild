#!/usr/bin/env python3
"""Cursor beforeShellExecution 钩子：提交前人工确认的闸门。

Agent 不得自己执行会写入 Git 历史或远程仓库的命令。这些操作只能由人在自己的终端里
运行 `python tools/task.py commit <卡号>` 完成（它会显示提交前确认单并要求人输入卡号）。

输入（stdin，JSON）：{"command": "...", "cwd": "...", ...}
输出（stdout，JSON）：{"permission": "allow" | "deny", "user_message": ..., "agent_message": ...}

注意：钩子只拦截 Agent 发起的终端命令，人在自己终端里执行的命令不经过它。
它用于防止误操作和跳过流程，不能防止有意绕过；最终防线是 GitHub 的分支保护与 CI 的尾注检查。
只依赖 Python 标准库。
"""
from __future__ import annotations

import json
import re
import sys

GIT = r"\bgit(?:\.exe)?\b(?:\s+(?:-C\s+\S+|-c\s+\S+|--[\w-]+(?:=\S+)?|-[a-zA-Z]))*\s+"
RULES: list[tuple[re.Pattern[str], str]] = [
    (re.compile(GIT + r"(commit|push|merge|rebase|cherry-pick|revert|am|tag|update-ref|filter-branch|filter-repo|replace|notes)(?![\w-])"),
     "git {0} 会写入提交历史或远程仓库"),
    (re.compile(GIT + r"reset\b[^\n;&|]*--(hard|keep|merge)\b"), "git reset --{0} 会丢弃改动"),
    (re.compile(GIT + r"(?:switch|checkout)\b[^\n;&|]*\s(-f|--force|--discard-changes)\b"), "强制切换分支会丢弃改动"),
    (re.compile(GIT + r"clean\b[^\n;&|]*\s-[a-zA-Z]*f"), "git clean -f 会删除文件"),
    (re.compile(GIT + r"config\b[^\n;&|]*\bcore\.hooksPath\b"), "修改 core.hooksPath 会关闭提交校验"),
    (re.compile(GIT + r"branch\b[^\n;&|]*\s-(D|f|M)\b"), "强制删除或改名分支"),
    (re.compile(r"\btask\.py['\"]?\s+commit\b"), "task.py commit 是人工确认步骤"),
    (re.compile(r"\brepo_setup\.py['\"]?\s+(init|protect)\b"), "repo_setup.py {0} 只能由维护者执行"),
    (re.compile(r"\bgh(?:\.exe)?\s+pr\s+(merge|review)\b"), "gh pr {0} 由人决定"),
    (re.compile(r"\bgh(?:\.exe)?\s+api\b[^\n;&|]*(-X|--method)\s*(POST|PUT|PATCH|DELETE)\b", re.I), "gh api 写操作"),
]


def check(command: str) -> str | None:
    for pattern, why in RULES:
        m = pattern.search(command)
        if m:
            groups = [g for g in m.groups() if g]
            return why.format(*(groups or [""]))
    return None


def main() -> int:
    raw = sys.stdin.read()
    try:
        command = str(json.loads(raw or "{}").get("command", ""))
    except ValueError:
        command = raw
    reason = check(command)
    if reason is None:
        out = {"permission": "allow"}
    else:
        out = {
            "permission": "deny",
            "user_message": f"已拦截 Agent 的命令（{reason}）：{command[:200]}\n"
                            "提交与推送需要你在自己的终端里运行：python tools/task.py commit <卡号>",
            "agent_message": (
                f"已拒绝：{reason}。本仓库的提交与推送必须经过人工确认。"
                "不要重试、不要换一种写法绕过。请运行 python tools/task.py finish <卡号> ...（若尚未运行），"
                "然后停止，把 .task-runs/<卡号>.review.md 的要点告诉人，请人在自己的终端运行 "
                "python tools/task.py commit <卡号>。"
            ),
        }
    sys.stdout.write(json.dumps(out, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
