#!/usr/bin/env python3
"""CLI regression tests for bilingual release notes without network access."""

import copy
import json
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().with_name("generate_release_notes.rb")


def release(tag, draft=False, prerelease=False):
    return {"tag_name": tag, "draft": draft, "prerelease": prerelease}


def entry(version, date, *updates):
    return {"version": version, "date": date, "updates": list(updates)}


class GenerateReleaseNotesTest(unittest.TestCase):
    """Protect release selection, merged ordering, and invalid-input failures."""

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / "tools").mkdir()
        (self.root / "change_log").mkdir()
        shutil.copy2(SCRIPT, self.root / "tools/generate_release_notes.rb")
        self.english = [
            entry("3.6.6", "2026.10.18", "[feature][settings] current feature",
                  "[optimize][compile] current optimization"),
            entry("3.6.5", "2026.10.15", "[feature][compile] router feature",
                  "[bugfix][compile] compiler fix", "[bugfix][settings] settings fix"),
            entry("3.6.4", "2026.09.28", "[feature] native feature",
                  "[optimize] native optimization", "[bugfix] native fix"),
            entry("3.6.3", "2026.09.23", "[feature] reporting feature",
                  "[bugfix] source fix", "[bugfix] large library fix"),
            entry("3.6.2", "2026.09.22", "[feature] upload feature",
                  "[feature] defaults feature", "[bugfix] configuration fix"),
            entry("3.6.0", "2026.09.18", "[feature] first feature", "[feature] codegen feature"),
            entry("3.5.5", "2026.10.22", "[feature] excluded baseline"),
            entry("3.7.0", "2026.10.23", "[feature] excluded future"),
        ]
        self.chinese = copy.deepcopy(self.english)
        for item in self.chinese:
            item["updates"] = [update + " 中文" for update in item["updates"]]
        self.pages = [[release("3.5.5")]]

    def run_notes(self, version="3.6.6", args=None, raw_json=None):
        for suffix, data in (("", self.english), ("_cn", self.chinese)):
            (self.root / f"change_log/change_log_rc{suffix}.yaml").write_text(
                json.dumps(data, ensure_ascii=False), encoding="utf-8")
        releases_path = self.root / "releases.json"
        releases_path.write_text(
            raw_json if raw_json is not None else json.dumps(self.pages), encoding="utf-8")
        arguments = [version, str(releases_path)] if args is None else args
        return subprocess.run(["ruby", str(self.root / "tools/generate_release_notes.rb"),
                               *arguments], capture_output=True, text=True)

    def assert_failure(self, result, diagnostic):
        self.assertNotEqual(0, result.returncode)
        self.assertEqual("", result.stdout)
        self.assertIn(diagnostic, result.stderr)

    def test_merges_six_versions_into_categories_with_sixteen_updates_per_language(self):
        result = self.run_notes()
        self.assertEqual(0, result.returncode, result.stderr)
        english, chinese = result.stdout.split("## 中文\n\n")
        self.assertEqual(16, sum(line.startswith("- ") for line in english.splitlines()))
        self.assertEqual(16, sum(line.startswith("- ") for line in chinese.splitlines()))
        self.assertNotIn("excluded", result.stdout)
        self.assertNotRegex(result.stdout, r"3\.[567]\.\d+")
        self.assertIn("3.5.5 < version <= 3.6.6", result.stderr)
        self.assertEqual(["### Features", "### Optimizations", "### Bug fixes"],
                         [line for line in english.splitlines() if line.startswith("### ")])
        self.assertEqual(["### 新功能", "### 优化", "### 问题修复"],
                         [line for line in chinese.splitlines() if line.startswith("### ")])
        expected = [
            "[feature] first feature", "[feature] codegen feature", "[feature] upload feature",
            "[feature] defaults feature", "[feature] reporting feature", "[feature] native feature",
            "[feature][compile] router feature", "[feature][settings] current feature",
            "[optimize] native optimization", "[optimize][compile] current optimization",
            "[bugfix] configuration fix", "[bugfix] source fix", "[bugfix] large library fix",
            "[bugfix] native fix", "[bugfix][compile] compiler fix", "[bugfix][settings] settings fix",
        ]
        self.assertEqual(expected, [line[2:] for line in english.splitlines() if line.startswith("- ")])
        self.assertEqual([text + " 中文" for text in expected],
                         [line[2:] for line in chinese.splitlines() if line.startswith("- ")])

    def test_rerun_ignores_current_future_draft_and_prerelease_releases(self):
        self.pages[0] += [release("3.6.6"), release("3.7.0"), release("3.6.5", draft=True),
                          release("v3.6.4", prerelease=True), release("dev-latest")]
        result = self.run_notes()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("first feature", result.stdout)

    def test_selects_highest_lower_numeric_release_across_pages_and_v_prefixes(self):
        self.english = [entry("3.6.11", "2026.10.27", "[feature] selected"),
                        entry("3.6.10", "2026.10.26", "[feature] excluded baseline")]
        self.chinese = copy.deepcopy(self.english)
        self.pages = [[release("3.6.9")], [release("v3.6.10"), release("3.6.10")]]
        result = self.run_notes("v3.6.11")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("excluded baseline", result.stdout)
        self.assertIn("3.6.10 < version <= 3.6.11", result.stderr)

    def test_sorts_by_date_then_numeric_version_then_original_position(self):
        self.english = [entry("3.6.6", "2026.09.20", "[feature] higher same day"),
                        entry("3.6.5", "2026.10.01", "[feature] later date"),
                        entry("3.6.4", "2026.09.20", "[feature] lower first", "[feature] lower second")]
        self.chinese = copy.deepcopy(self.english)
        result = self.run_notes()
        self.assertEqual(0, result.returncode, result.stderr)
        lines = [line for line in result.stdout.split("## 中文")[0].splitlines() if line.startswith("- ")]
        self.assertEqual(["- [feature] lower first", "- [feature] lower second",
                          "- [feature] higher same day", "- [feature] later date"], lines)
        self.assertNotIn("### Optimizations", result.stdout)
        self.assertNotIn("### Bug fixes", result.stdout)

    def test_single_version_keeps_original_entries_and_duplicate_text(self):
        self.english = [entry("3.5.5", "2026.10.22", "[bugfix] fix", "[feature] feature",
                              "[feature] feature", "[optimize] optimization")]
        self.chinese = copy.deepcopy(self.english)
        self.pages = [[release("3.5.4")]]
        result = self.run_notes("3.5.5")
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(4, result.stdout.count("- [feature] feature"))
        self.assertNotIn("3.5.4", result.stdout)

    def test_ignores_outside_duplicates_and_historical_rc_sections(self):
        self.english += [copy.deepcopy(self.english[-2]),
                         entry("3.6.1-rc1", "invalid", "unsupported")]
        self.chinese += [copy.deepcopy(self.chinese[-2])]
        self.assertEqual(0, self.run_notes().returncode)

    def test_rejects_missing_arguments_and_invalid_current_version(self):
        for args in ([], ["3.6.6"], ["3.6.6", "unused", "extra"]):
            with self.subTest(args=args):
                self.assert_failure(self.run_notes(args=args), "Usage:")
        for version in ("3.06.6", "3.6.6-rc1", "not-a-version"):
            with self.subTest(version=version):
                self.assert_failure(self.run_notes(version), "Invalid release version")

    def test_rejects_missing_release_file(self):
        self.assert_failure(self.run_notes(args=["3.6.6", str(self.root / "missing.json")]), "missing.json")

    def test_rejects_invalid_release_json_and_page_structure(self):
        self.assert_failure(self.run_notes(raw_json="{"), "Invalid releases JSON")
        for pages in ({}, [release("3.5.5")], [None], [[None]]):
            with self.subTest(pages=pages):
                self.pages = pages
                self.assert_failure(self.run_notes(), "Invalid release")

    def test_rejects_missing_release_metadata(self):
        for field in ("tag_name", "draft", "prerelease"):
            with self.subTest(field=field):
                candidate = release("3.5.5")
                del candidate[field]
                self.pages = [[candidate]]
                self.assert_failure(self.run_notes(), "Invalid release metadata")

    def test_rejects_no_previous_formal_release(self):
        self.pages = [[release("3.6.6"), release("3.7.0"), release("3.5.5", prerelease=True)]]
        self.assert_failure(self.run_notes(), "No previous formal release")

    def test_requires_current_version_in_both_languages(self):
        for language in ("english", "chinese"):
            with self.subTest(language=language):
                original = getattr(self, language)
                setattr(self, language, original[1:])
                self.assert_failure(self.run_notes(), "Missing release 3.6.6")
                setattr(self, language, original)

    def test_rejects_duplicate_selected_version_in_either_language(self):
        for language in ("english", "chinese"):
            with self.subTest(language=language):
                data = getattr(self, language)
                data.append(copy.deepcopy(data[1]))
                self.assert_failure(self.run_notes(), "Duplicate release 3.6.5")
                data.pop()

    def test_rejects_different_selected_version_sets(self):
        self.chinese.pop(1)
        self.assert_failure(self.run_notes(), "version sets do not match")

    def test_rejects_invalid_updates(self):
        for updates in ([], None, [""], [12], ["[docs] unsupported"], ["no category"]):
            with self.subTest(updates=updates):
                self.english[1]["updates"] = updates
                self.assert_failure(self.run_notes(), "updates")

    def test_rejects_invalid_dates(self):
        for date in ("2026.02.30", "2026.2.01", "not-a-date", None):
            with self.subTest(date=date):
                self.english[1]["date"] = date
                self.assert_failure(self.run_notes(), "Invalid date for release 3.6.5")

    def test_rejects_bilingual_date_count_and_category_mismatches(self):
        for field, value, diagnostic in (
                ("date", "2026.10.16", "dates do not match"),
                ("updates", ["[feature] fewer"], "update counts do not match"),
                ("updates", ["[bugfix] wrong category", "[bugfix] fix", "[bugfix] fix"],
                 "update categories do not match")):
            with self.subTest(diagnostic=diagnostic):
                original = self.chinese[1][field]
                self.chinese[1][field] = value
                self.assert_failure(self.run_notes(), diagnostic)
                self.chinese[1][field] = original

    def test_rejects_invalid_changelog_structure(self):
        self.english = {"version": "3.6.6"}
        self.assert_failure(self.run_notes(), "Invalid changelog format")

    def test_rejects_malformed_yaml_without_partial_notes(self):
        self.run_notes()
        (self.root / "change_log/change_log_rc_cn.yaml").write_text("- version: [", encoding="utf-8")
        result = subprocess.run(
            ["ruby", str(self.root / "tools/generate_release_notes.rb"), "3.6.6",
             str(self.root / "releases.json")], capture_output=True, text=True)
        self.assert_failure(result, "Invalid changelog format")


if __name__ == "__main__":
    unittest.main()
