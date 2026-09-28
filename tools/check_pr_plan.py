#!/usr/bin/env python3
"""Check that a pull request links to a task plan changed in the same PR."""

import json
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import quote


PLAN_PATH = re.compile(r"docs/task/\d{4}-(?:0[1-9]|1[0-2])/[^/]+\.md\Z")
PLAN_SECTION = re.compile(r"(?ms)^## Task plan[ \t]*\r?\n(.*?)(?=^## |\Z)")
MARKDOWN_LINK = re.compile(r"\[[^\]\n]+\]\((https://github\.com/[^)\s]+)\)")


def changed_plans(base_sha, head_sha):
    result = subprocess.run(
        ["git", "diff", "--name-only", "--diff-filter=AMR", base_sha + "..." + head_sha, "--", "docs/task/"],
        check=True,
        stdout=subprocess.PIPE,
        universal_newlines=True,
    )
    return [path for path in result.stdout.splitlines() if PLAN_PATH.fullmatch(path)]


def task_plan_links(body):
    section = PLAN_SECTION.search(body or "")
    if not section:
        return set()
    content = re.sub(r"<!--.*?-->", "", section.group(1), flags=re.DOTALL)
    return set(MARKDOWN_LINK.findall(content))


def main(event_path):
    pull_request = json.loads(Path(event_path).read_text(encoding="utf-8"))["pull_request"]
    plans = changed_plans(pull_request["base"]["sha"], pull_request["head"]["sha"])
    if not plans:
        print("Add or update a docs/task/YYYY-MM/*.md plan in this pull request.")
        return 1

    head_repo = pull_request["head"]["repo"]["full_name"]
    refs = (pull_request["head"]["ref"], pull_request["head"]["sha"])
    expected = {
        "https://github.com/{}/blob/{}/{}".format(
            head_repo, quote(ref, safe="/"), quote(path, safe="/")
        )
        for path in plans
        for ref in refs
    }
    if not expected.intersection(task_plan_links(pull_request.get("body"))):
        print("Link a changed task plan under the '## Task plan' section using the PR head branch or commit URL.")
        print("Expected one of:")
        for url in sorted(expected):
            print("- " + url)
        return 1

    print("Task plan is committed in this PR and linked from its description.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
