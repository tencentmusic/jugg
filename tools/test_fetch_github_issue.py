#!/usr/bin/env python3
"""Tests for the GitHub Issue fetcher."""

from __future__ import annotations

import json
import os
import sys
import unittest


ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if ROOT not in sys.path:
    sys.path.insert(0, ROOT)

from tools.fetch_github_issue import (
    extract_report_ids,
    parse_issue_page,
    parse_issue_url,
    render_markdown,
)


class FetchGitHubIssueTest(unittest.TestCase):
    def test_parse_issue_url(self) -> None:
        ref = parse_issue_url("https://github.com/tencentmusic/jugg/issues/37?foo=bar#issue")
        self.assertEqual("tencentmusic", ref.owner)
        self.assertEqual("jugg", ref.repository)
        self.assertEqual(37, ref.number)
        self.assertEqual("https://github.com/tencentmusic/jugg/issues/37", ref.url)

    def test_reject_pull_request_url(self) -> None:
        with self.assertRaisesRegex(ValueError, "pull request"):
            parse_issue_url("https://github.com/tencentmusic/jugg/pull/37")

    def test_extract_report_ids_without_duplicates(self) -> None:
        text = "Jugg report: c82381c3\nJugg Report ID: c82381c3\nreport id: another"
        self.assertEqual(["c82381c3", "another"], extract_report_ids(text))

    def test_render_markdown_includes_issue_context(self) -> None:
        output = render_markdown(
            {
                "title": "Build failed",
                "url": "https://github.com/example/repo/issues/1",
                "repository": "example/repo",
                "state": "open",
                "author": "alice",
                "labels": ["bug"],
                "juggReportIds": ["report-1"],
                "body": "Body",
                "comments": [{"author": "bob", "createdAt": "2026-09-08T00:00:00Z", "body": "Comment"}],
            }
        )
        self.assertIn("# Build failed", output)
        self.assertIn("`report-1`", output)
        self.assertIn("Comment", output)

    def test_parse_issue_page_reads_embedded_issue_and_comments(self) -> None:
        ref = parse_issue_url("https://github.com/example/repo/issues/1")
        embedded = {
            "payload": {
                "preloadedQueries": [
                    {
                        "result": {
                            "data": {
                                "repository": {
                                    "issue": {
                                        "number": 1,
                                        "title": "Build failed",
                                        "state": "OPEN",
                                        "author": {"login": "alice"},
                                        "createdAt": "2026-09-08T00:00:00Z",
                                        "updatedAt": "2026-09-08T00:00:00Z",
                                        "labels": {"edges": [{"node": {"name": "bug"}}]},
                                        "body": "Jugg report: report-1",
                                        "frontTimelineItems": {
                                            "edges": [
                                                {
                                                    "node": {
                                                        "__typename": "IssueComment",
                                                        "databaseId": 2,
                                                        "author": {"login": "bob"},
                                                        "createdAt": "2026-09-08T01:00:00Z",
                                                        "body": "Comment",
                                                    }
                                                }
                                            ]
                                        },
                                    }
                                }
                            }
                        }
                    }
                ]
            }
        }
        page = (
            '<script type="application/json" data-target="react-app.embeddedData">'
            + json.dumps(embedded)
            + "</script>"
        )
        issue = parse_issue_page(page, ref)
        self.assertEqual("open", issue["state"])
        self.assertEqual(["bug"], issue["labels"])
        self.assertEqual(["report-1"], issue["juggReportIds"])
        self.assertEqual("bob", issue["comments"][0]["author"])


if __name__ == "__main__":
    unittest.main()
