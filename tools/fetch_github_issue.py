#!/usr/bin/env python3
"""Fetch a GitHub Issue and render its context without MCP or third-party packages."""

from __future__ import annotations

import argparse
import html
import json
import os
import re
import sys
from typing import Any, Dict, List, NamedTuple, Optional, Sequence
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import Request, urlopen


API_VERSION = "2022-11-28"
DEFAULT_TIMEOUT_SECONDS = 20.0
ISSUE_PATH_PATTERN = re.compile(r"^/([^/]+)/([^/]+)/issues/(\d+)/?$")
REPORT_ID_PATTERN = re.compile(
    r"(?i)\b(?:jugg\s+report(?:\s+id)?|report\s+id)\s*[:：]\s*"
    r"([A-Za-z0-9][A-Za-z0-9._-]*)"
)


class IssueRef(NamedTuple):
    owner: str
    repository: str
    number: int
    url: str


class GitHubRequestError(RuntimeError):
    """Raised when GitHub returns an error or cannot be reached."""

    def __init__(self, message: str, status_code: Optional[int] = None) -> None:
        super().__init__(message)
        self.status_code = status_code


def parse_issue_url(value: str) -> IssueRef:
    parsed = urlparse(value.strip())
    if parsed.scheme not in ("http", "https") or parsed.netloc.lower() not in (
        "github.com",
        "www.github.com",
    ):
        raise ValueError("expected a GitHub Issue URL")

    match = ISSUE_PATH_PATTERN.match(parsed.path)
    if match is None:
        if "/pull/" in parsed.path:
            raise ValueError("pull request URLs are not supported")
        raise ValueError("expected URL format: https://github.com/{owner}/{repo}/issues/{number}")

    owner, repository, number = match.groups()
    return IssueRef(
        owner=owner,
        repository=repository,
        number=int(number),
        url="https://github.com/%s/%s/issues/%s" % (owner, repository, number),
    )


def _request_json(url: str, token: Optional[str], timeout: float) -> Any:
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "jugg-github-issue-fetcher",
        "X-GitHub-Api-Version": API_VERSION,
    }
    if token:
        headers["Authorization"] = "Bearer " + token

    request = Request(url, headers=headers)
    try:
        with urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except HTTPError as exc:
        detail = ""
        try:
            payload = json.loads(exc.read().decode("utf-8"))
            detail = payload.get("message", "")
        except (ValueError, UnicodeDecodeError):
            pass
        suffix = ": " + detail if detail else ""
        raise GitHubRequestError("GitHub API returned HTTP %s%s" % (exc.code, suffix), exc.code)
    except URLError as exc:
        raise GitHubRequestError("cannot reach GitHub API: %s" % exc.reason)


def _request_html(url: str, timeout: float) -> str:
    request = Request(
        url,
        headers={
            "Accept": "text/html",
            "User-Agent": "jugg-github-issue-fetcher",
        },
    )
    try:
        with urlopen(request, timeout=timeout) as response:
            return response.read().decode("utf-8")
    except HTTPError as exc:
        raise GitHubRequestError("GitHub page returned HTTP %s" % exc.code, exc.code)
    except URLError as exc:
        raise GitHubRequestError("cannot reach GitHub page: %s" % exc.reason)


def _api_url(ref: IssueRef, suffix: str = "") -> str:
    return "https://api.github.com/repos/%s/%s/issues/%s%s" % (
        ref.owner,
        ref.repository,
        ref.number,
        suffix,
    )


def fetch_issue(ref: IssueRef, token: Optional[str], include_comments: bool, timeout: float) -> Dict[str, Any]:
    try:
        issue = _request_json(_api_url(ref), token, timeout)
    except GitHubRequestError as exc:
        if not token and exc.status_code in (403, 429):
            return fetch_issue_from_page(ref, include_comments, timeout)
        raise

    comments: List[Dict[str, Any]] = []
    if include_comments and issue.get("comments", 0):
        page = 1
        while True:
            try:
                response = _request_json(
                    _api_url(ref, "/comments?per_page=100&page=%d" % page),
                    token,
                    timeout,
                )
            except GitHubRequestError as exc:
                if not token and exc.status_code in (403, 429):
                    return fetch_issue_from_page(ref, include_comments, timeout)
                raise
            if not isinstance(response, list):
                raise GitHubRequestError("GitHub returned an invalid comments response")
            comments.extend(response)
            if len(response) < 100:
                break
            page += 1

    return normalize_issue(ref, issue, comments)


def fetch_issue_from_page(ref: IssueRef, include_comments: bool, timeout: float) -> Dict[str, Any]:
    issue = parse_issue_page(_request_html(ref.url, timeout), ref)
    if not include_comments:
        issue["comments"] = []
        issue["juggReportIds"] = extract_report_ids(issue["body"])
    return issue


def parse_issue_page(content: str, ref: IssueRef) -> Dict[str, Any]:
    match = re.search(
        r'<script type="application/json" data-target="react-app\.embeddedData">(.*?)</script>',
        content,
        re.DOTALL,
    )
    if match is None:
        raise GitHubRequestError("GitHub page did not contain structured Issue data")

    try:
        embedded = json.loads(html.unescape(match.group(1)))
    except (TypeError, ValueError) as exc:
        raise GitHubRequestError("GitHub page contained invalid structured Issue data") from exc

    issue = None
    for query in embedded.get("payload", {}).get("preloadedQueries", []):
        candidate = query.get("result", {}).get("data", {}).get("repository", {}).get("issue")
        if isinstance(candidate, dict) and candidate.get("number") == ref.number:
            issue = candidate
            break
    if issue is None:
        raise GitHubRequestError("GitHub page did not contain the requested Issue")

    comments = []
    seen_comment_ids = set()
    for timeline_name in ("frontTimelineItems", "backTimelineItems"):
        for edge in issue.get(timeline_name, {}).get("edges", []):
            comment = edge.get("node", {})
            if comment.get("__typename") != "IssueComment":
                continue
            comment_id = comment.get("databaseId")
            if comment_id in seen_comment_ids:
                continue
            seen_comment_ids.add(comment_id)
            comments.append(comment)

    comments.sort(key=lambda comment: comment.get("createdAt") or "")
    return normalize_issue(ref, issue, comments)


def normalize_issue(ref: IssueRef, issue: Dict[str, Any], comments: List[Dict[str, Any]]) -> Dict[str, Any]:
    body = issue.get("body") or ""
    comment_bodies = [comment.get("body") or "" for comment in comments]
    report_ids = extract_report_ids("\n\n".join([body] + comment_bodies))
    return {
        "url": ref.url,
        "repository": "%s/%s" % (ref.owner, ref.repository),
        "number": ref.number,
        "title": issue.get("title") or "",
        "state": (issue.get("state") or "").lower(),
        "author": _user_login(issue.get("user") or issue.get("author")),
        "createdAt": issue.get("created_at") or issue.get("createdAt") or "",
        "updatedAt": issue.get("updated_at") or issue.get("updatedAt") or "",
        "labels": _issue_labels(issue),
        "body": body,
        "comments": [
            {
                "author": _user_login(comment.get("user") or comment.get("author")),
                "createdAt": comment.get("created_at") or comment.get("createdAt") or "",
                "body": comment.get("body") or "",
            }
            for comment in comments
        ],
        "juggReportIds": report_ids,
    }


def _user_login(user: Any) -> str:
    return user.get("login", "unknown") if isinstance(user, dict) else "unknown"


def _issue_labels(issue: Dict[str, Any]) -> List[str]:
    labels = issue.get("labels", [])
    if isinstance(labels, list):
        return [label.get("name") for label in labels if isinstance(label, dict) and label.get("name")]
    if isinstance(labels, dict):
        return [
            node.get("name")
            for edge in labels.get("edges", [])
            for node in [edge.get("node", {})]
            if isinstance(node, dict) and node.get("name")
        ]
    return []


def extract_report_ids(text: str) -> List[str]:
    result: List[str] = []
    for match in REPORT_ID_PATTERN.finditer(text):
        report_id = match.group(1)
        if report_id not in result:
            result.append(report_id)
    return result


def render_markdown(issue: Dict[str, Any]) -> str:
    lines = [
        "# %s" % issue["title"],
        "",
        "- URL: %s" % issue["url"],
        "- Repository: `%s`" % issue["repository"],
        "- State: `%s`" % issue["state"],
        "- Author: `%s`" % issue["author"],
        "- Labels: %s" % (", ".join("`%s`" % label for label in issue["labels"]) or "none"),
    ]
    if issue["juggReportIds"]:
        lines.append("- Jugg Report IDs: %s" % ", ".join("`%s`" % value for value in issue["juggReportIds"]))

    lines.extend(["", "## Body", "", issue["body"] or "(empty)"])
    if issue["comments"]:
        lines.extend(["", "## Comments", ""])
        for comment in issue["comments"]:
            lines.extend([
                "### %s · %s" % (comment["author"], comment["createdAt"]),
                "",
                comment["body"] or "(empty)",
                "",
            ])
    return "\n".join(lines).rstrip() + "\n"


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("issue_url", help="GitHub Issue URL")
    parser.add_argument("--json", action="store_true", dest="json_output", help="output structured JSON")
    parser.add_argument("--no-comments", action="store_true", help="only fetch the Issue body")
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT_SECONDS, help="HTTP timeout in seconds")
    return parser


def main(argv: Optional[Sequence[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        ref = parse_issue_url(args.issue_url)
        if args.timeout <= 0:
            raise ValueError("timeout must be greater than zero")
        issue = fetch_issue(ref, os.environ.get("GITHUB_TOKEN"), not args.no_comments, args.timeout)
    except (GitHubRequestError, ValueError) as exc:
        print("error: %s" % exc, file=sys.stderr)
        return 1

    if args.json_output:
        print(json.dumps(issue, ensure_ascii=False, indent=2))
    else:
        print(render_markdown(issue), end="")
    return 0


if __name__ == "__main__":
    sys.exit(main())
