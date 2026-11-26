#!/usr/bin/env python3
"""Report language-policy review items introduced by an incoming Git diff."""

from __future__ import annotations

import argparse
from difflib import SequenceMatcher
from pathlib import Path
import re
import subprocess
import sys

HAN = re.compile(r"[\u3400-\u9fff]")
ABSTRACT = re.compile(r"^\*\*(Purpose|Decision|Impact):\*\*\s*\S", re.MULTILINE)


def git(*args: str) -> bytes:
    return subprocess.check_output(["git", *args], stderr=subprocess.PIPE)


def revision_text(revision: str, path: str) -> str:
    try:
        return git("show", f"{revision}:{path}").decode("utf-8")
    except (subprocess.CalledProcessError, UnicodeDecodeError):
        return ""


def added_lines(before: str, after: str) -> list[tuple[int, str]]:
    old, new = before.splitlines(), after.splitlines()
    matcher = SequenceMatcher(None, old, new, autojunk=False)
    return [(number + 1, new[number]) for tag, _, _, start, end in matcher.get_opcodes()
            if tag in {"insert", "replace"} for number in range(start, end)]


def classify(path: str, status: str, chinese_added: bool, after: str) -> tuple[str, str, str]:
    parts = Path(path).parts
    name = Path(path).name.lower()
    if not chinese_added:
        return "No new Chinese", "—", "No language action; still review factual changes normally."
    if path.startswith("docs/wiki/zh/") or name.endswith(".zh-cn.md") or any(part.startswith("values-zh") for part in parts) or (name.startswith("change_log") and "_cn" in name):
        return "Localization", "English counterpart if product facts changed", "Exempt language input; synchronize product facts with the English source."
    if "src/test/" in path or "src/androidTest/" in path or "/fixtures/" in path:
        return "Test input", "—", "Exempt meaningful input/expected output; translate only nonsemantic comments."
    if path.startswith("docs/task/"):
        if status == "A" and set(ABSTRACT.findall("\n".join(after.splitlines()[:35]))) != {"Purpose", "Decision", "Impact"}:
            return "Task abstract review", path, "If the body is non-English, add a concise English Purpose / Decision / Impact abstract before it."
        return "Task record", path, "Preserve the reviewer-language body; extract confirmed durable facts into docs/ai/."
    if path.startswith("docs/superpowers/"):
        return "Historical plan", "Relevant docs/ai/ topic after verification", "Keep the archive; confirm any durable fact against code before extraction."
    if path.startswith("docs/ai_knowledge/"):
        return "Current knowledge", "docs/ai/" + path[len("docs/ai_knowledge/"):], "Compare new facts with code and existing English page; merge unique facts, never overwrite them."
    if path.startswith("docs/ai/"):
        return "Current knowledge", path, "Review new Chinese prose and retain only meaningful diagnostic strings/examples."
    if path.startswith("docs/wiki/"):
        return "Current Wiki", path, "Write the English source first, then synchronize docs/wiki/zh/."
    if path.endswith((".kt", ".java", ".py", ".sh", ".rb")):
        return "Source or diagnostic", path, "Review whether Chinese is a diagnostic matcher/input or user-facing copy; translate only the latter."
    if name.endswith((".md", ".mdx", ".txt")) or path.startswith((".agents/", ".github/")):
        return "Current guidance", path, "Review the changed lines and make English the maintained source; preserve meaningful localized input."
    return "Other input", path, "Classify the changed content manually; do not fail solely because it contains Chinese."


def changed_files(base: str, head: str) -> list[tuple[str, str, str]]:
    fields = git("diff", "--name-status", "-z", "--find-renames", "--diff-filter=ACMR", base, head, "--").split(b"\0")
    result = []
    i = 0
    while i < len(fields) and fields[i]:
        status = fields[i].decode("ascii")[0]
        if status in {"R", "C"}:
            old_path = fields[i + 1].decode("utf-8")
            new_path = fields[i + 2].decode("utf-8")
            i += 3
        else:
            old_path = new_path = fields[i + 1].decode("utf-8")
            i += 2
        result.append((status, old_path, new_path))
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True, help="Commit before the incoming changes (for a merge, usually its first parent).")
    parser.add_argument("--head", default="HEAD", help="Resulting commit or candidate branch tip; default HEAD.")
    parser.add_argument("--report", type=Path, help="Write the Markdown review report to this path instead of stdout.")
    args = parser.parse_args()
    try:
        base = git("rev-parse", "--verify", f"{args.base}^{{commit}}").decode().strip()
        head = git("rev-parse", "--verify", f"{args.head}^{{commit}}").decode().strip()
        files = changed_files(base, head)
    except subprocess.CalledProcessError as exc:
        print(exc.stderr.decode("utf-8", errors="replace").strip(), file=sys.stderr)
        return 2

    rows = []
    for status, old_path, path in files:
        before = "" if status == "A" else revision_text(base, old_path)
        after = revision_text(head, path)
        if not after:
            continue  # Deleted/undecodable files cannot introduce Chinese prose.
        additions = added_lines(before, after)
        chinese_lines = [number for number, line in additions if HAN.search(line)]
        has_han = bool(chinese_lines)
        category, landing, action = classify(path, status, has_han, after)
        if has_han:
            locations = ", ".join(f"L{number}" for number in chinese_lines[:5])
            if len(chinese_lines) > 5:
                locations += f" (+{len(chinese_lines) - 5} more)"
            rows.append((path, locations, category, landing, action))

    lines = ["# Incoming language review", "", f"- Base: `{base}`", f"- Result: `{head}`",
             f"- Changed files inspected: {len(files)}", f"- Files with newly added Chinese lines: {len(rows)}", "",
             "This report inspects added/replaced lines only. It is a review aid, not an automatic language gate. Existing archives and unchanged Chinese text do not appear. A matching path or Chinese character alone does not establish whether a fact is current or a translation is complete.", "",
             "| Path | Added lines | Classification | English landing | Review action |", "|---|---|---|---|---|"]
    for path, locations, category, landing, action in rows:
        lines.append(f"| `{path}` | {locations} | {category} | {landing} | {action} |")
    if not rows:
        lines.append("| — | — | No newly added Chinese lines detected | — | Continue ordinary factual review. |")
    lines.extend(["", "## Integration record", "",
                  "For each row, record the final classification, the English landing page or exemption, and unresolved factual questions. Compare both the incoming source and the resulting English page; do not overwrite branch-only facts with an older English translation. Confirm current product behavior against code. Re-run this report after resolving the merge, using the pre-merge base and final integration commit.", ""])
    output = "\n".join(lines)
    if args.report:
        args.report.write_text(output, encoding="utf-8")
    else:
        print(output, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
