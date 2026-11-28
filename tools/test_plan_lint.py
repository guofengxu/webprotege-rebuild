#!/usr/bin/env python3
"""plan_lint.py：卡号支持一位或两位阶段号（P0 ~ P99），阶段与卡号按数字排序；状态含 cancelled（取消）。"""
from __future__ import annotations

import unittest

import plan_lint


def make_card(card_id: str, module: str | None = None, status: str = "todo",
              deps: list[str] | None = None, body: str = "") -> plan_lint.Card:
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
        f"depends_on: [{', '.join(deps or [])}]\n"
        "---\n"
        f"# {card_id} 示例\n"
        f"{body}"
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


class CancelledStatusTest(unittest.TestCase):
    """人决定不再做的卡标为 cancelled（取消）：必须写明原因，不进入可开工与阻塞列表。"""

    CANCEL_SECTION = "\n## 取消\n- 原因：人决定不再做。\n"

    def status_errors(self, cards: list[plan_lint.Card]) -> list[str]:
        errors, _ = plan_lint.validate(cards)
        return [e for e in errors if "status" in e or "取消" in e or "依赖" in e]

    def test_cancelled_card_with_reason_section_passes(self) -> None:
        card = make_card("P6-47", status="cancelled", body=self.CANCEL_SECTION)
        self.assertEqual(self.status_errors([card]), [])

    def test_cancelled_card_requires_cancel_section(self) -> None:
        errors = self.status_errors([make_card("P6-47", status="cancelled")])
        self.assertTrue(any("状态为 cancelled，但缺少「取消」小节" in e for e in errors), errors)

    def test_open_card_must_not_depend_on_cancelled_card(self) -> None:
        cancelled = make_card("P6-47", status="cancelled", body=self.CANCEL_SECTION)
        for status in ("todo", "in_progress", "blocked", "done"):
            with self.subTest(status=status):
                dependent = make_card("P7-01", status=status, deps=["P6-47"])
                errors = self.status_errors([cancelled, dependent])
                self.assertTrue(any("依赖 P6-47 已取消" in e for e in errors), errors)

    def test_cancelled_card_may_depend_on_cancelled_card(self) -> None:
        first = make_card("P6-47", status="cancelled", body=self.CANCEL_SECTION)
        second = make_card("P7-01", status="cancelled", deps=["P6-47"], body=self.CANCEL_SECTION)
        self.assertEqual(self.status_errors([first, second]), [])

    def test_index_counts_and_labels_cancelled_cards(self) -> None:
        cards = [make_card("P6-01"), make_card("P6-02", status="blocked"), make_card("P6-47", status="cancelled")]
        text = plan_lint.render_index(cards, epics=[])
        self.assertIn("共 3 张卡：完成 0，进行中 0，阻塞 1，挂起 0，取消 1，待办 1。", text)
        self.assertIn("| 取消 |", text)
        self.assertNotIn("[P6-47](P6/P6-47-demo.md) 示例 P6-47", text)  # 不在可开工 / 阻塞中两个列表里

    def test_epic_only_phase_shows_cancelled_planning_card(self) -> None:
        planner = make_card("P6-47", module="tasks/P7", status="cancelled")
        text = plan_lint.render_index([make_card("P6-01"), planner], epics=["P6", "P7"])
        self.assertIn("[P6-47](P6/P6-47-demo.md)（取消）", text[text.index("\n## P7\n"):])

    def test_graph_styles_cancelled_cards(self) -> None:
        text = plan_lint.render_graph([make_card("P6-47", status="cancelled")])
        self.assertIn("classDef cancelled ", text)
        self.assertIn('P6_47["P6-47"]:::cancelled', text)


class SuspendedStatusTest(unittest.TestCase):
    """人决定暂停、等外部条件的卡标为 suspended（挂起）：写明恢复条件，单独列出，不进入可开工与阻塞列表。"""

    SUSPEND_SECTION = "\n## 挂起\n- 恢复条件：外部凭据就绪。\n"

    def status_errors(self, cards: list[plan_lint.Card]) -> list[str]:
        errors, _ = plan_lint.validate(cards)
        return [e for e in errors if "status" in e or "挂起" in e or "依赖" in e]

    def test_suspended_card_with_section_passes(self) -> None:
        card = make_card("P6-39", status="suspended", body=self.SUSPEND_SECTION)
        self.assertEqual(self.status_errors([card]), [])

    def test_suspended_card_requires_suspend_section(self) -> None:
        errors = self.status_errors([make_card("P6-39", status="suspended")])
        self.assertTrue(any("状态为 suspended，但缺少「挂起」小节" in e for e in errors), errors)

    def test_open_card_may_depend_on_suspended_card(self) -> None:
        suspended = make_card("P6-39", status="suspended", body=self.SUSPEND_SECTION)
        dependent = make_card("P6-40", deps=["P6-39"])
        self.assertEqual(self.status_errors([suspended, dependent]), [])

    def test_index_counts_and_lists_suspended_cards_separately(self) -> None:
        cards = [make_card("P6-01"), make_card("P6-02", status="blocked"), make_card("P6-39", status="suspended")]
        text = plan_lint.render_index(cards, epics=[])
        self.assertIn("共 3 张卡：完成 0，进行中 0，阻塞 1，挂起 1，取消 0，待办 1。", text)
        suspended_list = text[text.index("\n## 挂起中\n"):text.index("\n## P6\n")]
        self.assertIn("[P6-39](P6/P6-39-demo.md) 示例 P6-39", suspended_list)
        self.assertNotIn("P6-39", text[:text.index("\n## 挂起中\n")])  # 不在可开工 / 阻塞中两个列表里
        self.assertIn("| 挂起 |", text)

    def test_graph_styles_suspended_cards(self) -> None:
        text = plan_lint.render_graph([make_card("P6-39", status="suspended")])
        self.assertIn("classDef suspended ", text)
        self.assertIn('P6_39["P6-39"]:::suspended', text)


if __name__ == "__main__":
    unittest.main()
