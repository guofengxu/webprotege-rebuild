#!/usr/bin/env python3
"""validate_names.py：任务卡分支与 Task 尾注支持一位或两位阶段号（p0 ~ p99）。"""
from __future__ import annotations

import unittest

import validate_names

CONFIRMED = "Confirmed-by: Reviewer <reviewer@example.com>"


class TaskBranchTest(unittest.TestCase):
    def test_extracts_card_id_from_one_and_two_digit_phase_branches(self) -> None:
        cases = {
            "feature/p1-03-change-minimiser": "P1-03",
            "docs/p7-01-manchester-parser": "P7-01",
            "docs/p10-01-multi-instance-poc": "P10-01",
            "feature/p99-99-last-card": "P99-99",
        }
        for branch, card_id in cases.items():
            with self.subTest(branch=branch):
                self.assertEqual(validate_names.task_id_from_branch(branch), card_id)
                self.assertEqual(validate_names.check_branch(branch), [])

    def test_other_branches_are_not_task_branches(self) -> None:
        for branch in ("docs/p6-block-planning-cards", "docs/p07-01-leading-zero",
                       "docs/p100-01-three-digits", "feature/p1-3-one-digit-number"):
            with self.subTest(branch=branch):
                self.assertIsNone(validate_names.task_id_from_branch(branch))


class TaskTrailerTest(unittest.TestCase):
    def test_two_digit_phase_trailer_matches_branch_card(self) -> None:
        msg = f"docs(tasks): plan multi-instance deployment\n\nTask: P10-01\n{CONFIRMED}\n"
        errors, _ = validate_names.check_commit(msg, branch="docs/p10-01-multi-instance-poc")
        self.assertEqual(errors, [])

    def test_trailer_for_another_card_is_rejected(self) -> None:
        msg = f"docs(tasks): plan multi-instance deployment\n\nTask: P1-01\n{CONFIRMED}\n"
        errors, _ = validate_names.check_commit(msg, branch="docs/p10-01-multi-instance-poc")
        self.assertTrue(any("does not match branch card P10-01" in e for e in errors), errors)


if __name__ == "__main__":
    unittest.main()
