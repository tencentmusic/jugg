#!/usr/bin/env python3
"""Restore Markdown formatting from a reference draft and report text changes."""

from __future__ import annotations

import argparse
from dataclasses import dataclass, field
from difflib import SequenceMatcher
from pathlib import Path
import re
from typing import Dict, List, Optional, Sequence, Tuple


IMAGE_MARKDOWN_RE = re.compile(r"^!\[[^]]*]\([^)]+\)$")
IMAGE_HTML_RE = re.compile(r"^<img\s+[^>]*>$", re.IGNORECASE)
IMAGE_PLACEHOLDER_RE = re.compile(r"^(?:图片|title_img|[^/]+\.(?:png|jpe?g|gif|webp))$", re.IGNORECASE)
SEPARATOR_RE = re.compile(r"^-{3,}$")
SPACE_RE = re.compile(r"\s+")


@dataclass(frozen=True)
class InlineSpan:
    """Inline Markdown wrapper and its range in plain text."""

    start: int
    end: int
    opening: str
    closing: str


@dataclass
class ArticleLine:
    """Semantic representation of one non-empty article line."""

    raw: str
    line_no: int
    prefix: str = ""
    plain: str = ""
    key: str = ""
    kind: str = "text"
    spans: List[InlineSpan] = field(default_factory=list)


@dataclass(frozen=True)
class Alignment:
    """One aligned reference/final line pair or insertion/deletion."""

    reference: Optional[ArticleLine]
    final: Optional[ArticleLine]
    similarity: float


def split_block_prefix(line: str) -> Tuple[str, str]:
    for pattern in (
        r"^(#{1,6}\s+)(.*)$",
        r"^(>\s?)(.*)$",
        r"^(\s*[-*+]\s+)(.*)$",
        r"^(\s*\d+[.)]\s+)(.*)$",
    ):
        match = re.match(pattern, line)
        if match:
            return match.group(1), match.group(2)
    return "", line


def parse_inline_markdown(value: str) -> Tuple[str, List[InlineSpan]]:
    """Remove supported inline Markdown while recording reusable wrappers."""

    plain: List[str] = []
    spans: List[InlineSpan] = []
    markers: List[Tuple[str, int]] = []
    index = 0
    while index < len(value):
        link = re.match(r"\[([^]]+)]\(([^)]+)\)", value[index:])
        if link:
            start = len(plain)
            plain.extend(link.group(1))
            spans.append(InlineSpan(start, len(plain), "[", f"]({link.group(2)})"))
            index += link.end()
            continue
        code = re.match(r"(`+)(.+?)\1", value[index:])
        if code:
            start = len(plain)
            plain.extend(code.group(2))
            spans.append(InlineSpan(start, len(plain), code.group(1), code.group(1)))
            index += code.end()
            continue
        marker = "**" if value.startswith("**", index) else "*" if value[index] == "*" else ""
        if marker and not any(item[0] == marker for item in markers):
            if value.find(marker, index + len(marker)) < 0:
                marker = ""
        if marker:
            active = next((item for item in reversed(markers) if item[0] == marker), None)
            if active:
                markers.remove(active)
                spans.append(InlineSpan(active[1], len(plain), marker, marker))
            else:
                markers.append((marker, len(plain)))
            index += len(marker)
            continue
        if value[index] == "<":
            tag = re.match(r"<[^>]+>", value[index:])
            if tag:
                index += tag.end()
                continue
        plain.append(value[index])
        index += 1
    return "".join(plain), spans


def comparison_key(value: str) -> str:
    return SPACE_RE.sub("", value).strip()


def parse_line(raw: str, line_no: int, reference: bool) -> Optional[ArticleLine]:
    stripped = raw.strip()
    if not stripped or SEPARATOR_RE.fullmatch(stripped):
        return None
    if IMAGE_MARKDOWN_RE.fullmatch(stripped) or IMAGE_HTML_RE.fullmatch(stripped):
        return ArticleLine(raw, line_no, plain="<image>", key="<image>", kind="image")
    if reference and stripped.startswith("[!TODO ") and stripped.endswith("](TODO)"):
        return ArticleLine(raw, line_no, plain="<media>", key="<media>", kind="image")
    if not reference and IMAGE_PLACEHOLDER_RE.fullmatch(stripped):
        return ArticleLine(raw, line_no, plain="<image>", key="<image>", kind="image")

    prefix, content = split_block_prefix(raw.rstrip()) if reference else ("", stripped)
    if content in {"[!TIP]", "[!NOTE]", "✓", "i"}:
        return ArticleLine(raw, line_no, prefix, "<callout>", "<callout>", "callout")
    plain, spans = parse_inline_markdown(content)
    return ArticleLine(raw, line_no, prefix, plain, comparison_key(plain), spans=spans)


def load_lines(path: Path, reference: bool) -> List[ArticleLine]:
    result: List[ArticleLine] = []
    for line_no, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        parsed = parse_line(raw, line_no, reference)
        if parsed:
            result.append(parsed)
    return result


def line_similarity(reference: ArticleLine, final: ArticleLine) -> float:
    if reference.kind != final.kind and "text" not in {reference.kind, final.kind}:
        return 0.0
    if reference.kind == final.kind and reference.kind in {"image", "callout"}:
        return 1.0
    return SequenceMatcher(None, reference.key, final.key, autojunk=False).ratio()


def align_lines(reference: Sequence[ArticleLine], final: Sequence[ArticleLine]) -> List[Alignment]:
    """Align article lines globally so insertions do not shift later sections."""

    gap_score = -0.7
    scores = [[0.0] * (len(final) + 1) for _ in range(len(reference) + 1)]
    moves = [[""] * (len(final) + 1) for _ in range(len(reference) + 1)]
    for i in range(1, len(reference) + 1):
        scores[i][0] = i * gap_score
        moves[i][0] = "delete"
    for j in range(1, len(final) + 1):
        scores[0][j] = j * gap_score
        moves[0][j] = "insert"

    for i, old in enumerate(reference, 1):
        for j, new in enumerate(final, 1):
            similarity = line_similarity(old, new)
            candidates = (
                (scores[i - 1][j - 1] + similarity * 3.0 - 1.4, "match"),
                (scores[i - 1][j] + gap_score, "delete"),
                (scores[i][j - 1] + gap_score, "insert"),
            )
            scores[i][j], moves[i][j] = max(candidates, key=lambda item: item[0])

    aligned: List[Alignment] = []
    i, j = len(reference), len(final)
    while i or j:
        move = moves[i][j]
        if move == "match":
            old, new = reference[i - 1], final[j - 1]
            aligned.append(Alignment(old, new, line_similarity(old, new)))
            i -= 1
            j -= 1
        elif move == "delete":
            aligned.append(Alignment(reference[i - 1], None, 0.0))
            i -= 1
        else:
            aligned.append(Alignment(None, final[j - 1], 0.0))
            j -= 1
    return list(reversed(aligned))


def locate_span(span: InlineSpan, reference: ArticleLine, final_text: str) -> Optional[Tuple[int, int]]:
    old_text = reference.plain[span.start:span.end]
    starts = [match.start() for match in re.finditer(re.escape(old_text), final_text)]
    if starts:
        expected = round(span.start * len(final_text) / max(len(reference.plain), 1))
        start = min(starts, key=lambda item: abs(item - expected))
        return start, start + len(old_text)
    if span.start == 0 and span.end == len(reference.plain):
        return 0, len(final_text)
    return None


def restore_inline_format(reference: ArticleLine, final_text: str) -> str:
    openings: Dict[int, List[Tuple[int, str]]] = {}
    closings: Dict[int, List[Tuple[int, str]]] = {}
    for span in reference.spans:
        located = locate_span(span, reference, final_text)
        if not located:
            continue
        start, end = located
        openings.setdefault(start, []).append((end, span.opening))
        closings.setdefault(end, []).append((start, span.closing))

    output: List[str] = []
    for index in range(len(final_text) + 1):
        for _, marker in sorted(closings.get(index, []), reverse=True):
            output.append(marker)
        for _, marker in sorted(openings.get(index, []), reverse=True):
            output.append(marker)
        if index < len(final_text):
            output.append(final_text[index])
    return "".join(output)


def render_alignment(item: Alignment) -> Optional[str]:
    if not item.final:
        return None
    if not item.reference or item.similarity < 0.35:
        if item.final.kind == "image":
            return "![TODO](TODO)"
        return item.final.raw.strip()
    if item.reference.kind in {"image", "callout"}:
        return item.reference.raw.strip()
    content = restore_inline_format(item.reference, item.final.raw.strip())
    return item.reference.prefix + content


def block_family(line: str) -> str:
    if re.match(r"^\s*(?:[-*+] |\d+[.)] )", line):
        return "list"
    if line.startswith(">"):
        return "quote"
    return "block"


def write_formatted(path: Path, aligned: Sequence[Alignment]) -> None:
    rendered = [line for item in aligned if (line := render_alignment(item))]
    output: List[str] = []
    for line in rendered:
        if output and block_family(output[-1]) != block_family(line):
            output.append("")
        elif output and block_family(line) == "block":
            output.append("")
        output.append(line)
    path.write_text("\n".join(output).rstrip() + "\n", encoding="utf-8")


def is_same_text(item: Alignment) -> bool:
    if not item.reference or not item.final:
        return False
    return item.reference.key == item.final.key


def group_text_changes(aligned: Sequence[Alignment]) -> List[List[Alignment]]:
    groups: List[List[Alignment]] = []
    current: List[Alignment] = []
    for item in aligned:
        if is_same_text(item):
            if current:
                groups.append(current)
                current = []
            continue
        if (item.reference and item.reference.kind != "text") or (item.final and item.final.kind != "text"):
            continue
        current.append(item)
    if current:
        groups.append(current)
    return groups


def line_range(lines: Sequence[ArticleLine]) -> str:
    if len(lines) == 1:
        return f"L{lines[0].line_no}"
    return f"L{lines[0].line_no}-L{lines[-1].line_no}"


def report_group(index: int, group: Sequence[Alignment]) -> List[str]:
    old = [item.reference for item in group if item.reference]
    new = [item.final for item in group if item.final]
    if old and new:
        title = f"{index}. 修改（中间版 {line_range(old)} → 最终版 {line_range(new)}）"
    elif old:
        title = f"{index}. 删除（中间版 {line_range(old)}）"
    else:
        title = f"{index}. 新增（最终版 {line_range(new)}）"
    result = [title]
    if old:
        result.append("   - 中间版：" + "\n     ".join(line.plain for line in old))
    if new:
        result.append("   - 最终版：" + "\n     ".join(line.raw.strip() for line in new))
    return result


def media_changes(aligned: Sequence[Alignment]) -> List[Alignment]:
    return [
        item
        for item in aligned
        if not item.reference or not item.final
        if (item.reference and item.reference.kind == "image") or (item.final and item.final.kind == "image")
    ]


def write_report(path: Path, aligned: Sequence[Alignment], reference_path: Path, final_path: Path) -> int:
    changes = group_text_changes(aligned)
    media = media_changes(aligned)
    lines = [
        "# 文章文字差异",
        "",
        f"- 中间版：`{reference_path}`",
        f"- 最终版：`{final_path}`",
        "- 说明：已忽略 Markdown 标记、列表编号和空白差异；媒体占位单独列出。",
        "",
        f"共 {len(changes)} 处正文增删改。",
        "",
    ]
    for index, group in enumerate(changes, 1):
        lines.extend(report_group(index, group))
        lines.append("")
    if media:
        lines.extend(["## 媒体占位差异", ""])
        for item in media:
            if item.reference:
                lines.append(f"- 中间版 L{item.reference.line_no} 有占位，最终版纯文本中未找到：{item.reference.raw.strip()}")
            elif item.final:
                lines.append(f"- 最终版 L{item.final.line_no} 新增图片占位，但中间版没有可复用的图片路径：{item.final.raw.strip()}")
        lines.append("")
    path.write_text("\n".join(lines).rstrip() + "\n", encoding="utf-8")
    return len(changes)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Restore Markdown formatting from a reference draft and compare article text."
    )
    parser.add_argument("reference", type=Path, help="Markdown draft that still has formatting.")
    parser.add_argument("final", type=Path, help="Final plain-text article.")
    parser.add_argument("--formatted-output", required=True, type=Path)
    parser.add_argument("--report", required=True, type=Path)
    args = parser.parse_args()

    reference = load_lines(args.reference, reference=True)
    final = load_lines(args.final, reference=False)
    aligned = align_lines(reference, final)
    write_formatted(args.formatted_output, aligned)
    change_count = write_report(args.report, aligned, args.reference, args.final)
    print(f"Formatted Markdown: {args.formatted_output}")
    print(f"Text diff report: {args.report}")
    print(f"Text changes: {change_count}")


if __name__ == "__main__":
    main()
