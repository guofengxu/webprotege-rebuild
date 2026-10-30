#!/usr/bin/env python3
"""检查 tools/ecc-routing.json 中引用的 ECC skill / agent / 命令是否已安装到 Cursor。

用法：
    python tools/check_ecc.py                 列出缺失项，有缺失时退出码为 1
    python tools/check_ecc.py --print-install 另外打印安装命令
    python tools/check_ecc.py --fix-hooks     安装 ECC 后：提交闸门放回第一位，并把 rule 的 paths: 改成 Cursor 的 globs:

另外检查 .cursor/hooks.json：ECC 安装时会覆盖或合并这个文件，本仓库的提交闸门
（.cursor/hooks/commit_gate.py，见 docs/04-dev-workflow.md「人工确认」）必须是 beforeShellExecution 的第一个钩子，
因为 Cursor 的部分版本只执行同一事件的第一个钩子。

ECC 语言 rule 用 Claude Code 的 paths:；Cursor 只认 globs: 与 alwaysApply。不转换则打开匹配文件时不会自动挂上。

查找位置（按顺序）：
    项目：.cursor/skills/<名称>/SKILL.md、.cursor/agents/ecc-<名称>.md 或 <名称>.md、.cursor/commands/<名称>.md
    用户：~/.cursor/ 下的同名位置
    环境变量 ECC_HOME（ECC 仓库目录）不会被当作“已安装”，只用于打印安装命令。
"""
from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ROUTING = ROOT / "tools" / "ecc-routing.json"
BASES = [ROOT / ".cursor", Path.home() / ".cursor"]


def referenced() -> dict[str, set[str]]:
    r = json.loads(ROUTING.read_text(encoding="utf-8"))
    out = {"skills": set(), "agents": set(), "commands": set()}
    groups = list(r["workTypes"].values()) + list(r["extras"].values())
    for g in groups:
        out["skills"].update(g.get("skills", []))
        out["agents"].update(g.get("agents", []) + g.get("assistAgents", []))
        out["commands"].update(g.get("commands", []))
        of = g.get("onFailure", {})
        out["agents"].update(of.get("agents", []))
        out["commands"].update(of.get("commands", []))
    common = r["common"]
    out["skills"].update(common["commit"].get("skills", []))
    out["agents"].update(common["commit"].get("agents", []))
    out["commands"].update(common.get("afterPush", {}).get("commands", []))
    return out


def installed(kind: str, name: str) -> Path | None:
    name = name.lstrip("/")
    for base in BASES:
        if kind == "skills":
            cands = [base / "skills" / name / "SKILL.md"]
        elif kind == "agents":
            cands = [base / "agents" / f"ecc-{name}.md", base / "agents" / f"{name}.md"]
        else:
            cands = [base / "commands" / f"{name}.md", base / "commands" / f"ecc-{name}.md"]
        for c in cands:
            if c.exists():
                return c
    return None


def missing_items(names: dict[str, set[str]] | None = None) -> dict[str, list[str]]:
    names = names or referenced()
    return {k: sorted(n for n in v if installed(k, n) is None) for k, v in names.items()}


HOOKS_JSON = ROOT / ".cursor" / "hooks.json"
GATE_ENTRY = {
    "command": "bash .cursor/hooks/commit_gate.sh",
    "event": "beforeShellExecution",
    "matcher": "git|gh|task\\.py",
    "timeout": 10,
    "failClosed": True,
    "description": "webprotege-rebuild: 提交与推送必须由人确认（python tools/task.py commit），"
                   "Agent 不得执行 git commit / push / merge 等",
}


def gate_status() -> str:
    """ok / missing / not-first / no-file / bad-json"""
    if not HOOKS_JSON.exists():
        return "no-file"
    try:
        data = json.loads(HOOKS_JSON.read_text(encoding="utf-8"))
    except ValueError:
        return "bad-json"
    entries = data.get("hooks", {}).get("beforeShellExecution", [])
    idx = [i for i, e in enumerate(entries) if "commit_gate" in str(e.get("command", ""))]
    if not idx:
        return "missing"
    return "ok" if idx[0] == 0 else "not-first"


def fix_hooks() -> None:
    data = {"version": 1, "hooks": {}}
    if HOOKS_JSON.exists():
        data = json.loads(HOOKS_JSON.read_text(encoding="utf-8"))
    hooks = data.setdefault("hooks", {})
    entries = [e for e in hooks.get("beforeShellExecution", []) if "commit_gate" not in str(e.get("command", ""))]
    hooks["beforeShellExecution"] = [GATE_ENTRY] + entries
    data.setdefault("version", 1)
    HOOKS_JSON.parent.mkdir(exist_ok=True)
    with open(HOOKS_JSON, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(data, ensure_ascii=False, indent=2) + "\n")


RULES_DIR = ROOT / ".cursor" / "rules"
_FRONTMATTER = re.compile(r"\A---\n(.*?)\n---\n", re.DOTALL)
_PATHS_ITEM = re.compile(r"^[ \t]+-[ \t]+(.+?)\s*$")


def convert_rule_frontmatter(text: str) -> str | None:
    """把 Claude Code 的 paths: 转成 Cursor 的 globs:。无需转换时返回 None。"""
    m = _FRONTMATTER.match(text)
    if not m:
        return None
    fm = m.group(1)
    if re.search(r"^globs:", fm, re.M):
        return None
    if re.search(r"^alwaysApply:\s*true\b", fm, re.M):
        return None
    lines = fm.splitlines()
    paths_at = next((i for i, ln in enumerate(lines) if re.match(r"^paths:\s*$", ln)), None)
    if paths_at is None:
        return None
    globs: list[str] = []
    end = paths_at + 1
    while end < len(lines):
        item = _PATHS_ITEM.match(lines[end])
        if item is None:
            break
        raw = item.group(1)
        if len(raw) >= 2 and raw[0] == raw[-1] and raw[0] in "\"'":
            raw = raw[1:-1]
        if raw:
            globs.append(raw)
        end += 1
    if not globs:
        return None
    kept = lines[:paths_at] + lines[end:]
    globs_line = "globs: [" + ", ".join(json.dumps(g) for g in globs) + "]"
    insert_at = 1 if kept and kept[0].startswith("description:") else 0
    kept.insert(insert_at, globs_line)
    if not any(re.match(r"^alwaysApply:", ln) for ln in kept):
        kept.append("alwaysApply: false")
    return "---\n" + "\n".join(kept) + "\n---\n" + text[m.end() :]


def paths_rule_files(rules_dir: Path | None = None) -> list[Path]:
    d = rules_dir if rules_dir is not None else RULES_DIR
    if not d.is_dir():
        return []
    found: list[Path] = []
    for p in sorted(d.glob("*.mdc")):
        if convert_rule_frontmatter(p.read_text(encoding="utf-8")) is not None:
            found.append(p)
    return found


def fix_cursor_rules(rules_dir: Path | None = None) -> list[Path]:
    changed: list[Path] = []
    for p in paths_rule_files(rules_dir):
        new = convert_rule_frontmatter(p.read_text(encoding="utf-8"))
        if new is None:
            continue
        with open(p, "w", encoding="utf-8", newline="\n") as f:
            f.write(new)
        changed.append(p)
    return changed


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    refs = referenced()
    miss = missing_items(refs)
    total = sum(len(v) for v in refs.values())
    n_miss = sum(len(v) for v in miss.values())
    label = {"skills": "skill", "agents": "agent", "commands": "命令"}
    print(f"ECC 引用项共 {total} 个，已安装 {total - n_miss} 个，缺失 {n_miss} 个。")
    for k, v in miss.items():
        if v:
            print(f"  缺失的 {label[k]}：{', '.join(v)}")
    if "--fix-hooks" in sys.argv[1:]:
        fix_hooks()
        print("已把提交闸门放到 .cursor/hooks.json 的 beforeShellExecution 第一位。重启 Cursor 后生效。")
        rewritten = fix_cursor_rules()
        print(f"Cursor rule：已将 {len(rewritten)} 个 paths: 转成 globs:。")
    stale = paths_rule_files()
    if stale:
        print(
            f"Cursor rule：{len(stale)} 个仍用 Claude 的 paths:（Cursor 不自动挂载），"
            "运行 python tools/check_ecc.py --fix-hooks"
        )
    else:
        print("Cursor rule：frontmatter 已是 globs: / alwaysApply")
    gate = gate_status()
    gate_msg = {
        "ok": "提交闸门：已启用（.cursor/hooks.json 第一位）",
        "missing": "提交闸门：缺失（ECC 安装可能覆盖了 .cursor/hooks.json），运行 python tools/check_ecc.py --fix-hooks",
        "not-first": "提交闸门：不在第一位（Cursor 可能只执行第一个钩子），运行 python tools/check_ecc.py --fix-hooks",
        "no-file": "提交闸门：没有 .cursor/hooks.json，运行 python tools/check_ecc.py --fix-hooks",
        "bad-json": "提交闸门：.cursor/hooks.json 不是合法 JSON，请手工修复后运行 --fix-hooks",
    }[gate]
    print(gate_msg)
    if "--print-install" in sys.argv[1:]:
        r = json.loads(ROUTING.read_text(encoding="utf-8"))
        ecc_home = os.environ.get("ECC_HOME", r"<ECC 仓库目录>")
        print("\n在 webprotege-rebuild 根目录下执行（ECC 以当前目录为项目根；先用 --dry-run 确认写入位置）：")
        print(f"  {ecc_home}\\install.ps1 --target cursor --dry-run --modules {','.join(r['eccInstall']['modules'])}")
        print(f"  {ecc_home}\\install.ps1 --target cursor --modules {','.join(r['eccInstall']['modules'])}")
        print(f"  {ecc_home}\\install.ps1 --target cursor --skills {','.join(sorted(refs['skills']))}")
        print("macOS / Linux 把 install.ps1 换成 install.sh。")
        print("安装完成后运行：python tools/check_ecc.py --fix-hooks")
    return 1 if n_miss or gate != "ok" or stale else 0


if __name__ == "__main__":
    sys.exit(main())
