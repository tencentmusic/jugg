#!/usr/bin/env python3
"""Export prompt-only benchmark packs for tested agents."""

from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[4]
DEFAULT_OUTPUT_ROOT = REPO_ROOT / "android_demo_project" / "build" / "benchmark-packs"

BENCHMARKS = {
    "cli": {
        "title": "Jugg CLI Benchmark Prompt Pack",
        "source": REPO_ROOT / "docs" / "skills" / "benchmark" / "benchmark-cli",
        "mode": "cli",
    },
    "ui-verify": {
        "title": "Jugg UI Verify Benchmark Prompt Pack",
        "source": REPO_ROOT / "docs" / "skills" / "benchmark" / "benchmark-ui-verify",
        "mode": "ui-verify",
    },
    "hooks": {
        "title": "Jugg Agent Hooks Benchmark Prompt Pack",
        "source": REPO_ROOT / "docs" / "skills" / "benchmark" / "benchmark-hooks",
        "mode": "hooks",
    },
    "instrument": {
        "title": "Jugg Instrument Benchmark Prompt Pack",
        "source": REPO_ROOT / "docs" / "skills" / "benchmark" / "benchmark-instrument",
        "mode": "cli",
        "file_order": [
            "l2_instrument_basic.md",
            "l2_instrument_advanced.md",
            "l3_instrument_no_device.md",
            "l4_instrument_e2e.md",
        ],
    },
}


@dataclass(frozen=True)
class Case:
    source_file: str
    case_id: str
    title: str
    prompt: str


def parse_case_heading(line: str) -> tuple[str, str] | None:
    match = re.match(r"^##\s+([^:：]+)[:：]\s*(.+?)\s*$", line)
    if not match:
        return None
    return match.group(1).strip(), match.group(2).strip()


def extract_prompt(lines: list[str], start: int) -> str:
    prompt_lines: list[str] = []
    in_code_block = False
    i = start
    while i < len(lines):
        line = lines[i]
        if not in_code_block and (line.startswith(("Expected:", "期望：")) or line.startswith("## ")):
            break
        if line.startswith(("Prompt:", "Prompt：")):
            prompt_lines.append(line.removeprefix("Prompt:").removeprefix("Prompt：").strip())
        elif prompt_lines:
            if line.startswith("```"):
                in_code_block = not in_code_block
            prompt_lines.append(line if in_code_block else line.strip())
        i += 1
    return "\n".join(line for line in prompt_lines if line).strip()


def parse_cases(markdown_file: Path, source_root: Path) -> list[Case]:
    lines = markdown_file.read_text(encoding="utf-8").splitlines()
    cases: list[Case] = []
    for index, line in enumerate(lines):
        heading = parse_case_heading(line)
        if not heading:
            continue
        case_id, title = heading
        prompt = extract_prompt(lines, index + 1)
        if not prompt:
            raise ValueError(f"Missing prompt for {case_id} in {markdown_file}")
        cases.append(
            Case(
                source_file=str(markdown_file.relative_to(source_root)),
                case_id=case_id,
                title=title,
                prompt=prompt,
            )
        )
    return cases


def collect_cases(source_root: Path, file_order: list[str] | None = None) -> list[Case]:
    md_files = {p.name: p for p in source_root.glob("*.md") if p.name != "README.md"}

    if file_order:
        ordered: list[Path] = []
        for name in file_order:
            if name in md_files:
                ordered.append(md_files.pop(name))
        # append remaining files alphabetically
        ordered.extend(p for _, p in sorted(md_files.items()))
    else:
        ordered = [p for _, p in sorted(md_files.items())]

    cases: list[Case] = []
    for markdown_file in ordered:
        cases.extend(parse_cases(markdown_file, source_root))
    return cases


def benchmark_lines(mode: str) -> list[str]:
    if mode == "hooks":
        return [
            "Execution requirements:",
            "- Run in the current CWD.",
            "- Run only the hook steps in `cases.md`; trigger hooks through the agent's own edits, commands, and session-ending action.",
            "- Do not edit hook source or start Android Studio.",
            "- Add, move, or modify only isolated trigger files required by a case. Source triggers for Jugg pending changes belong under `app/src/main/java/com/example/myapplication/`.",
            "- Do not edit existing business files. Use `hook_benchmark_scratch/` only for the non-sourceset false-block case.",
            "- Use relative paths in the report, except verbatim absolute script paths printed by the client in hook feedback.",
            "- If a case expects a block but the hook does not fire or feedback is unavailable, mark `FAIL`, not `SKIP`.",
            "- Stop-hook feedback does not appear in shell/terminal/tool output. Trigger it by ending the session; continue recording if the client returns a follow-up message.",
            "- On repeated allowance, Codex/Claude command warnings must be visible in context. Cursor/Gemini stop allowance may be silent. A human records whether the second Codex/Claude stop warning appeared in the client under `Human confirmation (Codex / Claude)` in report.md; `systemMessage` normally does not reach agent context and its absence is not an agent FAIL.",
            "- Write results to the adjacent `report.md`.",
        ]
    lines = [
        "Execution requirements:",
        "- Run inside `android_demo_project` or a subdirectory.",
        "- Use the Jugg CLI from `docs/skills/jugg-android-dev-loop`.",
        "- Do not call MCP directly.",
        "- Use relative paths in the report, not machine-specific absolute paths.",
        "- State the reason for any `SKIP`.",
        "- Write results to the adjacent `report.md`.",
    ]
    if mode == "ui-verify":
        lines.insert(
            -1,
            "- In UI cases, an expected safety-gate skip may earn full credit; penalize an incorrect skip of an executable case.",
        )
    return lines


def sequence_label(mode: str) -> str:
    return "Command sequence" if mode == "hooks" else "CLI sequence"


def verdict_label(mode: str) -> str:
    return "PASS / FAIL" if mode == "hooks" else "PASS / FAIL / SKIP"


def render_cases(title: str, cases: list[Case], mode: str) -> str:
    allowed_changes = (
        "- Write results only to the adjacent `report.md`; otherwise add, move, or modify only the isolated hook trigger files required by a case."
        if mode == "hooks"
        else "- Write results only to the adjacent `report.md`."
    )
    lines = [
        f"# {title}",
        "",
        "These are prompt-only cases for the agent under test.",
        "",
        "Constraints:",
        "- Do not modify `README.md`, `cases.md`, or `manifest.json`.",
        allowed_changes,
        "- Do not read `docs/skills/benchmark`; it contains the master answers.",
        "",
        *benchmark_lines(mode),
        "",
    ]
    current_file: str | None = None
    for case in cases:
        if case.source_file != current_file:
            current_file = case.source_file
            lines.extend([f"## {current_file}", ""])
        lines.extend(
            [
                f"### {case.case_id}: {case.title}",
                "",
                case.prompt,
                "",
            ]
        )
    return "\n".join(lines).rstrip() + "\n"


def render_readme(title: str, case_count: int, mode: str) -> str:
    command_label = sequence_label(mode)
    requirements = "\n".join(benchmark_lines(mode))
    verdicts = verdict_label(mode)
    allowed_changes = (
        "- Modify only `report.md`, except for isolated hook trigger files required by a case."
        if mode == "hooks"
        else "- Modify only `report.md`."
    )
    skipped_summary = "" if mode == "hooks" else "Skipped: Z"
    return f"""# {title}

This prompt-only pack is visible to the agent under test. This file contains instructions; it is not a document to complete.

## Requirements for the Agent Under Test

- Do not modify `README.md`, `cases.md`, `PROMPT.md`, or `manifest.json`.
{allowed_changes}
- Execute the cases in `cases.md` and fill in `report.md`; do not complete the instruction documents.
- Do not read `docs/skills/benchmark`; it is the evaluation oracle.

## Execution Constraints

- Run only the cases in `cases.md`.
- Put all evidence in `report.md`, rather than only summarizing it in chat.

{requirements}

## Files

- `cases.md`: {case_count} cases for the agent under test.
- `PROMPT.md`: Startup prompt to send to the agent.
- `report.md`: Result template for the agent to fill in.
- `manifest.json`: Export metadata.

## Report Format

Append for each case:

```markdown
### CASE-ID: Case title
- Prompt:
- Working dir:
- {command_label}:
- Evidence:
- Verdict: {verdicts}
- Score: N / 5
- Notes:
```

Append a summary after all cases:

```markdown
## Summary

| File | Case | Verdict | Score | Notes |
|------|------|---------|-------|-------|

Total: XX / YY
{skipped_summary}

Blockers:
```
"""


def render_prompt(title: str, case_count: int, mode: str) -> str:
    intro = "Run the benchmark in the current CWD." if mode == "hooks" else "Run the benchmark in the `android_demo_project` workspace."
    requirements = "\n".join(benchmark_lines(mode))
    command_label = sequence_label(mode)
    verdicts = verdict_label(mode)
    allowed_changes = (
        "- Write results only to the adjacent `report.md`; otherwise add, move, or modify only isolated hook trigger files required by a case."
        if mode == "hooks"
        else "- Write results only to the adjacent `report.md`."
    )
    skip_rule = (
        "- In hooks cases expecting a block, mark `FAIL`, not `SKIP`, if the hook does not fire, feedback is missing, or the trigger cannot be completed. When silent allowance is expected, record the absence of a block or warning."
        if mode == "hooks"
        else (
            "- In UI cases, an expected safety-gate skip may earn full credit. Use `SKIP` only when execution or a prerequisite is impossible; state the blocker and attempted actions."
            if mode == "ui-verify"
            else "- Use `SKIP` only when execution is impossible; state the blocker and attempted actions."
        )
    )
    completion_summary = (
        "the total score; omit the skipped count for hooks."
        if mode == "hooks"
        else "the total score and skipped count."
    )
    return f"""# {title} Agent Prompt

You are the agent under test. {intro}

Read the local `README.md` and `cases.md`; run all {case_count} cases in order.
If the parent request asks you to complete or run `PROMPT.md`, it means to run this benchmark, not to rewrite `PROMPT.md`.

Hard constraints:

- Do not modify `README.md`, `cases.md`, `PROMPT.md`, or `manifest.json`.
{allowed_changes}
- Do not read `docs/skills/benchmark`.
- Execute commands; do not provide only a plan, reasoning, or template.
- Do not finish before handling all {case_count} cases.
- For each case, record `Prompt`, `Working dir`, `{command_label}`, `Evidence`, `Verdict`, `Score`, and `Notes` in `report.md`.

{requirements}

Execution loop:
1. Read the current case's `Prompt`.
2. Run the necessary commands.
3. Immediately write `{command_label}` and `Evidence` in `report.md`.
4. Assign `Verdict` and `Score` based on evidence.
5. Continue to the next case.

Assessment rules:
- `PASS`/`FAIL` require real execution evidence. For hooks cases expecting a block or warning, include verbatim feedback actually seen by the agent; for silent allowance, record that no block or warning arrived.
- Allowed verdicts: `{verdicts}`.
{skip_rule}
- An empty `{command_label}` means the case was not executed.

At the end of `report.md`, fill in `Summary` and `Blockers` and give {completion_summary}
"""


def render_report(title: str, cases: list[Case], mode: str) -> str:
    command_label = sequence_label(mode)
    verdicts = verdict_label(mode)
    skipped_summary = "" if mode == "hooks" else "Skipped: Z"
    lines = [
        f"# {title} Report",
        "",
        "## Environment",
        "",
        "- Working dir:",
        "- Agent:",
        "- Date:",
        "",
        "## Results",
        "",
    ]
    for case in cases:
        lines.extend(
            [
                f"### {case.case_id}: {case.title}",
                "- Prompt:",
                case.prompt,
                "- Working dir:",
                f"- {command_label}:",
                "- Evidence:",
                f"- Verdict: {verdicts}",
                "- Score: N / 5",
                "- Notes:",
                "",
            ]
        )
    lines.extend(
        [
            "## Summary",
            "",
            "| File | Case | Verdict | Score | Notes |",
            "|------|------|---------|-------|-------|",
            "",
            "Total: XX / YY",
            skipped_summary,
            "",
            "Blockers:",
            "",
        ]
    )
    return "\n".join(lines)


def export_pack(kind: str, output_root: Path) -> Path:
    config = BENCHMARKS[kind]
    source_root = config["source"]
    title = config["title"]
    mode = config["mode"]
    file_order = config.get("file_order")
    cases = collect_cases(source_root, file_order=file_order)
    if not cases:
        raise ValueError(f"No cases found in {source_root}")

    output_dir = output_root / kind
    output_dir.mkdir(parents=True, exist_ok=True)
    (output_dir / "README.md").write_text(render_readme(title, len(cases), mode), encoding="utf-8")
    (output_dir / "PROMPT.md").write_text(render_prompt(title, len(cases), mode), encoding="utf-8")
    (output_dir / "cases.md").write_text(render_cases(title, cases, mode), encoding="utf-8")
    (output_dir / "report.md").write_text(render_report(title, cases, mode), encoding="utf-8")
    manifest = {
        "kind": kind,
        "title": title,
        "caseCount": len(cases),
        "files": sorted({case.source_file for case in cases}),
    }
    (output_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    return output_dir


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("benchmark", choices=["all", *BENCHMARKS.keys()])
    parser.add_argument(
        "--output-root",
        type=Path,
        default=DEFAULT_OUTPUT_ROOT,
        help="Output root for generated prompt packs.",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    kinds = BENCHMARKS.keys() if args.benchmark == "all" else [args.benchmark]
    for kind in kinds:
        output_dir = export_pack(kind, args.output_root)
        try:
            print(output_dir.relative_to(REPO_ROOT))
        except ValueError:
            print(output_dir)


if __name__ == "__main__":
    main()
