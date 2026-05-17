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
        if not in_code_block and (line.startswith("期望：") or line.startswith("## ")):
            break
        if line.startswith("Prompt："):
            prompt_lines.append(line.removeprefix("Prompt：").strip())
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


def collect_cases(source_root: Path) -> list[Case]:
    cases: list[Case] = []
    for markdown_file in sorted(source_root.glob("*.md")):
        if markdown_file.name == "README.md":
            continue
        cases.extend(parse_cases(markdown_file, source_root))
    return cases


def benchmark_lines(mode: str) -> list[str]:
    if mode == "hooks":
        return [
            "执行要求：",
            "- 在当前 CWD 执行。",
            "- 只执行 `cases.md` 中给出的 hook 验证步骤，必须通过 Agent 自己的编辑、命令和结束会话动作触发 hooks。",
            "- 不修改 hook 源码、不启动 Android Studio。",
            "- 允许按 case 要求修改隔离的 hook 触发文件；需要触发 Jugg pending changes 的源码触发文件必须放在 `app/src/main/java/com/example/myapplication/`。",
            "- 不要修改现有业务文件；非 sourceset 误阻断验证按 case 要求使用 `hook_benchmark_scratch/`。",
            "- 不要在报告中写入本机绝对路径；路径一律使用相对路径。",
            "- 例外：hook 反馈原文中由客户端输出的绝对脚本路径可原样保留，用于证明 Agent 实际看到了 hook 反馈。",
            "- 本 benchmark 用于验证 hooks 是否正确配置；预期阻断的 case 如 hook 未触发或收不到反馈时记 `FAIL`，不要记 `SKIP`。",
            "- stop hook 反馈不会出现在 shell/terminal/tool output 中；必须通过结束会话动作触发，并在客户端返回 followup/新消息后继续写入报告。",
            "- 二次放行反馈按客户端区分：Codex/Claude 应能看到 warning 原文；Cursor/Gemini 允许静默放行，报告记录第二次动作已放行即可。",
            "- 结果写入同目录 `report.md`。",
        ]
    lines = [
        "执行要求：",
        "- 在 `android_demo_project` 或其子目录执行。",
        "- 使用 `docs/skills/jugg-android-dev-loop` 提供的 Jugg CLI。",
        "- 不要直接调用 MCP。",
        "- 不要在报告中写入本机绝对路径；路径一律使用相对路径。",
        "- 条件不足时写明 `SKIP` 原因。",
        "- 结果写入同目录 `report.md`。",
    ]
    if mode == "ui-verify":
        lines.insert(
            -1,
            "- UI benchmark 中，预期跳过的安全门禁 case 可给满分；误跳过可执行 case 才扣分。",
        )
    return lines


def sequence_label(mode: str) -> str:
    return "Command sequence" if mode == "hooks" else "CLI sequence"


def verdict_label(mode: str) -> str:
    return "PASS / FAIL" if mode == "hooks" else "PASS / FAIL / SKIP"


def render_cases(title: str, cases: list[Case], mode: str) -> str:
    allowed_changes = (
        "- 只允许把执行结果写入同目录 `report.md`；除此之外，只能修改 case 明确要求的隔离 hook 触发文件。"
        if mode == "hooks"
        else "- 只允许把执行结果写入同目录 `report.md`。"
    )
    lines = [
        f"# {title}",
        "",
        "这些是给被测 Agent 的 prompt-only 用例。",
        "",
        "重要约束：",
        "- 不要修改 `README.md`、`cases.md`、`manifest.json`。",
        allowed_changes,
        "- 不要读取 `docs/skills/benchmark`，不要读取母版答案。",
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
        "- 只允许修改 `report.md`；除此之外，只能修改 case 明确要求的隔离 hook 触发文件。"
        if mode == "hooks"
        else "- 只允许修改 `report.md`。"
    )
    skipped_summary = "" if mode == "hooks" else "Skipped: Z"
    return f"""# {title}

这是被测 Agent 可见的 prompt-only 题目包。本文件是执行说明，不是待补全文档。

## 被测 Agent 必须遵守

- 不要修改 `README.md`、`cases.md`、`PROMPT.md`、`manifest.json`。
{allowed_changes}
- 你的任务是执行 `cases.md` 中的用例并填写 `report.md`，不是补全说明文档。
- 不要读取 `docs/skills/benchmark`，那里是母版和验收用 oracle。

## 运行约束

- 只执行 `cases.md` 中的用例。
- 所有证据写入 `report.md`，不要只在对话里总结。

{requirements}

## 文件

- `cases.md`: 被测用例，共 {case_count} 条。
- `PROMPT.md`: 可直接发给被测 Agent 的启动 prompt。
- `report.md`: 结果模板，被测 Agent 需要填写。
- `manifest.json`: 导出元数据。

## 报告格式

每条用例追加：

```markdown
### CASE-ID: 用例标题
- Prompt:
- Working dir:
- {command_label}:
- Evidence:
- Verdict: {verdicts}
- Score: N / 5
- Notes:
```

完成后追加汇总：

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
    intro = "请在当前 CWD 执行 benchmark。" if mode == "hooks" else "请在当前 `android_demo_project` 工作区执行 benchmark。"
    requirements = "\n".join(benchmark_lines(mode))
    command_label = sequence_label(mode)
    verdicts = verdict_label(mode)
    allowed_changes = (
        "- 只允许把执行结果写入同目录 `report.md`；除此之外，只能修改 case 明确要求的隔离 hook 触发文件。"
        if mode == "hooks"
        else "- 只允许把执行结果写入同目录 `report.md`。"
    )
    skip_rule = (
        "- hooks benchmark 中，预期阻断的 case 如 hook 未触发、收不到反馈或无法完成触发动作时必须记 `FAIL`，不要记 `SKIP`；预期静默放行的 case 必须记录未收到阻断或 warning。"
        if mode == "hooks"
        else (
            "- UI benchmark 中，预期跳过的安全门禁 case 可给满分；无法执行或前提不满足时才允许 `SKIP`，并写明阻塞原因与已尝试动作。"
            if mode == "ui-verify"
            else "- 无法执行时才允许 `SKIP`，并写明阻塞原因与已尝试动作。"
        )
    )
    completion_summary = (
        "总分；hooks benchmark 不填写跳过数量。"
        if mode == "hooks"
        else "总分与跳过数量。"
    )
    return f"""# {title} Agent Prompt

你是被测 Agent。{intro}

请阅读当前目录的 `README.md` 和 `cases.md`，按 `cases.md` 顺序执行全部 {case_count} 条用例。
如果上层用户消息是“完成 .../PROMPT.md”或“执行这个 PROMPT”，这表示让你执行本 benchmark，
不是让你改写 `PROMPT.md`。

硬性约束：

- 禁止修改 `README.md`、`cases.md`、`PROMPT.md`、`manifest.json`。
{allowed_changes}
- 不要读取 `docs/skills/benchmark`。
- 不允许只输出计划、推理或模板；必须真实执行命令。
- 不允许提前结束；未处理完 {case_count} 条用例前不得宣布完成。
- 每条用例都必须在 `report.md` 中留下：`Prompt`、`Working dir`、`{command_label}`、`Evidence`、`Verdict`、`Score`、`Notes`。

{requirements}

执行流程（逐条循环）：
1. 读取当前 case 的 `Prompt`。
2. 执行必要命令。
3. 立刻把 `{command_label}` 与 `Evidence` 写入 `report.md`。
4. 基于证据给出 `Verdict` 与 `Score`。
5. 继续下一条 case。

判定规则：
- `PASS`/`FAIL` 必须附带真实执行证据；hooks benchmark 对预期阻断/警告的 case 必须包含 Agent 实际看到的 hook 反馈原文，对预期静默放行的 case 必须记录未收到阻断或 warning。
- 可选 verdict：`{verdicts}`。
{skip_rule}
- `{command_label}` 为空视为该 case 未执行。

完成后在 `report.md` 末尾填写 `Summary` 和 `Blockers`，并给出{completion_summary}
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
    cases = collect_cases(source_root)
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
