#!/usr/bin/env python3
"""plan_lint.py：卡号支持一位或两位阶段号（P0 ~ P99），阶段与卡号按数字排序。"""
from __future__ import annotations

import unittest

import plan_lint


def make_card(card_id: str, module: str | None = None, status: str = "todo") -> plan_lint.Card:
    phase = card_id.split("-")[0]
    text = (
        "---\n"
        f"id: {card_id}\n"
        f"title: 示例 {card_id}\n"
        f"phase: {phase}\n"
        f"module: {module or 'tasks/' + phase}\n"
        "type: planning\n"
        "size: S\n"
        "work_type: planning\n"
        f"status: {status}\n"
        "depends_on: []\n"
        "---\n"
        f"# {card_id} 示例\n"
    )
    return plan_lint.parse_text(text, plan_lint.TASKS / phase / f"{card_id}-demo.md")


class CardIdFormatTest(unittest.TestCase):
    def test_accepts_one_and_two_digit_phases(self) -> None:
        for cid in ("P0-01", "P6-99", "P7-01", "P10-01", "P99-99"):
            with self.subTest(cid=cid):
                self.assertIsNotNone(plan_lint.ID_RE.match(cid))

    def test_rejects_leading_zero_and_wrong_widths(self) -> None:
        for cid in ("P07-01", "P00-01", "P100-01", "P1-1", "P1-001", "P-01", "p1-01"):
            with self.subTest(cid=cid):
                self.assertIsNone(plan_lint.ID_RE.match(cid))

    def test_two_digit_phase_card_passes_id_phase_and_path_checks(self) -> None:
        errors, _ = plan_lint.validate([make_card("P10-01")])
        related = [e for e in errors if "id 格式" in e or "phase" in e or "文件应位于" in e or "文件名" in e]
        self.assertEqual(related, [])


class SortOrderTest(unittest.TestCase):
    def test_id_sort_key_orders_phase_then_number_numerically(self) -> None:
        ids = ["P10-01", "P2-05", "P6-99", "P1-10", "P7-01", "P1-02"]
        self.assertEqual(sorted(ids, key=plan_lint.id_sort_key),
                         ["P1-02", "P1-10", "P2-05", "P6-99", "P7-01", "P10-01"])

    def test_phase_sort_key_orders_phases_numerically(self) -> None:
        self.assertEqual(sorted(["P10", "P2", "P9", "P0"], key=plan_lint.phase_sort_key),
                         ["P0", "P2", "P9", "P10"])

    def test_index_lists_p10_after_p9(self) -> None:
        text = plan_lint.render_index([make_card("P10-01"), make_card("P2-01"), make_card("P9-01")], epics=[])
        self.assertLess(text.index("\n## P2\n"), text.index("\n## P9\n"))
        self.assertLess(text.index("\n## P9\n"), text.index("\n## P10\n"))

    def test_graph_lists_p10_after_p9(self) -> None:
        text = plan_lint.render_graph([make_card("P10-01"), make_card("P9-01")])
        self.assertLess(text.index("subgraph P9"), text.index("subgraph P10"))


class EpicOnlyPhaseTest(unittest.TestCase):
    """有 EPIC.md、还没有卡片的阶段（例如由规划卡预留的 P7 ~ P10）也要列在 INDEX 中。"""

    def test_lists_phase_with_epic_but_no_cards_with_its_planning_card(self) -> None:
        planner = make_card("P6-47", module="tasks/P7", status="blocked")
        text = plan_lint.render_index([make_card("P6-01"), planner], epics=["P6", "P7"])
        section = text[text.index("\n## P7\n"):]
        self.assertIn("暂无卡片", section)
        self.assertIn("[P7/EPIC.md](P7/EPIC.md)", section)
        self.assertIn("[P6-47](P6/P6-47-demo.md)（阻塞）", section)
        self.assertNotIn("| ID |", section)

    def test_epic_only_phase_sits_in_numeric_order(self) -> None:
        text = plan_lint.render_index([make_card("P6-01"), make_card("P10-01")], epics=["P6", "P7", "P10"])
        self.assertLess(text.index("\n## P6\n"), text.index("\n## P7\n"))
        self.assertLess(text.index("\n## P7\n"), text.index("\n## P10\n"))

    def test_footer_lists_given_epics(self) -> None:
        text = plan_lint.render_index([make_card("P6-01")], epics=["P6", "P10"])
        self.assertIn("各阶段的候选清单见 `P6/EPIC.md`、`P10/EPIC.md`。", text)

    def test_epic_phases_include_reserved_new_phases(self) -> None:
        phases = plan_lint.epic_phases()
        for ph in ("P7", "P8", "P9", "P10"):
            self.assertIn(ph, phases)
        self.assertEqual(phases, sorted(phases, key=plan_lint.phase_sort_key))


if __name__ == "__main__":
    unittest.main()
