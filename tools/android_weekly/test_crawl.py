#!/usr/bin/env python3
"""Unit tests for the Android Weekly crawler."""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from tools.android_weekly.crawl import Entry, Issue, canonical_project_url, discover_issues, extract_open_source_projects, harmonyos_interview_data, parse_issue, render_site, select_highlights, write_failures


ARCHIVE = '<a href="/issues/issue-2">Issue #2</a><a href="/issues/issue-1">Issue #1</a>'
ISSUE = """<h2>Issue #744</h2><small>September 13th, 2026</small>
<span>Articles &amp; Tutorials</span><td class="editor-text content-text">
<a href="https://example.test/compose" style="font-size: 16px">Compose performance guide</a>
<div>Practical Android rendering guidance.</div></td>
<span>Libraries &amp; Code</span><td class="editor-text content-text">
<a href="https://example.test/kotlin" style="font-size: 16px">Kotlin security toolkit</a>
<div>A library for safe Android clients.</div></td>"""
LEGACY_ISSUE = """<h2>Issue #300</h2><div class="issue"><h2>Articles &amp; Tutorials</h2>
<a class="article-headline" href="https://example.test/legacy">Legacy Compose guide</a>
<p>Older issue summary.</p></div></div><div class="col-40">"""
EARLY_ISSUE = """<h2>Issue #1</h2><div class="issue"><h2>Articles and Tutorials</h2><p>
<a href="https://example.test/early" style="color: #336699">https://example.test/early</a>
Early Android testing tutorial.</p></div></section>"""


class AndroidWeeklyCrawlerTest(unittest.TestCase):
    def test_discover_issues_orders_unique_numbers(self) -> None:
        self.assertEqual([(1, "https://androidweekly.net/issues/issue-1"), (2, "https://androidweekly.net/issues/issue-2")], discover_issues(ARCHIVE))

    def test_canonical_project_url_rejects_non_repository_pages(self) -> None:
        self.assertEqual(("https://github.com/acme/widget", "GitHub"), canonical_project_url("https://github.com/acme/widget?utm_source=newsletter#read"))
        self.assertIsNone(canonical_project_url("https://github.com/acme/widget/issues/1"))

    def test_extract_open_source_projects_keeps_occurrences_and_score_reason(self) -> None:
        entry = Entry("Compose Widget", "https://github.com/acme/widget", "Android Compose UI library", "Libraries")
        projects = extract_open_source_projects([Issue(744, "issue", "Issue #744", "", [entry], [entry])])
        self.assertEqual(1, len(projects))
        self.assertEqual("GitHub", projects[0].platform)
        self.assertEqual("744", projects[0].occurrences[0]["issue"])
        self.assertIn("明确仓库链接", projects[0].score_reason)

    def test_parse_issue_keeps_sections_summaries_and_sources(self) -> None:
        issue = parse_issue(744, "https://androidweekly.net/issues/issue-744", ISSUE)
        self.assertEqual("Issue #744", issue.title)
        self.assertEqual(2, len(issue.entries))
        self.assertEqual("Articles & Tutorials", issue.entries[0].section)
        self.assertIn("Practical Android", issue.entries[0].summary)
        self.assertEqual("https://example.test/kotlin", issue.entries[1].url)

    def test_highlights_prioritise_relevant_topics(self) -> None:
        entries = [Entry("General news", "https://example.test/a", "", "Articles"), Entry("Kotlin security release", "https://example.test/b", "Android tooling", "Tools")]
        highlights = select_highlights(entries)
        self.assertEqual("Kotlin security release", highlights[0].title)
        self.assertIn("security", highlights[0].topics or [])
        self.assertEqual(4, len(highlights[0].dimensions or {}))
        self.assertTrue(highlights[0].score_reason)
        self.assertGreaterEqual(highlights[0].score, 1)
        self.assertLessEqual(highlights[0].score, 10)

    def test_parse_issue_supports_legacy_article_headlines(self) -> None:
        issue = parse_issue(300, "https://androidweekly.net/issues/issue-300", LEGACY_ISSUE)
        self.assertEqual(1, len(issue.entries))
        self.assertEqual("Legacy Compose guide", issue.entries[0].title)
        self.assertEqual("Articles & Tutorials", issue.entries[0].section)

    def test_parse_issue_supports_earliest_url_only_entries(self) -> None:
        issue = parse_issue(1, "https://androidweekly.net/issues/issue-1", EARLY_ISSUE)
        self.assertEqual("https://example.test/early", issue.entries[0].title)
        self.assertEqual("https://example.test/early", issue.entries[0].url)

    def test_render_site_creates_index_and_issue_page(self) -> None:
        entry = Entry("Compose guide", "https://example.test/guide", "A summary", "Articles", 3, ["compose"])
        issue = Issue(744, "https://androidweekly.net/issues/issue-744", "Issue #744", "September 13th, 2026", [entry], [entry])
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            render_site([issue], output)
            self.assertIn("Issue #744", (output / "index.html").read_text(encoding="utf-8"))
            detail = (output / "issues" / "issue-744.html").read_text(encoding="utf-8")
            self.assertIn("https://example.test/guide", detail)
            self.assertIn("Topics: compose", detail)
            self.assertIn("综合评分", detail)
            self.assertIn("interview-template-zh.html", (output / "index.html").read_text(encoding="utf-8"))
            self.assertIn("Android 面试项目案例", (output / "interview-template-zh.html").read_text(encoding="utf-8"))
            library = (output / "interview-case-library-zh.html").read_text(encoding="utf-8")
            self.assertIn("Flutter/C++ 外部源码增量构建", library)
            self.assertIn("项目代码依据", library)
            self.assertIn("ExternalBuildCompiler.kt", library)
            intelligence = (output / "opensource-projects-zh.html").read_text(encoding="utf-8")
            self.assertIn("开源项目情报库", intelligence)
            self.assertTrue((output / "opensource-projects.json").exists())
            harmonyos = (output / "harmonyos-interview-zh.html").read_text(encoding="utf-8")
            self.assertIn("鸿蒙中高级面试题库", harmonyos)
            self.assertIn('id="search"', harmonyos)
            self.assertIn("Android 开发者快速对照", harmonyos)
            self.assertIn("60 分钟模拟面试", harmonyos)
            self.assertIn("开源源码题", harmonyos)
            for anchor in ("overview", "questions", "scenarios", "opensource", "android-compare", "mock-interview", "sources"):
                self.assertIn('id="%s"' % anchor, harmonyos)
            self.assertIn("ArkUI ACE Engine", harmonyos)
            self.assertIn("不搜索项目卡", harmonyos)
            self.assertIn("harmonyos-interview-zh.html", (output / "index.html").read_text(encoding="utf-8"))
            self.assertTrue((output / "harmonyos-research-manifest.json").exists())
            compatibility_page = (output / "harmonyos-opensource-zh.html").read_text(encoding="utf-8")
            self.assertIn("harmonyos-interview-zh.html#opensource", compatibility_page)
            self.assertTrue((output / "harmonyos-opensource.json").exists())

    def test_harmonyos_library_has_required_question_structure(self) -> None:
        data = harmonyos_interview_data()
        self.assertGreaterEqual(len(data["questions"]), 46)
        self.assertGreaterEqual(len(data["scenarios"]), 20)
        self.assertEqual(6, len(data["simulation"]))
        for question in data["questions"]:
            self.assertTrue(question["standard_answer"])
            self.assertTrue(question["deep_dive"])
            self.assertTrue(question["pitfall"])
            self.assertTrue(question["follow_up"])
            self.assertTrue(question["official_sources"])
        self.assertGreaterEqual(len(data["open_source_projects"]), 6)

    def test_write_failures_preserves_error_context(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            write_failures(root, [{"number": "1", "url": "https://example.test/1", "error": "HTTP 500"}])
            stored = (root / "failures.json").read_text(encoding="utf-8")
            self.assertIn('"number": "1"', stored)
            self.assertIn("HTTP 500", stored)


if __name__ == "__main__":
    unittest.main()
