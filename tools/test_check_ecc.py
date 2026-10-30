#!/usr/bin/env python3
"""check_ecc.py：Cursor rule 的 paths: → globs: 转换。"""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

import check_ecc


class ConvertRuleFrontmatterTest(unittest.TestCase):
    def test_converts_single_path_list_to_globs(self) -> None:
        src = (
            "---\n"
            "paths:\n"
            '  - "**/*.java"\n'
            "---\n"
            "# Java Testing\n"
        )
        got = check_ecc.convert_rule_frontmatter(src)
        self.assertEqual(
            got,
            (
                "---\n"
                'globs: ["**/*.java"]\n'
                "alwaysApply: false\n"
                "---\n"
                "# Java Testing\n"
            ),
        )

    def test_converts_multiple_paths_preserving_order(self) -> None:
        src = (
            "---\n"
            "paths:\n"
            '  - "**/*.css"\n'
            '  - "**/*.vue"\n'
            "---\n"
            "body\n"
        )
        got = check_ecc.convert_rule_frontmatter(src)
        self.assertIsNotNone(got)
        assert got is not None
        self.assertIn('globs: ["**/*.css", "**/*.vue"]', got)
        self.assertTrue(got.endswith("body\n"))
        self.assertNotIn("paths:", got)

    def test_keeps_description_and_adds_always_apply(self) -> None:
        src = (
            "---\n"
            'description: "Java patterns"\n'
            "paths:\n"
            '  - "**/*.java"\n'
            "---\n"
            "x\n"
        )
        got = check_ecc.convert_rule_frontmatter(src)
        self.assertEqual(
            got,
            (
                "---\n"
                'description: "Java patterns"\n'
                'globs: ["**/*.java"]\n'
                "alwaysApply: false\n"
                "---\n"
                "x\n"
            ),
        )

    def test_leaves_existing_globs_unchanged(self) -> None:
        src = (
            "---\n"
            'description: "Go patterns extending common rules"\n'
            'globs: ["**/*.go", "**/go.mod"]\n'
            "alwaysApply: false\n"
            "---\n"
            "# Go\n"
        )
        self.assertIsNone(check_ecc.convert_rule_frontmatter(src))

    def test_leaves_always_apply_true_unchanged(self) -> None:
        src = (
            "---\n"
            "description: 架构护栏\n"
            "alwaysApply: true\n"
            "---\n"
            "- wp-kernel\n"
        )
        self.assertIsNone(check_ecc.convert_rule_frontmatter(src))

    def test_leaves_missing_frontmatter_unchanged(self) -> None:
        self.assertIsNone(check_ecc.convert_rule_frontmatter("# Code Review\n"))

    def test_second_pass_is_noop(self) -> None:
        src = (
            "---\n"
            "paths:\n"
            '  - "**/*.ts"\n'
            "---\n"
            "body\n"
        )
        once = check_ecc.convert_rule_frontmatter(src)
        self.assertIsNotNone(once)
        assert once is not None
        self.assertIsNone(check_ecc.convert_rule_frontmatter(once))


class FixCursorRulesTest(unittest.TestCase):
    def test_rewrites_only_paths_rules_in_directory(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            (d / "java-testing.mdc").write_text(
                "---\npaths:\n  - \"**/*.java\"\n---\n# Java\n",
                encoding="utf-8",
            )
            (d / "golang-patterns.mdc").write_text(
                "---\ndescription: go\nglobs: [\"**/*.go\"]\nalwaysApply: false\n---\n# Go\n",
                encoding="utf-8",
            )
            (d / "common-code-review.mdc").write_text(
                "# Code Review\n",
                encoding="utf-8",
            )
            changed = check_ecc.fix_cursor_rules(d)
            self.assertEqual([p.name for p in changed], ["java-testing.mdc"])
            java = (d / "java-testing.mdc").read_text(encoding="utf-8")
            self.assertEqual(
                java,
                "---\nglobs: [\"**/*.java\"]\nalwaysApply: false\n---\n# Java\n",
            )
            self.assertIn("globs:", (d / "golang-patterns.mdc").read_text(encoding="utf-8"))
            self.assertEqual(
                (d / "common-code-review.mdc").read_text(encoding="utf-8"),
                "# Code Review\n",
            )
            self.assertEqual(check_ecc.paths_rule_files(d), [])


if __name__ == "__main__":
    unittest.main()
