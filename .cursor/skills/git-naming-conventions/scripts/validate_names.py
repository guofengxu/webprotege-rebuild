#!/usr/bin/env python3
"""Validate branch names, Conventional Commit messages, commit trailers, and GitHub comments.

Usage:
  validate_names.py --branch NAME [--message TEXT | --message-file PATH] [--require-trailers]
  validate_names.py --message TEXT | --message-file PATH
  validate_names.py --comment TEXT          (use '-' to read stdin)
  validate_names.py --range BASE..HEAD [--require-trailers]
        validate every non-merge commit in the range (used by CI)

Task branches (webprotege-rebuild): <type>/<card-id-lower>-<kebab-desc>, e.g. feature/p1-03-change-minimiser.
Commits on task branches are created by `python tools/task.py commit` after a human confirms,
and must carry the trailers:
  Task: P1-03
  Confirmed-by: Name <email>

Exit codes: 0 all checked items pass; 1 otherwise.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys

# Ticket: 123-  or  WP-45- / wp-45-  then kebab description
BRANCH_RE = re.compile(
    r"^(feature|fix|hotfix|test|refactor|docs|chore|release)/"
    r"(([A-Za-z]+-[0-9]+|[0-9]+)-)?"
    r"[a-z0-9]+(-[a-z0-9]+)*$"
)

# Task-card branch created by tools/task.py start
TASK_BRANCH_RE = re.compile(
    r"^(feature|fix|test|refactor|docs|chore)/(p[0-9]-[0-9]{2})-[a-z0-9]+(-[a-z0-9]+)*$"
)

COMMIT_HEADER_RE = re.compile(
    r"^(feat|fix|docs|style|refactor|perf|test|chore|ci|build|revert)"
    r"(\([a-z0-9][a-z0-9./-]*\))?"
    r"(!)?: "
    r"[a-z].{0,71}$"
)

COMMENT_HEADER_RE = re.compile(
    r"^(praise|nitpick|suggestion|issue|todo|question|thought|chore|note)"
    r"( \([a-z0-9-]+\))*"
    r": "
    r".{3,120}$"
)

TASK_TRAILER_RE = re.compile(r"^Task: (P[0-9]-[0-9]{2})$", re.M)
CONFIRMED_TRAILER_RE = re.compile(r"^Confirmed-by: \S.* <[^<>\s@]+@[^<>\s]+>$", re.M)

# Scopes used in webprotege-rebuild (unknown scopes only produce a warning)
KNOWN_SCOPES = {
    "kernel", "app", "shared", "access", "bus", "project", "revision", "ontology", "search",
    "events", "migration", "web", "migrator", "mobile", "api-types", "build", "ci", "deps",
    "tasks", "docs", "testdata", "tools",
}

BANNED_SUBJECTS = {"update", "fix", "wip", "updates", "misc", "temp"}
BANNED_COMMENTS = {"lgtm", "+1", "fix this", "same", "ok", "okay"}

PROTECTED = {"main", "master", "develop", "dev"}


def task_id_from_branch(name: str) -> str | None:
    m = TASK_BRANCH_RE.match(name or "")
    return m.group(2).upper() if m else None


def check_branch(name: str) -> list[str]:
    errors: list[str] = []
    if not name:
        return ["branch name is empty"]
    if name in PROTECTED:
        return [f"refusing to treat protected branch as feature branch: {name}"]
    if not BRANCH_RE.match(name):
        errors.append(
            "branch must match <type>/<optional-ticket>-<kebab-desc>; "
            f"got: {name!r}"
        )
    return errors


def clean_message(message: str) -> str:
    """Drop git comment lines and surrounding blank lines (same as git's default cleanup)."""
    lines = [ln.rstrip() for ln in (message or "").replace("\r\n", "\n").split("\n")
             if not ln.startswith("#")]
    return "\n".join(lines).strip()


def check_commit(message: str, branch: str | None = None,
                 require_trailers: bool = False) -> tuple[list[str], list[str]]:
    """Return (errors, warnings) for one commit message."""
    errors: list[str] = []
    warnings: list[str] = []
    text = clean_message(message)
    if not text:
        return ["commit message is empty"], warnings
    lines = text.split("\n")
    header = lines[0].strip()
    if header.startswith(("Merge ", 'Revert "')):
        return errors, warnings  # created by GitHub merge / git revert; not validated
    if not COMMIT_HEADER_RE.match(header):
        errors.append(
            "commit header must be Conventional Commits "
            "(type(scope)?: subject, subject lowercase start, <= 72 chars, no trailing '.'); "
            f"got: {header!r}"
        )
        return errors, warnings
    subject = header.split(": ", 1)[1]
    if subject.rstrip(".").lower() in BANNED_SUBJECTS:
        errors.append(f"commit subject is too vague: {subject!r}")
    if header.endswith("."):
        errors.append("commit subject must not end with a period")
    if len(lines) > 1 and lines[1].strip():
        errors.append("leave one blank line between the header and the body")
    scope = re.match(r"^[a-z]+\(([^)]+)\)", header)
    if scope and scope.group(1) not in KNOWN_SCOPES:
        warnings.append(f"unknown scope {scope.group(1)!r}; known scopes: {', '.join(sorted(KNOWN_SCOPES))}")

    branch_task = task_id_from_branch(branch) if branch else None
    task_ids = TASK_TRAILER_RE.findall(text)
    if require_trailers or branch_task:
        if not task_ids:
            errors.append("missing trailer 'Task: PX-NN' (added by tools/task.py commit)")
        if not CONFIRMED_TRAILER_RE.search(text):
            errors.append("missing trailer 'Confirmed-by: Name <email>' "
                          "(commits on task branches must be confirmed by a human via tools/task.py commit)")
    if branch_task and task_ids and branch_task not in task_ids:
        errors.append(f"trailer Task: {', '.join(task_ids)} does not match branch card {branch_task}")
    return errors, warnings


def check_message(message: str) -> list[str]:
    """Backward-compatible header-only check."""
    return check_commit(message)[0]


def check_comment(comment: str) -> list[str]:
    errors: list[str] = []
    if comment is None:
        return ["comment is missing"]
    text = comment.strip()
    if not text:
        return ["comment is empty"]
    if text.lower() in BANNED_COMMENTS:
        return [
            "comment is too vague / non-actionable; "
            "use Conventional Comments (<label>: <subject>)"
        ]
    header = text.splitlines()[0].strip()
    if not COMMENT_HEADER_RE.match(header):
        errors.append(
            "comment header must be Conventional Comments "
            "(label [decorations]: subject); "
            f"got: {header!r}"
        )
    return errors


def commits_in_range(rng: str) -> list[tuple[str, str]]:
    out = subprocess.run(
        ["git", "log", "--no-merges", "--format=%H%x00%B%x1e", rng],
        capture_output=True, text=True, encoding="utf-8", errors="replace", check=True,
    ).stdout
    commits = []
    for chunk in out.split("\x1e"):
        chunk = chunk.strip("\n")
        if not chunk:
            continue
        sha, _, body = chunk.partition("\x00")
        commits.append((sha.strip(), body))
    return commits


def _read_maybe_stdin(value: str) -> str:
    return sys.stdin.read() if value == "-" else value


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--branch", help="Branch name to validate")
    parser.add_argument("--message", help="Commit message (use '-' to read stdin)")
    parser.add_argument("--message-file", help="Read the commit message from a file (git commit-msg hook)")
    parser.add_argument("--comment", help="GitHub Issue/PR/Review comment body (use '-' to read stdin)")
    parser.add_argument("--range", help="Validate every non-merge commit in BASE..HEAD")
    parser.add_argument("--require-trailers", action="store_true",
                        help="Require 'Task:' and 'Confirmed-by:' trailers")
    args = parser.parse_args()

    if not any([args.branch, args.message, args.message_file, args.comment, args.range]):
        parser.error("provide --branch, --message, --message-file, --comment and/or --range")

    failures = 0

    if args.branch is not None:
        errs = check_branch(args.branch)
        if errs:
            failures += 1
            print(f"FAIL branch: {args.branch}")
            for e in errs:
                print(f"  - {e}")
        else:
            print(f"OK   branch: {args.branch}")

    msg = None
    if args.message is not None:
        msg = _read_maybe_stdin(args.message)
    elif args.message_file is not None:
        with open(args.message_file, encoding="utf-8") as f:
            msg = f.read()
    if msg is not None:
        errs, warns = check_commit(msg, args.branch, args.require_trailers)
        for w in warns:
            print(f"WARN message: {w}")
        if errs:
            failures += 1
            print("FAIL message:")
            for e in errs:
                print(f"  - {e}")
        else:
            print(f"OK   message: {clean_message(msg).splitlines()[0]}")

    if args.range:
        for sha, body in commits_in_range(args.range):
            errs, warns = check_commit(body, None, args.require_trailers)
            header = clean_message(body).splitlines()[0] if clean_message(body) else ""
            for w in warns:
                print(f"WARN {sha[:8]}: {w}")
            if errs:
                failures += 1
                print(f"FAIL {sha[:8]} {header}")
                for e in errs:
                    print(f"  - {e}")
            else:
                print(f"OK   {sha[:8]} {header}")

    if args.comment is not None:
        comment = _read_maybe_stdin(args.comment)
        errs = check_comment(comment)
        if errs:
            failures += 1
            print("FAIL comment:")
            for e in errs:
                print(f"  - {e}")
        else:
            print(f"OK   comment: {comment.strip().splitlines()[0]}")

    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
