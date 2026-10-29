#!/usr/bin/env python3
"""仓库与开发环境初始化（只能由人在自己的终端运行，Agent 不得执行 init / protect）。

    python tools/repo_setup.py init --gh-create guofengxu/webprotege-rebuild [--public]
    python tools/repo_setup.py init --remote https://github.com/<账号>/webprotege-rebuild.git
        维护者执行一次：把当前目录建成 Git 仓库，作为基线提交推送到 GitHub 的 main。

    python tools/repo_setup.py protect
        维护者执行：按 .github/branch-protection.json 设置 main 的分支保护（需要 gh 且已登录）。

    python tools/repo_setup.py dev
        每位开发者克隆仓库后执行一次：检查工具链与 Git 身份，启用仓库自带的 Git 钩子，检查 ECC 与提交闸门。
        只改本仓库的 git config（core.hooksPath），不改其它任何东西；可以重复运行。

只依赖 Python 3.9+ 标准库。
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MAIN = os.environ.get("WP_MAIN_BRANCH", "main")
BASELINE_MESSAGE = "docs: add rebuild planning, task cards and development standards"
EXECUTABLES = (".githooks/commit-msg", ".githooks/pre-push", ".cursor/hooks/commit_gate.py")
# .github/ 下的文件（PR 模板、CODEOWNERS、分支保护配置）的初始版本放在这里，init 时复制到 .github/。
# 建仓之后以 .github/ 中的文件为准，修改 .github/ 即可，不必同步回模板。
GITHUB_TEMPLATES = ROOT / "tools" / "repo-templates" / "github"


class SetupError(Exception):
    pass


def say(msg: str = "") -> None:
    print(msg, flush=True)


def run(*args: str, check: bool = True, capture: bool = True) -> subprocess.CompletedProcess:
    proc = subprocess.run(list(args), cwd=ROOT, text=True, encoding="utf-8", errors="replace",
                          capture_output=capture)
    if check and proc.returncode != 0:
        raise SetupError(f"{' '.join(args)} 失败：\n{(proc.stderr or proc.stdout or '').strip()}")
    return proc


def git(*args: str, check: bool = True) -> str:
    return run("git", *args, check=check).stdout.strip()


def is_repo() -> bool:
    return (ROOT / ".git").exists()


def require_human_terminal() -> None:
    if not (sys.stdin.isatty() and sys.stdout.isatty()):
        raise SetupError("本命令只能由人在交互式终端中运行。Agent 不得执行。")


def confirm(prompt: str) -> bool:
    ans = input(f"{prompt} [y/N] ").strip().lower()
    return ans in ("y", "yes")


def superseded_files() -> list[Path]:
    out = []
    for p in ROOT.rglob("*.md"):
        if ".git" in p.parts or "node_modules" in p.parts:
            continue
        try:
            head = p.read_text(encoding="utf-8")[:40]
        except (OSError, UnicodeDecodeError):
            continue
        if head.lstrip().startswith("<!-- superseded"):
            out.append(p)
    return sorted(out)


def install_github_templates() -> list[str]:
    created = []
    if not GITHUB_TEMPLATES.is_dir():
        return created
    target = ROOT / ".github"
    target.mkdir(exist_ok=True)
    for src in sorted(GITHUB_TEMPLATES.iterdir()):
        dst = target / src.name
        if src.is_file() and not dst.exists():
            dst.write_bytes(src.read_bytes())
            created.append(f".github/{src.name}")
    return created


def identity() -> tuple[str, str]:
    return git("config", "user.name", check=False), git("config", "user.email", check=False)


def enable_hooks() -> None:
    git("config", "core.hooksPath", ".githooks")
    if os.name != "nt":
        for rel in EXECUTABLES:
            p = ROOT / rel
            if p.exists():
                p.chmod(0o755)


# ---------------------------------------------------------------- init

def cmd_init(args: argparse.Namespace) -> None:
    require_human_terminal()
    if not shutil.which("git"):
        raise SetupError("找不到 git。请先安装 Git for Windows。")
    if is_repo() and git("rev-parse", "--verify", "HEAD", check=False):
        raise SetupError("当前目录已经是有提交的 Git 仓库，不需要 init。同事请直接 git clone，然后运行 dev。")
    name, email = identity()
    if not name or not email:
        raise SetupError('请先设置提交身份：git config --global user.name "你的名字"；'
                         'git config --global user.email "你的邮箱"')
    if not (args.remote or args.gh_create):
        raise SetupError("请指定 --gh-create <账号>/<仓库名>（用 gh 新建仓库）或 --remote <已建好的空仓库地址>。")
    if args.gh_create and not shutil.which("gh"):
        raise SetupError("找不到 gh。请安装 GitHub CLI 并运行 gh auth login，或先在网页上建空仓库后改用 --remote。")

    stubs = superseded_files()
    say(f"仓库目录：{ROOT}")
    say(f"提交身份：{name} <{email}>")
    say(f"远程：{args.remote or ('新建 ' + args.gh_create + ('（公开）' if args.public else '（私有）'))}")
    if stubs:
        say("以下文件是规划改版留下的空壳（内容只有 <!-- superseded -->），将被删除：")
        for p in stubs:
            say(f"  {p.relative_to(ROOT).as_posix()}")
    if not confirm("确认初始化并推送基线到 GitHub？"):
        say("已取消。")
        return

    for p in stubs:
        p.unlink()
    for rel in install_github_templates():
        say(f"已生成 {rel}")
    if not is_repo():
        git("init", "-b", MAIN)
    git("add", "-A")
    for rel in EXECUTABLES:
        if (ROOT / rel).exists():
            git("update-index", "--chmod=+x", rel)
    files = git("diff", "--cached", "--name-only").splitlines()
    say(f"暂存 {len(files)} 个文件")
    # 基线提交在 main 上，由人执行；此时尚未启用 .githooks，pre-push 不会拦截推送 main。
    git("commit", "-q", "-m", BASELINE_MESSAGE, "-m", f"Confirmed-by: {name} <{email}>")
    say(f"已创建基线提交 {git('rev-parse', '--short', 'HEAD')}")

    if args.gh_create:
        vis = "--public" if args.public else "--private"
        run("gh", "repo", "create", args.gh_create, vis, "--source", ".", "--remote", "origin",
            "--description", "WebProtégé rebuild: planning, task cards and AI-agent development standards")
        say(f"已在 GitHub 上创建 {args.gh_create}")
    else:
        if git("remote", "get-url", "origin", check=False):
            git("remote", "set-url", "origin", args.remote)
        else:
            git("remote", "add", "origin", args.remote)
        if git("ls-remote", "--heads", "origin", check=False):
            raise SetupError("远程仓库不是空的。请换一个新建的空仓库（不要勾选自动生成 README）。")
    git("push", "-u", "origin", MAIN)
    say(f"已推送到 origin/{MAIN}")
    enable_hooks()
    say("已启用仓库自带的 Git 钩子（core.hooksPath=.githooks）")
    say("\n下一步：")
    say("  1. python tools/repo_setup.py protect     设置 main 的分支保护")
    say("  2. 在 GitHub 仓库 Settings → Collaborators 中邀请同事（或把仓库放进组织并授予团队权限）")
    say("  3. 按 CONTRIBUTING.md 安装 ECC，运行 python tools/check_ecc.py --fix-hooks")
    say("  4. 把 CONTRIBUTING.md 发给同事")


# ---------------------------------------------------------------- protect

def repo_slug() -> str:
    url = git("remote", "get-url", "origin")
    m = re.search(r"github\.com[:/]([^/]+/[^/.]+?)(?:\.git)?$", url)
    if not m:
        raise SetupError(f"无法从远程地址解析 GitHub 仓库：{url}")
    return m.group(1)


def cmd_protect(args: argparse.Namespace) -> None:
    require_human_terminal()
    if not shutil.which("gh"):
        raise SetupError("找不到 gh。请安装 GitHub CLI 并运行 gh auth login；或在网页 Settings → Branches 中按 "
                         ".github/branch-protection.json 手工设置。")
    cfg = ROOT / ".github" / "branch-protection.json"
    if not cfg.exists():
        cfg = GITHUB_TEMPLATES / "branch-protection.json"
    payload = json.loads(cfg.read_text(encoding="utf-8"))
    payload.pop("_comment", None)
    slug = repo_slug()
    say(f"将为 {slug} 的 {MAIN} 设置分支保护：")
    say(json.dumps(payload, ensure_ascii=False, indent=2))
    if not confirm("确认？"):
        say("已取消。")
        return
    proc = subprocess.run(["gh", "api", "-X", "PUT", f"repos/{slug}/branches/{MAIN}/protection", "--input", "-"],
                          cwd=ROOT, input=json.dumps(payload), text=True, capture_output=True,
                          encoding="utf-8", errors="replace")
    if proc.returncode != 0:
        err = (proc.stderr or proc.stdout).strip()
        hint = ""
        if "Upgrade" in err or "403" in err:
            hint = ("\n提示：GitHub 免费账户的私有仓库不支持分支保护。可以把仓库放进 Team 计划的组织、"
                    "升级账户，或改为公开仓库。没有分支保护时，CI 与 PR 评审只能靠大家自觉遵守。")
        raise SetupError(f"设置失败：{err}{hint}")
    say(f"已设置 {slug} 的 {MAIN} 分支保护。")


# ---------------------------------------------------------------- dev

def version_of(cmd: list[str]) -> str | None:
    exe = shutil.which(cmd[0])
    if not exe:
        return None
    try:
        out = subprocess.run([exe, *cmd[1:]], capture_output=True, text=True, timeout=20,
                             encoding="utf-8", errors="replace")
        lines = [ln for ln in ((out.stdout or "") + "\n" + (out.stderr or "")).splitlines()
                 if ln.strip() and not ln.startswith("Picked up")]
        return lines[0].strip()
    except (OSError, subprocess.TimeoutExpired, IndexError):
        return None


def cmd_dev(args: argparse.Namespace) -> None:
    problems: list[str] = []
    warnings: list[str] = []

    def check(label: str, ok: bool, detail: str, required: bool = True) -> None:
        mark = "OK  " if ok else ("缺失" if required else "提示")
        say(f"  [{mark}] {label}：{detail}")
        if not ok:
            (problems if required else warnings).append(label)

    say("工具链")
    check("Python 3.9+", sys.version_info >= (3, 9), sys.version.split()[0])
    check("Cursor 钩子使用的 python 命令", bool(shutil.which("python")),
          shutil.which("python") or "PATH 中没有 python（commit_gate 钩子会失败，并拒绝 Agent 的全部 git 命令）")
    check("git", bool(shutil.which("git")), version_of(["git", "--version"]) or "未安装")
    sys.path.insert(0, str(ROOT / "tools"))
    try:
        import task  # noqa: E402
        check("bash（执行验收命令）", True, task.find_bash())
    except Exception as e:  # noqa: BLE001
        check("bash（执行验收命令）", False, str(e))
    check("gh（可选，用于创建 PR）", bool(shutil.which("gh")), version_of(["gh", "--version"]) or "未安装", False)
    check("JDK（P0-01 起需要 25）", bool(shutil.which("java")), version_of(["java", "-version"]) or "未安装", False)
    check("Docker（P0-04 起）", bool(shutil.which("docker")), version_of(["docker", "--version"]) or "未安装", False)
    check("Node.js（P3-27 起需要 24）", bool(shutil.which("node")), version_of(["node", "--version"]) or "未安装", False)
    check("pnpm（P3-27 起）", bool(shutil.which("pnpm")), version_of(["pnpm", "--version"]) or "未安装", False)

    say("\nGit")
    if not is_repo():
        raise SetupError("当前目录不是 Git 仓库。请用 git clone 获取仓库后再运行。")
    name, email = identity()
    check("提交身份（写入 Confirmed-by）", bool(name and email),
          f"{name} <{email}>" if name and email else '请运行 git config --global user.name "…" 与 user.email "…"')
    origin = git("remote", "get-url", "origin", check=False)
    check("远程 origin", bool(origin), origin or "未设置")
    enable_hooks()
    check("仓库 Git 钩子", git("config", "core.hooksPath", check=False) == ".githooks", "core.hooksPath=.githooks")
    if os.name == "nt":
        crlf = git("config", "core.autocrlf", check=False)
        check("换行符", crlf in ("", "false", "input"),
              f"core.autocrlf={crlf or '未设置'}（仓库由 .gitattributes 统一为 LF，建议 git config core.autocrlf false）",
              False)

    say("\nCursor 与 ECC")
    import check_ecc  # noqa: E402
    gate = check_ecc.gate_status()
    check("提交闸门（.cursor/hooks.json 第一位）", gate == "ok",
          "已启用" if gate == "ok" else f"{gate}：运行 python tools/check_ecc.py --fix-hooks 后重启 Cursor")
    miss = check_ecc.missing_items()
    n_miss = sum(len(v) for v in miss.values())
    check("ECC skill / agent / 命令", n_miss == 0,
          "齐全" if n_miss == 0 else f"缺少 {n_miss} 项：运行 python tools/check_ecc.py --print-install", False)

    say("")
    if problems:
        say("需要先处理：" + "、".join(problems))
        raise SetupError("开发环境检查未通过。")
    say("开发环境检查通过" + (f"（另有 {len(warnings)} 条提示，按需处理）" if warnings else "") + "。")
    say("下一步：python tools/task.py next")


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("init", help="【维护者，一次】建仓库并推送基线")
    g = s.add_mutually_exclusive_group()
    g.add_argument("--gh-create", metavar="OWNER/NAME", help="用 gh 在 GitHub 上新建仓库")
    g.add_argument("--remote", metavar="URL", help="已在 GitHub 上建好的空仓库地址")
    s.add_argument("--public", action="store_true", help="与 --gh-create 一起使用：建公开仓库（默认私有）")
    s.set_defaults(func=cmd_init)
    sub.add_parser("protect", help="【维护者】设置 main 的分支保护").set_defaults(func=cmd_protect)
    sub.add_parser("dev", help="【每位开发者】检查并配置本机开发环境").set_defaults(func=cmd_dev)
    args = p.parse_args()
    try:
        args.func(args)
    except SetupError as e:
        print(f"\n错误：{e}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\n已取消。", file=sys.stderr)
        return 130
    return 0


if __name__ == "__main__":
    sys.exit(main())
