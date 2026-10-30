#!/usr/bin/env python3
"""任务卡开发流程（docs/04-dev-workflow.md）。

一张卡的完整流程（Agent 执行 ①~⑤，人执行 ⑥⑦）：
    python tools/task.py start  P1-03        ① 从 GitHub 主分支创建子分支  ② 拉取到本地
                                             ③ 开发（按卡片与 ECC skill 执行）
    python tools/task.py test   P1-03        ④ 本地单元测试：验收命令 + 全量检查，记录结果
    python tools/task.py finish P1-03 -m "feat(kernel): port change list minimiser" --summary "…" --reviews "java-reviewer"
                                             ⑤ 测试通过后更新卡片状态，暂存改动，生成《提交前确认单》，然后停下
    python tools/task.py commit P1-03        ⑥ 【人工确认】人在自己的终端查看确认单，输入卡号后才提交并推送
    python tools/task.py reject P1-03 --reason "…"   ⑥ 不同意提交：撤回准备，原因反馈给 Agent
之后：在 GitHub 发起 PR 并合并到主分支（⑦），依赖本卡的卡才能开工（start 会检查）。

其它子命令：
    python tools/task.py next                列出主分支上现在可以开工的卡
    python tools/task.py status              显示当前分支对应的卡、测试结果与待确认的提交
    python tools/task.py ecc P1-03           显示本卡要用的 ECC skill / agent / 命令
    python tools/task.py block P1-03 --reason "…"   标记阻塞并准备提交，同样需要人工确认

Agent 不得执行 commit：它要求交互式终端并由人输入卡号，Cursor 钩子 .cursor/hooks/commit_gate.py 也会拒绝 Agent 执行。
只依赖 Python 3.9+ 标准库；需要 git，在 Windows 上需要 Git for Windows 自带的 bash。
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_ecc  # noqa: E402
import plan_lint  # noqa: E402

ROOT = plan_lint.ROOT
sys.path.insert(0, str(ROOT / ".cursor" / "skills" / "git-naming-conventions" / "scripts"))
import validate_names  # noqa: E402  命名与提交规范的唯一实现

RUNS = ROOT / ".task-runs"
MAIN = os.environ.get("WP_MAIN_BRANCH", "main")
REMOTE = os.environ.get("WP_REMOTE", "origin")

AUTO_TICK = ("验收命令全部通过", "全量检查通过", "执行记录已填写")
EXCLUDED_FROM_FINGERPRINT = ("tasks/", "docs/03-plan/dependency-graph.md")
SIZE_LIMIT = {"S": 200, "M": 400}
SENSITIVE_PATHS = (
    ("docs/02-architecture/adr/", "修改了 ADR（Agent 不得修改已接受的 ADR）"),
    (".cursor/rules/", "修改了 Cursor 规则"),
    (".cursor/skills/", "修改了 skill"),
    (".cursor/hooks", "修改了 Cursor 钩子"),
    (".githooks/", "修改了 Git 钩子"),
    ("tools/", "修改了流程脚本"),
    (".github/", "修改了 CI 配置"),
)
DEPENDENCY_FILES = ("pom.xml", "package.json", "pnpm-lock.yaml")
TEST_PATH_HINTS = ("/src/test/", "/__tests__/", "/e2e/", "testdata/", ".spec.ts", ".test.ts", "tasks/")


class TaskError(Exception):
    pass


# ---------------------------------------------------------------- 基础工具

def say(msg: str = "") -> None:
    print(msg, flush=True)


def git(*args: str, check: bool = True, env: dict | None = None) -> str:
    proc = subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", env=env)
    if check and proc.returncode != 0:
        raise TaskError(f"git {' '.join(args)} 失败：\n{proc.stderr.strip() or proc.stdout.strip()}")
    return proc.stdout.strip()


def git_ok(*args: str) -> bool:
    return subprocess.run(["git", *args], cwd=ROOT, capture_output=True).returncode == 0


def current_branch() -> str:
    return git("branch", "--show-current")


def find_bash() -> str:
    if os.name == "nt":
        for cand in (r"C:\Program Files\Git\bin\bash.exe", r"C:\Program Files\Git\usr\bin\bash.exe",
                     r"C:\Program Files (x86)\Git\bin\bash.exe"):
            if Path(cand).exists():
                return cand
        found = shutil.which("bash")
        if found and "system32" not in found.lower():
            return found
        raise TaskError("找不到 Git Bash。请安装 Git for Windows，或把它的 bash.exe 所在目录加入 PATH。"
                        "（不要使用 C:\\Windows\\System32\\bash.exe，那是 WSL。）")
    found = shutil.which("bash")
    if not found:
        raise TaskError("找不到 bash")
    return found


def routing() -> dict:
    return plan_lint.load_routing()


def find_card_path(card_id: str) -> Path:
    card_id = card_id.upper()
    matches = [p for p in plan_lint.card_paths()
               if p.name.startswith(card_id + "-")
               and not plan_lint.is_superseded(p.read_text(encoding="utf-8"))]
    if not matches:
        raise TaskError(f"找不到任务卡 {card_id}")
    if len(matches) > 1:
        raise TaskError(f"任务卡 {card_id} 有多个文件：{', '.join(str(m.relative_to(ROOT)) for m in matches)}")
    return matches[0]


def load_card(card_id: str) -> plan_lint.Card:
    return plan_lint.parse(find_card_path(card_id))


def card_on_main(path: Path) -> plan_lint.Card:
    rel = path.relative_to(ROOT).as_posix()
    text = git("show", f"{REMOTE}/{MAIN}:{rel}", check=False)
    if not text:
        raise TaskError(f"主分支 {REMOTE}/{MAIN} 上没有 {rel}。规划文件需要先合并到主分支。")
    return plan_lint.parse_text(text, path)


def branch_name(card: plan_lint.Card) -> str:
    btype = card.meta.get("branch_type")
    if not btype:
        if card.meta.get("type") == "planning":
            btype = "docs"
        elif card.meta.get("work_type") == "ci-devops":
            btype = "chore"
        else:
            btype = "feature"
    name = f"{btype}/{card.id.lower()}-{card.slug}"
    errors = validate_names.check_branch(name)
    if errors or validate_names.task_id_from_branch(name) != card.id:
        raise TaskError(f"生成的分支名不符合规范：{name}（{'; '.join(errors)}）")
    return name


def require_on_branch(card: plan_lint.Card) -> str:
    expected = branch_name(card)
    cur = current_branch()
    if cur != expected:
        raise TaskError(f"当前分支是 {cur!r}，本卡应在 {expected!r} 上进行。先运行 python tools/task.py start {card.id}")
    return expected


def write_lf(path: Path, text: str) -> None:
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)


def set_frontmatter(text: str, key: str, value: str) -> str:
    text = text.replace("\r\n", "\n")
    head, sep, body = text.partition("\n---\n")
    if re.search(rf"^{key}:.*$", head, flags=re.M):
        head = re.sub(rf"^{key}:.*$", f"{key}: {value}", head, count=1, flags=re.M)
    else:
        head += f"\n{key}: {value}"
    return head + sep + body


def fingerprint() -> str:
    """工作区内容（含未跟踪文件，遵守 .gitignore）的指纹，不含 tasks/ 与生成文件。"""
    git_dir = Path(git("rev-parse", "--absolute-git-dir"))
    env = dict(os.environ, GIT_INDEX_FILE=str(git_dir / "task-fingerprint.index"))
    if git_ok("rev-parse", "--verify", "HEAD"):
        git("read-tree", "HEAD", env=env)
    else:
        git("read-tree", "--empty", env=env)
    git("add", "-A", env=env)
    tree = git("write-tree", env=env)
    lines = [ln for ln in git("ls-tree", "-r", tree).splitlines()
             if not ln.split("\t", 1)[-1].startswith(EXCLUDED_FROM_FINGERPRINT)]
    return hashlib.sha256("\n".join(lines).encode("utf-8")).hexdigest()


def ecc_lines(card: plan_lint.Card) -> list[str]:
    r = routing()
    wt = card.meta.get("work_type", "")
    t = r["workTypes"][wt]
    extras = card.meta.get("ecc_extra", [])

    def merged(key: str) -> list[str]:
        out = list(t.get(key, []))
        for e in extras:
            out += [x for x in r["extras"][e].get(key, []) if x not in out]
        return out

    lines = [f"工作项类型：{wt} —— {t['label']}" + (f"；附加：{', '.join(extras)}" if extras else "")]
    lines.append("  开工前加载的 skill：" + ", ".join(merged("skills")))
    if merged("assistAgents"):
        lines.append("  开工时可用的辅助 agent：" + ", ".join(merged("assistAgents")))
    lines.append("  提交前调用的审查 agent：" + ", ".join(merged("agents")))
    if merged("commands"):
        lines.append("  可用命令：" + ", ".join(merged("commands")))
    of = t.get("onFailure", {})
    if of:
        lines.append("  构建或测试失败时：" + ", ".join(of.get("agents", []) + of.get("commands", [])))
    notes = [t["notes"]] if t.get("notes") else []
    notes += [r["extras"][e]["notes"] for e in extras if r["extras"][e].get("notes")]
    for n in notes:
        lines.append("  注意：" + n)
    return lines


def card_ecc_names(card: plan_lint.Card) -> dict[str, set[str]]:
    r = routing()
    groups = [r["workTypes"][card.meta["work_type"]]] + [r["extras"][e] for e in card.meta.get("ecc_extra", [])]
    names = {"skills": set(), "agents": set(), "commands": set()}
    for g in groups:
        names["skills"].update(g.get("skills", []))
        names["agents"].update(g.get("agents", []) + g.get("assistAgents", []))
        names["commands"].update(g.get("commands", []))
        of = g.get("onFailure", {})
        names["agents"].update(of.get("agents", []))
        names["commands"].update(of.get("commands", []))
    return names


def full_check_commands(card: plan_lint.Card) -> list[str]:
    kind = routing()["workTypes"][card.meta["work_type"]].get("fullCheck", "java")
    module = card.meta.get("module", "")
    if kind == "auto":
        kind = "web" if "wp-web" in module else "mobile" if "wp-mobile" in module else "java"
    if kind == "java":
        return ["./mvnw -q verify"] if (ROOT / "mvnw").exists() else []
    if kind == "web":
        return ["pnpm --filter wp-web run verify"]
    if kind == "mobile":
        return ["pnpm --filter wp-mobile run verify"]
    if kind == "api-types":
        return ["pnpm --filter @wp/api-types run check"]
    return []


def run_shell(bash: str, command: str) -> tuple[int, float]:
    say(f"\n$ {command}")
    start = time.time()
    proc = subprocess.run([bash, "-c", command], cwd=ROOT)
    return proc.returncode, time.time() - start


def dependents(card_id: str) -> list[str]:
    cards, _, _ = plan_lint.load_cards()
    return sorted(c.id for c in cards if card_id in c.deps)



def pending_file(card_id: str) -> Path:
    return RUNS / f"{card_id}.pending.json"


def review_file(card_id: str) -> Path:
    return RUNS / f"{card_id}.review.md"


def feedback_file(card_id: str) -> Path:
    return RUNS / f"{card_id}.feedback.md"


def ensure_git_hooks() -> None:
    hooks = ROOT / ".githooks"
    if not hooks.is_dir():
        return
    if git("config", "--get", "core.hooksPath", check=False) != ".githooks":
        git("config", "core.hooksPath", ".githooks")
        say("已启用仓库自带的 Git 钩子（core.hooksPath=.githooks）")
    if os.name != "nt":
        for f in hooks.iterdir():
            if f.is_file():
                f.chmod(0o755)


def staged_tree() -> str:
    return git("write-tree")


def worktree_clean_except_index() -> list[str]:
    """返回暂存区之外的改动（未暂存的修改与未跟踪文件）。"""
    problems = []
    unstaged = git("diff", "--name-only")
    if unstaged:
        problems += [f"未暂存的修改：{p}" for p in unstaged.splitlines()]
    untracked = git("ls-files", "--others", "--exclude-standard")
    if untracked:
        problems += [f"未跟踪的文件：{p}" for p in untracked.splitlines()]
    return problems


def staged_numstat() -> list[tuple[int, int, str]]:
    rows = []
    for ln in git("diff", "--cached", "--numstat").splitlines():
        a, d, p = ln.split("\t", 2)
        rows.append((int(a) if a.isdigit() else 0, int(d) if d.isdigit() else 0, p))
    return rows


def module_prefixes(card: plan_lint.Card) -> set[str] | None:
    tokens = [t.strip() for t in re.split(r"[,，、\s]+", card.meta.get("module", "")) if t.strip()]
    if any(t == "root" for t in tokens):
        return None
    prefixes = set()
    for t in tokens:
        top = t.split("/")[0]
        if top.startswith(("wp-", "packages", "testdata", "tasks", "docs")):
            prefixes.add(top)
    return prefixes or None


def regenerate_index() -> bool:
    """重新生成 tasks/INDEX.md 与依赖图（纳入 Git，需与卡片保持一致）。"""
    return subprocess.run([sys.executable, str(ROOT / "tools" / "plan_lint.py")], cwd=ROOT,
                          capture_output=True).returncode == 0


def restore_pending(card_id: str) -> dict | None:
    """撤回一次准备：恢复卡片原文、取消暂存、删除待确认记录。"""
    pf = pending_file(card_id)
    if not pf.exists():
        return None
    pending = json.loads(pf.read_text(encoding="utf-8"))
    git("reset", "-q")
    write_lf(ROOT / pending["cardPath"], pending["cardBackup"])
    regenerate_index()
    pf.unlink()
    rf = review_file(card_id)
    if rf.exists():
        rf.unlink()
    return pending


def confirmer() -> str:
    name = git("config", "user.name", check=False)
    email = git("config", "user.email", check=False)
    if not name or not email:
        raise TaskError("请先设置 git config user.name 与 user.email（它们会写入 Confirmed-by 尾注）。")
    return f"{name} <{email}>"

def cmd_start(args: argparse.Namespace) -> None:
    if git("status", "--porcelain"):
        raise TaskError("工作区有未提交的改动。请先提交、暂存（git stash）或丢弃后再开始新卡。")
    say(f"同步 {REMOTE} ...")
    git("fetch", REMOTE, "--prune")
    if not git_ok("rev-parse", "--verify", f"{REMOTE}/{MAIN}"):
        raise TaskError(f"远程没有主分支 {REMOTE}/{MAIN}。首次使用请先按 docs/04-dev-workflow.md 第 2 节初始化仓库。")
    path = find_card_path(args.card)
    main_card = card_on_main(path)
    if main_card.status == "done":
        raise TaskError(f"{main_card.id} 在主分支上已经完成。")
    if main_card.status != "todo" and not args.resume:
        raise TaskError(f"{main_card.id} 在主分支上的状态是 {main_card.status}，不是 todo。")
    blocked_deps = []
    for dep in main_card.deps:
        dep_card = card_on_main(find_card_path(dep))
        if dep_card.status != "done":
            blocked_deps.append(f"{dep}（{dep_card.status}）")
    if blocked_deps:
        raise TaskError("以下依赖在主分支上尚未完成（需要先合并它们的 PR）：" + "、".join(blocked_deps))

    branch = branch_name(main_card)
    remote_exists = bool(git("ls-remote", "--heads", REMOTE, branch))
    if remote_exists and not args.resume:
        raise TaskError(f"远程已存在分支 {branch}。如果要继续之前的工作，加 --resume。")
    if not remote_exists:
        say(f"① 在 GitHub 上从 {MAIN} 创建子分支 {branch}")
        # 认领：只有真正“新建”了远程分支才算成功。分支已存在（同事刚刚认领）时 git 会报
        # Everything up-to-date 或被服务器拒绝，两种情况都没有 [new branch]。
        proc = subprocess.run(["git", "push", "--porcelain", REMOTE, f"{REMOTE}/{MAIN}:refs/heads/{branch}"],
                              cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace")
        if proc.returncode != 0 or "[new branch]" not in proc.stdout:
            raise TaskError(f"没能在 GitHub 上新建分支 {branch}：很可能同事刚刚认领了 {main_card.id}。"
                            f"请运行 python tools/task.py next 换一张卡。\n{(proc.stdout + proc.stderr).strip()}")
    say(f"② 拉取到本地并切换到 {branch}")
    git("fetch", REMOTE, branch)
    if git_ok("show-ref", "--verify", f"refs/heads/{branch}"):
        git("switch", branch)
        git("pull", "--ff-only", REMOTE, branch)
    else:
        git("switch", "-c", branch, "--track", f"{REMOTE}/{branch}")

    local = plan_lint.parse(path)
    if local.status == "todo":
        write_lf(path, set_frontmatter(path.read_text(encoding="utf-8"), "status", "in_progress"))
    ensure_git_hooks()
    say(f"\n已就绪：{local.id} {local.meta['title']}")
    say(f"卡片：{path.relative_to(ROOT).as_posix()}")
    for ln in ecc_lines(local):
        say(ln)
    missing = check_ecc.missing_items(card_ecc_names(local))
    if any(missing.values()):
        say("  警告：本卡需要的以下 ECC 项尚未安装到 Cursor（运行 python tools/check_ecc.py --print-install 查看安装方法）：")
        for k, v in missing.items():
            if v:
                say(f"    {k}: {', '.join(v)}")
    say(f"\n③ 按卡片开发；完成后运行：python tools/task.py test {local.id}")


def cmd_test(args: argparse.Namespace) -> None:
    card = load_card(args.card)
    branch = require_on_branch(card)
    bash = find_bash()
    commands = card.accept_commands()
    full = full_check_commands(card)
    results = []
    ok = True
    say(f"④ 本地测试：{card.id}（验收命令 {len(commands)} 条，全量检查 {len(full)} 条）")
    for phase, cmds in (("验收", commands), ("全量检查", full)):
        for c in cmds:
            code, secs = run_shell(bash, c)
            results.append({"phase": phase, "command": c, "exitCode": code, "seconds": round(secs, 1)})
            if code != 0:
                ok = False
                break
        if not ok:
            break
    if ok:
        lint = subprocess.run([sys.executable, str(ROOT / "tools" / "plan_lint.py"), "--check"], cwd=ROOT)
        results.append({"phase": "任务卡校验", "command": "plan_lint --check", "exitCode": lint.returncode, "seconds": 0})
        ok = lint.returncode == 0
    RUNS.mkdir(exist_ok=True)
    record = {
        "card": card.id, "branch": branch, "passed": ok,
        "finishedAt": dt.datetime.now().astimezone().isoformat(timespec="seconds"),
        "fingerprint": fingerprint(), "results": results,
    }
    (RUNS / f"{card.id}.json").write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
    say("")
    if ok:
        say(f"④ 测试通过。下一步：按卡片「ECC 技能」调用审查 agent（审查后有改动需重新 test），然后运行\n"
            f"   python tools/task.py finish {card.id} -m \"<type>(<scope>): <subject>\" --summary \"<实际改动>\" --reviews \"<已运行的审查 agent>\"")
    else:
        failed = results[-1]
        raise TaskError(f"测试失败：[{failed['phase']}] {failed['command']}（退出码 {failed['exitCode']}）。"
                        f"修复后重新运行 test；构建失败可按卡片「ECC 技能」调用对应的 build resolver。")


# ---------------------------------------------------------------- ⑤ 准备提交 / ⑥ 人工确认

HUMAN_PREFIX = "人已"   # 以“人已”开头的完成判据由人确认提交时勾选，Agent 不能勾


def now() -> str:
    return dt.datetime.now().astimezone().isoformat(timespec="seconds")


def log_event(card_id: str, event: dict) -> None:
    RUNS.mkdir(exist_ok=True)
    with open(RUNS / f"{card_id}.log.jsonl", "a", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps({"at": now(), **event}, ensure_ascii=False) + "\n")


def check_agent_message(message: str) -> str:
    message = validate_names.clean_message(message)
    if validate_names.TASK_TRAILER_RE.search(message) or validate_names.CONFIRMED_TRAILER_RE.search(message) \
            or re.search(r"^(Task|Confirmed-by):", message, flags=re.M):
        raise TaskError("提交信息中不要写 Task: / Confirmed-by: 尾注，它们由人确认时自动添加。")
    errors, warnings = validate_names.check_commit(message)
    if errors:
        raise TaskError("提交信息不符合规范（见 .cursor/skills/git-naming-conventions/SKILL.md）：\n  - "
                        + "\n  - ".join(errors))
    for w in warnings:
        say(f"提示：{w}")
    return message


def expected_reviews(card: plan_lint.Card) -> list[str]:
    r = routing()
    out = list(r["workTypes"][card.meta["work_type"]].get("agents", []))
    for e in card.meta.get("ecc_extra", []):
        out += [a for a in r["extras"][e].get("agents", []) if a not in out]
    return out


def is_test_path(p: str) -> bool:
    return any(h in "/" + p for h in TEST_PATH_HINTS)


def review_warnings(card: plan_lint.Card, numstat: list[tuple[int, int, str]], reviews: list[str] | None) -> list[str]:
    warnings = []
    card_text = card.path.read_text(encoding="utf-8")
    size = card.meta.get("size", "M")
    prod_lines = sum(a + d for a, d, p in numstat if not is_test_path(p))
    limit = SIZE_LIMIT.get(size)
    if limit and prod_lines > limit:
        warnings.append(f"改动量 {prod_lines} 行（不含测试与测试数据），超过 {size} 号卡的预期 {limit} 行")
    prefixes = module_prefixes(card)
    if prefixes:
        outside = sorted({p.split("/")[0] if "/" in p else "（根目录）" + p
                          for _, _, p in numstat
                          if not p.startswith("tasks/") and (p.split("/")[0] not in prefixes)})
        if outside:
            warnings.append(f"改动了卡片模块（{card.meta.get('module')}）以外的位置：{', '.join(outside)}")
    for _, _, p in numstat:
        for prefix, why in SENSITIVE_PATHS:
            if p.startswith(prefix):
                hint = "（卡片「产出」中提到了它）" if p in card_text else ""
                warnings.append(f"{why}：{p}{hint}")
        if p.split("/")[-1] in DEPENDENCY_FILES:
            warnings.append(f"修改了依赖声明 {p}：确认没有新增卡片未提到的第三方依赖")
    if reviews is not None:
        missing = [a for a in expected_reviews(card) if a not in reviews]
        if missing:
            warnings.append(f"卡片要求的审查 agent 没有在 --reviews 中列出：{', '.join(missing)}")
    return warnings


def write_review(card: plan_lint.Card, pending: dict, record: dict | None) -> Path:
    cid = card.id
    stat = subprocess.run(["git", "diff", "--cached", "--stat"], cwd=ROOT, capture_output=True, text=True,
                          encoding="utf-8", errors="replace").stdout.rstrip()
    human_items = pending.get("humanItems", [])
    lines = [
        f"# 提交前确认单：{cid} {card.meta['title']}",
        "",
        f"- 分支：`{pending['branch']}`",
        f"- 类型：{'完成任务' if pending['kind'] == 'finish' else '标记阻塞'}",
        f"- 准备时间：{pending['preparedAt']}",
        "",
        "## 提交信息",
        "",
        "```text",
        pending["message"],
        "",
        f"Task: {cid}",
        "Confirmed-by: <确认时取 git config user.name / user.email>",
        "```",
        "",
    ]
    if pending["kind"] == "finish":
        lines += ["## Agent 说明", "", f"- 实际改动：{pending['summary']}", f"- 偏差：{pending['deviation']}",
                  f"- 已运行的审查 agent：{', '.join(pending['reviews']) or '（未填写）'}", ""]
        if record:
            lines += ["## 本地测试", "", f"最近一次：{record['finishedAt']}，通过；测试之后代码没有变化。", ""]
            lines += [f"- [{r['phase']}] `{r['command']}` → 退出码 {r['exitCode']}（{r['seconds']}s）"
                      for r in record["results"]]
            lines.append("")
    else:
        lines += ["## 阻塞原因", "", pending["summary"], ""]
    lines += ["## 脚本发现的风险（需要人判断）", ""]
    lines += [f"- ⚠ {w}" for w in pending["warnings"]] or ["- 无"]
    lines += ["", "## 请人逐项核对", "",
              "- [ ] 改动只涉及卡片「产出」，没有「不在本卡范围」里的内容",
              "- [ ] 测试确实覆盖了卡片描述的行为（打开测试文件看断言，而不只看通过）",
              "- [ ] 没有新增卡片未提到的第三方依赖，没有修改已接受的 ADR、规则或流程脚本",
              "- [ ] 提交信息准确描述了改动（类型、范围、主题）"]
    lines += [f"- [ ] {it}（确认提交时自动勾选）" for it in human_items]
    lines += ["", "## 改动文件", "", "```text", stat or "（无）", "```", "",
              "## 操作（在你自己的终端里执行，不要让 Agent 执行）", "",
              f"- 确认并提交、推送：`python tools/task.py commit {cid}`（会再次显示本确认单，输入卡号 {cid} 才会提交）",
              "- 查看完整改动：`git diff --cached`",
              f"- 不同意：`python tools/task.py reject {cid} --reason \"原因\"`，原因会写入 `.task-runs/{cid}.feedback.md` 交给 Agent",
              ""]
    out = review_file(cid)
    write_lf(out, "\n".join(lines))
    return out


def stage_pending(card: plan_lint.Card, path: Path, backup: str, kind: str, message: str,
                  summary: str, deviation: str, reviews: list[str], record: dict | None,
                  human_items: list[str]) -> None:
    git("add", "-A")
    if not git("diff", "--cached", "--name-only"):
        raise TaskError("没有任何改动可以提交。")
    numstat = staged_numstat()
    pending = {
        "card": card.id, "kind": kind, "branch": current_branch(),
        "message": message, "header": message.splitlines()[0],
        "summary": summary, "deviation": deviation, "reviews": reviews,
        "stagedTree": staged_tree(), "cardPath": path.relative_to(ROOT).as_posix(), "cardBackup": backup,
        "preparedAt": now(), "testFinishedAt": record["finishedAt"] if record else None,
        "humanItems": human_items,
        "warnings": review_warnings(card, numstat, reviews if kind == "finish" else None),
    }
    RUNS.mkdir(exist_ok=True)
    pending_file(card.id).write_text(json.dumps(pending, ensure_ascii=False, indent=2), encoding="utf-8")
    review = write_review(card, pending, record)
    log_event(card.id, {"event": "prepared", "kind": kind, "header": pending["header"],
                        "stagedTree": pending["stagedTree"], "warnings": len(pending["warnings"])})
    rel = review.relative_to(ROOT).as_posix()
    say(f"\n已暂存改动并生成提交前确认单：{rel}")
    for w in pending["warnings"]:
        say(f"  ⚠ {w}")
    say("\n" + "=" * 72)
    say("【需要人工确认】Agent 到此停止：不要执行 git commit / git push / task.py commit。")
    say(f"请把确认单 {rel} 的要点告诉人，并请人在自己的终端运行：")
    say(f"    python tools/task.py commit {card.id}")
    say(f"人不同意时会运行 reject，原因写入 .task-runs/{card.id}.feedback.md。")
    say("=" * 72)


def cmd_finish(args: argparse.Namespace) -> None:
    """⑤ 测试通过后更新卡片状态，暂存全部改动，生成确认单。不提交。"""
    card = load_card(args.card)
    require_on_branch(card)
    message = check_agent_message(args.message)
    if restore_pending(card.id):
        say("已撤回上一次未确认的提交准备，重新准备。")
    path = find_card_path(card.id)
    card = plan_lint.parse(path)
    run_file = RUNS / f"{card.id}.json"
    if not run_file.exists():
        raise TaskError(f"没有测试记录。先运行 python tools/task.py test {card.id}")
    record = json.loads(run_file.read_text(encoding="utf-8"))
    if not record.get("passed"):
        raise TaskError("最近一次测试没有通过，不能更新状态和准备提交。")
    if record.get("fingerprint") != fingerprint():
        raise TaskError("测试之后代码又有改动。请重新运行 test，测试通过后再 finish。")

    backup = path.read_text(encoding="utf-8").replace("\r\n", "\n")
    text = backup
    for item in AUTO_TICK:
        text = re.sub(rf"^- \[ \] ({re.escape(item)}.*)$", r"- [x] \1", text, flags=re.M)
    unchecked = re.findall(r"^- \[ \] (.*)$", text, flags=re.M)
    human_items = [u for u in unchecked if u.startswith(HUMAN_PREFIX)]
    agent_items = [u for u in unchecked if not u.startswith(HUMAN_PREFIX)]
    if agent_items:
        raise TaskError("还有未勾选的完成判据，请逐条确认后在卡片中勾选（改成 - [x]）：\n  - " + "\n  - ".join(agent_items))
    ticked_human = [ln for ln in re.findall(r"^- \[x\] (.*)$", text, flags=re.M) if ln.startswith(HUMAN_PREFIX)]
    if ticked_human:
        raise TaskError("以“人已”开头的完成判据只能由人确认提交时勾选，请改回 - [ ]：\n  - " + "\n  - ".join(ticked_human))
    if "## 执行记录" not in text:
        text = text.rstrip() + "\n\n## 执行记录\n"
    reviews = [r.strip() for r in re.split(r"[,，\s]+", args.reviews or "") if r.strip()]
    text = text.rstrip() + (
        f"\n- 日期：{dt.date.today().isoformat()}\n- 分支：{current_branch()}\n- 测试：{record['finishedAt']} 通过"
        f"（{len(record['results'])} 项）\n- 实际改动：{args.summary}\n- 偏差：{args.deviation}\n"
        f"- 审查：{', '.join(reviews) or '无'}\n"
    )
    text = set_frontmatter(text, "status", "done")
    write_lf(path, text)
    lint = subprocess.run([sys.executable, str(ROOT / "tools" / "plan_lint.py")], cwd=ROOT)
    if lint.returncode != 0:
        write_lf(path, backup)
        raise TaskError("任务卡校验失败，卡片已恢复原样。请按上面的错误修正后重新 finish。")
    say("⑤ 已更新卡片状态为 done（尚未提交）")
    stage_pending(card, path, backup, "finish", message, args.summary, args.deviation, reviews, record, human_items)


def cmd_block(args: argparse.Namespace) -> None:
    """标记阻塞并准备提交（含当前进度），同样需要人确认。"""
    card = load_card(args.card)
    require_on_branch(card)
    restore_pending(card.id)
    path = find_card_path(card.id)
    backup = path.read_text(encoding="utf-8").replace("\r\n", "\n")
    text = set_frontmatter(backup, "status", "blocked").rstrip()
    text += f"\n\n## 阻塞\n- 日期：{dt.date.today().isoformat()}\n- 原因：{args.reason}\n"
    write_lf(path, text)
    if not regenerate_index():
        write_lf(path, backup)
        regenerate_index()
        raise TaskError("任务卡校验失败，卡片已恢复原样。请运行 python tools/plan_lint.py 查看错误。")
    message = f"docs(tasks): mark {card.id.lower()} as blocked"
    say(f"已在本地把 {card.id} 标记为 blocked（尚未提交）")
    stage_pending(card, path, backup, "block", message, args.reason, "", [], None, [])


def require_human_terminal() -> None:
    if not (sys.stdin.isatty() and sys.stdout.isatty()):
        raise TaskError("commit 只能由人在交互式终端中运行（检测到输入或输出不是终端）。"
                        "Agent 不得执行本命令；请人打开自己的终端运行。")


def verify_pending(card: plan_lint.Card) -> dict:
    pf = pending_file(card.id)
    if not pf.exists():
        raise TaskError(f"{card.id} 没有待确认的提交。Agent 需要先运行 finish（或 block）。")
    pending = json.loads(pf.read_text(encoding="utf-8"))
    if current_branch() != pending["branch"]:
        raise TaskError(f"当前分支 {current_branch()!r} 与准备时的分支 {pending['branch']!r} 不一致。")
    if staged_tree() != pending["stagedTree"]:
        raise TaskError("暂存区与准备时不一致（确认单生成之后有人改动或重新暂存了文件）。"
                        f"请让 Agent 重新运行 test 与 finish，或运行 reject {card.id}。")
    problems = worktree_clean_except_index()
    if problems:
        raise TaskError("暂存区以外还有改动，这些改动没有经过测试和确认单：\n  - " + "\n  - ".join(problems[:20])
                        + f"\n请让 Agent 处理（重新 test 与 finish），或运行 reject {card.id}。")
    return pending


def do_reject(card_id: str, reason: str, by: str) -> None:
    pending = restore_pending(card_id)
    if not pending:
        raise TaskError(f"{card_id} 没有待确认的提交。")
    fb = feedback_file(card_id)
    entry = (f"\n## {now()} 未通过人工确认（{by}）\n\n- 准备的提交：{pending['header']}\n- 原因：{reason}\n"
             f"- 处理：按原因修改后重新运行 test 与 finish。\n")
    old = fb.read_text(encoding="utf-8") if fb.exists() else f"# {card_id} 人工确认反馈\n"
    write_lf(fb, old.rstrip() + "\n" + entry)
    log_event(card_id, {"event": "rejected", "by": by, "reason": reason, "header": pending["header"]})
    say("已撤回提交准备：取消暂存，卡片恢复为准备前的内容；代码改动保留在工作区。")
    say(f"反馈已写入 {fb.relative_to(ROOT).as_posix()}，请让 Agent 读取后继续。")


def tick_human_items(path: Path, items: list[str]) -> None:
    if not items:
        return
    text = path.read_text(encoding="utf-8").replace("\r\n", "\n")
    for it in items:
        text = re.sub(rf"^- \[ \] {re.escape(it)}$", f"- [x] {it}", text, count=1, flags=re.M)
    write_lf(path, text)
    git("add", "--", path.relative_to(ROOT).as_posix())


def push_and_pr(card: plan_lint.Card, pending: dict, trailers: str, open_pr: bool) -> None:
    branch = pending["branch"]
    git("push", REMOTE, branch)
    say(f"⑥ 已推送到 {REMOTE}/{branch}")
    if not open_pr:
        return
    if not shutil.which("gh"):
        say("没有找到 gh 命令，请在 GitHub 网页上创建 PR。")
        return
    record_file = RUNS / f"{card.id}.json"
    tests = []
    if record_file.exists() and pending["kind"] == "finish":
        tests = [f"- [x] `{r['command']}`" for r in json.loads(record_file.read_text(encoding="utf-8"))["results"]]
    body = (f"## Summary\n- {pending['summary']}\n\n## Deviation\n- {pending['deviation'] or '无'}\n\n"
            f"## Test plan\n" + ("\n".join(tests) or "- 无（阻塞说明）") +
            f"\n\n## Review\n- 审查 agent：{', '.join(pending['reviews']) or '无'}\n"
            f"- 人工确认：已在本地核对确认单（{pending['preparedAt']}）\n\n{trailers}\n")
    subprocess.run(["gh", "pr", "create", "--base", MAIN, "--head", branch,
                    "--title", pending["header"], "--body", body], cwd=ROOT, check=False)


def cmd_commit(args: argparse.Namespace) -> None:
    """⑥ 人工确认：只能由人在交互式终端运行。"""
    require_human_terminal()
    card = load_card(args.card)
    pending = verify_pending(card)
    who = confirmer()
    say(review_file(card.id).read_text(encoding="utf-8") if review_file(card.id).exists() else "（确认单缺失）")
    while True:
        try:
            ans = input(f"\n输入卡号 {card.id} 确认提交{'并推送' if not args.no_push else ''}；"
                        f"d 查看完整改动；n 不同意；q 暂不处理：").strip()
        except EOFError:
            raise TaskError("没有读到输入，已取消。")
        if ans.lower() == "d":
            subprocess.run(["git", "diff", "--cached"], cwd=ROOT)
            continue
        if ans.lower() == "q":
            say("暂不处理，提交准备保留。")
            return
        if ans.lower() == "n":
            reason = input("请写下不同意的原因（会交给 Agent）：").strip() or "未说明原因"
            do_reject(card.id, reason, who)
            return
        if ans.upper() == card.id:
            break
        say(f"输入的不是 {card.id}，没有提交。")

    verify_pending(card)   # 等待输入期间可能有改动
    path = ROOT / pending["cardPath"]
    tick_human_items(path, pending.get("humanItems", []))
    trailers = f"Task: {card.id}\nConfirmed-by: {who}"
    git("commit", "-m", pending["message"], "-m", trailers)
    sha = git("rev-parse", "--short", "HEAD")
    say(f"⑥ 已提交 {sha}：{pending['header']}")
    log_event(card.id, {"event": "confirmed", "by": who, "commit": git("rev-parse", "HEAD"),
                        "header": pending["header"], "kind": pending["kind"]})
    pending_file(card.id).unlink()
    if review_file(card.id).exists():
        review_file(card.id).unlink()
    if feedback_file(card.id).exists():
        feedback_file(card.id).rename(RUNS / f"{card.id}.feedback.resolved.md")
    if args.no_push:
        say("已跳过推送（--no-push）。稍后运行：git push")
        return
    push_and_pr(card, pending, trailers, args.pr)
    if pending["kind"] == "finish":
        deps = dependents(card.id)
        say(f"\n下一步（⑦）：在 GitHub 上发起 PR，CI 通过、评审通过后合并到 {MAIN}。"
            + (f"合并之后，依赖本卡的 {'、'.join(deps)} 才能开工。" if deps else ""))
    else:
        say("\n阻塞已推送。请处理阻塞原因（拆卡、改卡或补充说明）后再继续。")


def cmd_reject(args: argparse.Namespace) -> None:
    card = load_card(args.card)
    do_reject(card.id, args.reason, git("config", "user.name", check=False) or "unknown")


def remote_task_branches() -> dict[str, str]:
    """远程已存在的任务分支：卡号 → 分支名。分支存在即表示有人已认领。"""
    out = {}
    for ln in git("ls-remote", "--heads", REMOTE).splitlines():
        ref = ln.split("\t", 1)[-1].removeprefix("refs/heads/") if "\t" in ln else ""
        cid = validate_names.task_id_from_branch(ref)
        if cid:
            out[cid] = ref
    return out


def cmd_next(args: argparse.Namespace) -> None:
    git("fetch", REMOTE, "--prune")
    cards = {}
    for path in plan_lint.card_paths():
        try:
            c = card_on_main(path)
        except (TaskError, ValueError):
            continue
        cards[c.id] = c
    claimed = remote_task_branches()
    ready = [c for c in cards.values() if c.status == "todo"
             and all(cards.get(d) and cards[d].status == "done" for d in c.deps)]
    free = [c for c in ready if c.id not in claimed]
    taken = [c for c in cards.values() if c.id in claimed and c.status != "done"]
    say(f"主分支 {REMOTE}/{MAIN} 上现在可以开工、且还没有人认领的卡：")
    for c in sorted(free, key=lambda c: c.id):
        say(f"  {c.id}  {c.meta['title']}")
    if not free:
        say("  无")
    if taken:
        say("\n已被认领（远程已有分支，尚未合并）：")
        for c in sorted(taken, key=lambda c: c.id):
            say(f"  {c.id}  {c.meta['title']}  ← {claimed[c.id]}")


def cmd_status(args: argparse.Namespace) -> None:
    branch = current_branch()
    cid = validate_names.task_id_from_branch(branch)
    if not cid:
        say(f"当前分支 {branch!r} 不对应任何任务卡。")
        return
    card = load_card(cid)
    say(f"{card.id} {card.meta['title']}  状态：{card.status}  分支：{branch}")
    run_file = RUNS / f"{card.id}.json"
    if run_file.exists():
        rec = json.loads(run_file.read_text(encoding="utf-8"))
        fresh = rec.get("fingerprint") == fingerprint()
        say(f"最近测试：{rec['finishedAt']}  {'通过' if rec['passed'] else '失败'}  "
            f"{'（与当前代码一致）' if fresh else '（之后代码有改动，需要重新测试）'}")
    else:
        say("还没有运行过测试。")
    pf = pending_file(card.id)
    if pf.exists():
        p = json.loads(pf.read_text(encoding="utf-8"))
        intact = staged_tree() == p["stagedTree"] and not worktree_clean_except_index()
        say(f"待人工确认：{p['header']}（{p['preparedAt']}，风险提示 {len(p['warnings'])} 条）"
            + ("" if intact else "  ⚠ 准备之后又有改动，需要重新 finish"))
        say(f"  确认单：{review_file(card.id).relative_to(ROOT).as_posix()}；人运行：python tools/task.py commit {card.id}")
    fb = feedback_file(card.id)
    if fb.exists():
        say(f"有未处理的人工反馈：{fb.relative_to(ROOT).as_posix()}")
        say(fb.read_text(encoding="utf-8").strip())


def cmd_ecc(args: argparse.Namespace) -> None:
    card = load_card(args.card)
    say(f"{card.id} {card.meta['title']}")
    for ln in ecc_lines(card):
        say(ln)


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    p = argparse.ArgumentParser(description="任务卡开发流程（见 docs/04-dev-workflow.md）")
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("start", help="① 从主分支创建子分支 ② 拉取到本地")
    s.add_argument("card")
    s.add_argument("--resume", action="store_true", help="远程分支已存在时继续之前的工作")
    s.set_defaults(func=cmd_start)
    s = sub.add_parser("test", help="④ 运行验收命令与全量检查")
    s.add_argument("card")
    s.set_defaults(func=cmd_test)
    s = sub.add_parser("finish", help="⑤ 更新卡片状态，暂存改动，生成提交前确认单（不提交）")
    s.add_argument("card")
    s.add_argument("-m", "--message", required=True, help="Conventional Commits 格式的提交信息（不含尾注）")
    s.add_argument("--summary", required=True, help="写入执行记录的“实际改动”")
    s.add_argument("--deviation", default="无", help="写入执行记录的“偏差”")
    s.add_argument("--reviews", default="", help="已运行的审查 agent，逗号分隔")
    s.set_defaults(func=cmd_finish)
    s = sub.add_parser("commit", help="⑥ 【仅限人】查看确认单，输入卡号后提交并推送")
    s.add_argument("card")
    s.add_argument("--no-push", action="store_true", help="只提交，不推送")
    s.add_argument("--pr", action="store_true", help="推送后用 gh 创建 PR")
    s.set_defaults(func=cmd_commit)
    s = sub.add_parser("reject", help="⑥ 不同意提交：撤回准备，原因反馈给 Agent")
    s.add_argument("card")
    s.add_argument("--reason", required=True)
    s.set_defaults(func=cmd_reject)
    s = sub.add_parser("block", help="标记阻塞并准备提交（同样需要人确认）")
    s.add_argument("card")
    s.add_argument("--reason", required=True)
    s.set_defaults(func=cmd_block)
    sub.add_parser("next", help="列出主分支上可以开工的卡").set_defaults(func=cmd_next)
    sub.add_parser("status", help="当前分支对应的卡、测试结果与待确认的提交").set_defaults(func=cmd_status)
    s = sub.add_parser("ecc", help="显示本卡要用的 ECC skill / agent / 命令")
    s.add_argument("card")
    s.set_defaults(func=cmd_ecc)
    args = p.parse_args()
    try:
        args.func(args)
    except TaskError as e:
        print(f"\n错误：{e}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\n已取消。", file=sys.stderr)
        return 130
    return 0


if __name__ == "__main__":
    sys.exit(main())
