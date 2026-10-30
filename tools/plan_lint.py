#!/usr/bin/env python3
"""校验 tasks/ 下的任务卡，并生成 tasks/INDEX.md 与 docs/03-plan/dependency-graph.md。

这两个文件纳入 Git（当前阶段单人维护，见 docs/04-dev-workflow.md 第 3 节），请勿手工编辑：
tools/task.py 的 finish / block 会重新生成它们，并与卡片一起提交。

用法：
    python tools/plan_lint.py                     校验并重写两个索引文件
    python tools/plan_lint.py --check             只校验，不写文件（tools/task.py test 使用）
    python tools/plan_lint.py --check --check-index   另外要求两个索引文件与卡片一致（CI 使用）

只依赖 Python 3.9+ 标准库。
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TASKS = ROOT / "tasks"
INDEX = TASKS / "INDEX.md"
GRAPH = ROOT / "docs" / "03-plan" / "dependency-graph.md"
LEGACY = ROOT.parent / "webprotege"
ROUTING = ROOT / "tools" / "ecc-routing.json"

ID_RE = re.compile(r"^P\d-\d{2}$")
REQUIRED_FIELDS = ["id", "title", "phase", "module", "type", "size", "work_type", "status", "depends_on"]
REQUIRED_SECTIONS = ["目标", "参考", "产出", "验收", "完成判据", "不在本卡范围"]
STATUSES = {"todo", "in_progress", "blocked", "done"}
TYPES = {"impl", "planning"}
SIZES = {"S", "M"}
BRANCH_TYPES = {"feature", "fix", "hotfix", "test", "refactor", "docs", "chore", "release"}
STATUS_LABEL = {"todo": "待办", "in_progress": "进行中", "blocked": "阻塞", "done": "完成"}
SUPERSEDED_MARK = "<!-- superseded"
LIST_FIELDS = {"depends_on", "ecc_extra"}


class Card:
    def __init__(self, path: Path, meta: dict, body: str):
        self.path = path
        self.meta = meta
        self.body = body

    @property
    def id(self) -> str:
        return self.meta.get("id", "")

    @property
    def deps(self) -> list[str]:
        return self.meta.get("depends_on", [])

    @property
    def status(self) -> str:
        return self.meta.get("status", "")

    @property
    def slug(self) -> str:
        stem = self.path.stem
        return stem[len(self.id) + 1:] if stem.startswith(self.id + "-") else stem

    def sections(self) -> list[str]:
        return re.findall(r"^## (.+?)\s*$", self.body, flags=re.M)

    def section(self, name: str) -> str:
        m = re.search(rf"^## {re.escape(name)}\s*\n(.*?)(?=^## |\Z)", self.body, flags=re.S | re.M)
        return m.group(1) if m else ""

    def accept_commands(self) -> list[str]:
        block = self.section("验收")
        cmds: list[str] = []
        for code in re.findall(r"```[a-zA-Z]*\n(.*?)```", block, flags=re.S):
            for line in code.splitlines():
                line = line.strip()
                if line and not line.startswith("#"):
                    cmds.append(line)
        return cmds


def is_superseded(text: str) -> bool:
    return text.lstrip().startswith(SUPERSEDED_MARK)


def parse_text(text: str, path: Path) -> Card:
    text = text.replace("\r\n", "\n")
    m = re.match(r"^---\n(.*?)\n---\n(.*)$", text, flags=re.S)
    if not m:
        raise ValueError("缺少 frontmatter")
    meta: dict = {}
    for line in m.group(1).splitlines():
        if not line.strip().startswith("title:"):
            line = line.split("#", 1)[0].rstrip()
        if not line.strip():
            continue
        key, _, value = line.partition(":")
        key, value = key.strip(), value.strip()
        if key in LIST_FIELDS:
            inner = value.strip("[]").strip()
            meta[key] = [v.strip() for v in inner.split(",") if v.strip()] if inner else []
        else:
            meta[key] = value
    meta.setdefault("ecc_extra", [])
    return Card(path, meta, m.group(2))


def parse(path: Path) -> Card:
    return parse_text(path.read_text(encoding="utf-8"), path)


def card_paths() -> list[Path]:
    return sorted(TASKS.glob("P*/P*-*.md"))


def load_cards() -> tuple[list[Card], list[str], list[str]]:
    errors: list[str] = []
    warnings: list[str] = []
    cards: list[Card] = []
    for path in card_paths():
        text = path.read_text(encoding="utf-8")
        if is_superseded(text):
            warnings.append(f"{path.relative_to(ROOT)} 已废弃，可以直接删除")
            continue
        try:
            cards.append(parse_text(text, path))
        except ValueError as e:
            errors.append(f"{path.relative_to(ROOT)}: {e}")
    return cards, errors, warnings


def load_routing() -> dict:
    return json.loads(ROUTING.read_text(encoding="utf-8"))


def validate(cards: list[Card]) -> tuple[list[str], list[str]]:
    errors: list[str] = []
    warnings: list[str] = []
    routing = load_routing()
    work_types = set(routing["workTypes"])
    extras = set(routing["extras"])
    by_id: dict[str, Card] = {}
    for c in cards:
        rel = c.path.relative_to(ROOT)
        for f in REQUIRED_FIELDS:
            if f not in c.meta:
                errors.append(f"{rel}: 缺少字段 {f}")
        if not ID_RE.match(c.id):
            errors.append(f"{rel}: id 格式应为 PX-NN，实际为 {c.id!r}")
        if not c.path.name.startswith(c.id + "-"):
            errors.append(f"{rel}: 文件名应以 {c.id}- 开头")
        if not re.fullmatch(r"[a-z0-9]+(-[a-z0-9]+)*", c.slug):
            errors.append(f"{rel}: 文件名中 id 之后的部分必须是小写 kebab-case（它会成为分支名的一部分）")
        if c.meta.get("phase") != c.id.split("-")[0]:
            errors.append(f"{rel}: phase 与 id 不一致")
        if c.path.parent.name != c.meta.get("phase"):
            errors.append(f"{rel}: 文件应位于 tasks/{c.meta.get('phase')}/")
        if c.status not in STATUSES:
            errors.append(f"{rel}: status 取值应为 {sorted(STATUSES)}")
        if c.meta.get("type") not in TYPES:
            errors.append(f"{rel}: type 取值应为 {sorted(TYPES)}")
        if c.meta.get("size") not in SIZES:
            errors.append(f"{rel}: size 取值应为 {sorted(SIZES)}（超过 M 的卡必须拆分）")
        if "work_type" in c.meta and c.meta["work_type"] not in work_types:
            errors.append(f"{rel}: work_type 取值应为 tools/ecc-routing.json 中的 {sorted(work_types)}")
        for e in c.meta.get("ecc_extra", []):
            if e not in extras:
                errors.append(f"{rel}: ecc_extra 中的 {e!r} 不在 tools/ecc-routing.json 的 extras 中")
        if "branch_type" in c.meta and c.meta["branch_type"] not in BRANCH_TYPES:
            errors.append(f"{rel}: branch_type 取值应为 {sorted(BRANCH_TYPES)}")
        secs = c.sections()
        for s in REQUIRED_SECTIONS:
            if s not in secs:
                errors.append(f"{rel}: 缺少小节「{s}」")
        if "验收" in secs and not c.accept_commands():
            errors.append(f"{rel}: 「验收」中至少要有一个命令代码块")
        if c.status == "done":
            # 以“人已”开头的判据由人在 task.py commit 时勾选，准备阶段允许暂未勾选
            if re.search(r"^- \[ \] (?!人已)", c.body, flags=re.M):
                errors.append(f"{rel}: 状态为 done，但还有未勾选的完成判据")
            if "执行记录" not in secs:
                errors.append(f"{rel}: 状态为 done，但缺少「执行记录」小节")
        if c.status == "blocked" and "阻塞" not in secs:
            errors.append(f"{rel}: 状态为 blocked，但缺少「阻塞」小节")
        if c.id in by_id:
            errors.append(f"{rel}: id {c.id} 与 {by_id[c.id].path.relative_to(ROOT)} 重复")
        by_id[c.id] = c

    for c in cards:
        for d in c.deps:
            if d not in by_id:
                errors.append(f"{c.path.relative_to(ROOT)}: 依赖 {d} 不存在")
            elif c.status == "done" and by_id[d].status != "done":
                errors.append(f"{c.path.relative_to(ROOT)}: 状态为 done，但依赖 {d} 尚未完成")

    state: dict[str, int] = {}

    def visit(cid: str, stack: list[str]) -> None:
        if state.get(cid) == 2 or cid not in by_id:
            return
        if state.get(cid) == 1:
            cycle = stack[stack.index(cid):] + [cid]
            errors.append("依赖成环：" + " → ".join(cycle))
            return
        state[cid] = 1
        for d in by_id[cid].deps:
            visit(d, stack + [cid])
        state[cid] = 2

    for cid in by_id:
        visit(cid, [])

    if LEGACY.is_dir():
        for c in cards:
            for ref in set(re.findall(r"`\.\./webprotege/([^`]+?)`", c.body)):
                if not (LEGACY / ref).exists():
                    warnings.append(f"{c.path.relative_to(ROOT)}: 旧工程中找不到 ../webprotege/{ref}")
    return errors, warnings


def ready(card: Card, by_id: dict[str, Card]) -> bool:
    return card.status == "todo" and all(by_id[d].status == "done" for d in card.deps if d in by_id)


def render_index(cards: list[Card]) -> str:
    by_id = {c.id: c for c in cards}
    phases = sorted({c.meta["phase"] for c in cards})
    counts = {s: sum(1 for c in cards if c.status == s) for s in STATUSES}
    lines = [
        "# 任务索引",
        "",
        "> 本文件由 `python tools/plan_lint.py` 生成并纳入 Git，请勿手工编辑；`tools/task.py finish` / `block` "
        "会自动更新。状态以各卡片文件为准，主分支上的进度也可以运行 `python tools/task.py next` 查看。",
        "",
        f"共 {len(cards)} 张卡：完成 {counts['done']}，进行中 {counts['in_progress']}，"
        f"阻塞 {counts['blocked']}，待办 {counts['todo']}。",
        "",
        "## 现在可以开工的卡（按本地文件计算）",
        "",
    ]
    ready_cards = [c for c in cards if ready(c, by_id)]
    if ready_cards:
        lines += [f"- [{c.id}]({c.path.relative_to(TASKS).as_posix()}) {c.meta['title']}" for c in ready_cards]
    else:
        lines.append("- 无")
    blocked = [c for c in cards if c.status == "blocked"]
    if blocked:
        lines += ["", "## 阻塞中", ""]
        lines += [f"- [{c.id}]({c.path.relative_to(TASKS).as_posix()}) {c.meta['title']}" for c in blocked]
    for ph in phases:
        lines += ["", f"## {ph}", "", "| ID | 标题 | 模块 | 类型 | 规模 | 状态 | 依赖 |", "|---|---|---|---|---|---|---|"]
        for c in [c for c in cards if c.meta["phase"] == ph]:
            deps = "、".join(c.deps) if c.deps else "—"
            st = STATUS_LABEL.get(c.status, c.status)
            if ready(c, by_id):
                st += "（可开工）"
            lines.append(
                f"| [{c.id}]({c.path.relative_to(TASKS).as_posix()}) | {c.meta['title']} | {c.meta['module']} "
                f"| {c.meta.get('work_type', '')} | {c.meta['size']} | {st} | {deps} |"
            )
    lines += ["", "各阶段的候选清单见 `P4/EPIC.md`、`P5/EPIC.md`、`P6/EPIC.md`。", ""]
    return "\n".join(lines)


def render_graph(cards: list[Card]) -> str:
    phases = sorted({c.meta["phase"] for c in cards})
    out = [
        "# 任务依赖图",
        "",
        "> 本文件由 `python tools/plan_lint.py` 生成并纳入 Git，请勿手工编辑。箭头表示“先完成 → 后开工”。",
        "",
        "```mermaid",
        "flowchart LR",
        "    classDef done fill:#d3f9d8,stroke:#2b8a3e",
        "    classDef in_progress fill:#fff3bf,stroke:#e67700",
        "    classDef blocked fill:#ffe3e3,stroke:#c92a2a",
        "    classDef todo fill:#f1f3f5,stroke:#868e96",
    ]
    for ph in phases:
        out.append(f"    subgraph {ph}")
        for c in [c for c in cards if c.meta["phase"] == ph]:
            nid = c.id.replace("-", "_")
            out.append(f'        {nid}["{c.id}"]:::{c.status}')
        out.append("    end")
    for c in cards:
        for d in c.deps:
            out.append(f"    {d.replace('-', '_')} --> {c.id.replace('-', '_')}")
    out += ["```", ""]
    return "\n".join(out)


def main() -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    check_only = "--check" in sys.argv[1:]
    cards, errors, warnings = load_cards()
    v_errors, v_warnings = validate(cards)
    errors += v_errors
    warnings += v_warnings
    for w in warnings:
        print(f"警告：{w}")
    if errors:
        for e in errors:
            print(f"错误：{e}")
        print(f"\n共 {len(errors)} 个错误。")
        return 1
    cards.sort(key=lambda c: c.id)
    outputs = ((INDEX, render_index(cards)), (GRAPH, render_graph(cards)))
    if check_only:
        if "--check-index" in sys.argv[1:]:
            stale = [p.relative_to(ROOT).as_posix() for p, text in outputs
                     if not p.exists() or p.read_text(encoding="utf-8").replace("\r\n", "\n") != text]
            if stale:
                print("错误：以下文件与任务卡不一致，请运行 python tools/plan_lint.py 重新生成后提交：" + "、".join(stale))
                return 1
        print(f"通过：{len(cards)} 张卡。")
        return 0
    for p, text in outputs:
        p.parent.mkdir(parents=True, exist_ok=True)
        with open(p, "w", encoding="utf-8", newline="\n") as f:
            f.write(text)
    print(f"通过：{len(cards)} 张卡，已更新 tasks/INDEX.md 与 docs/03-plan/dependency-graph.md。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
