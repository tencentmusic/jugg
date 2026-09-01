#!/usr/bin/env python3
"""Validate Jugg Wiki links, configured routes, and built route artifacts."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from urllib.parse import unquote, urlsplit


MARKDOWN_LINK_RE = re.compile(r"!?\[[^\]]*]\(([^)]+)\)")
HTML_LINK_RE = re.compile(r"(?:href|src)\s*=\s*['\"]([^'\"]+)['\"]", re.IGNORECASE)
CONFIG_LINK_RE = re.compile(r"\blink\s*:\s*['\"]([^'\"]+)['\"]")
FENCED_CODE_RE = re.compile(r"```.*?```|~~~.*?~~~", re.DOTALL)
INLINE_CODE_RE = re.compile(r"`[^`\n]+`")
SKIPPED_DIRS = {"node_modules", "dist", "cache", ".git"}
SOURCE_SUFFIXES = {".md", ".mts", ".ts", ".js", ".mjs", ".html"}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--wiki-root", type=Path, default=Path("docs/wiki"))
    parser.add_argument("--forbid-source-text", action="append", default=[])
    parser.add_argument("--expect-html-route", action="append", default=[])
    parser.add_argument("--expect-html-text", action="append", default=[], metavar="ROUTE::TEXT")
    parser.add_argument("--expect-removed-route", action="append", default=[])
    parser.add_argument("--expect-compatible-route", action="append", default=[])
    return parser.parse_args()


def iter_source_files(root: Path, suffixes: set[str]) -> list[Path]:
    return sorted(
        path
        for path in root.rglob("*")
        if path.is_file()
        and path.suffix in suffixes
        and not any(part in SKIPPED_DIRS for part in path.relative_to(root).parts)
    )


def clean_target(raw_target: str) -> str | None:
    target = raw_target.strip().split(maxsplit=1)[0].strip("<>")
    parsed = urlsplit(target)
    if parsed.scheme or parsed.netloc or target.startswith(("#", "mailto:", "tel:", "data:")):
        return None
    return unquote(parsed.path)


def source_candidates(root: Path, source: Path, target: str) -> list[Path]:
    if not target:
        return [source]
    base = root if target.startswith("/") else source.parent
    relative = target.lstrip("/")
    resolved = (base / relative).resolve()
    public = (root / "public" / relative).resolve() if target.startswith("/") else None
    if Path(relative).suffix:
        return [resolved] + ([public] if public else [])
    return [resolved.with_suffix(".md"), resolved / "index.md"]


def validate_document_links(root: Path) -> list[str]:
    errors: list[str] = []
    for source in iter_source_files(root, {".md"}):
        content = FENCED_CODE_RE.sub("", source.read_text(encoding="utf-8"))
        content = INLINE_CODE_RE.sub("", content)
        targets = MARKDOWN_LINK_RE.findall(content) + HTML_LINK_RE.findall(content)
        for raw_target in targets:
            target = clean_target(raw_target)
            if target is None:
                continue
            candidates = source_candidates(root, source, target)
            if not any(candidate.exists() for candidate in candidates):
                display = source.relative_to(root)
                errors.append(f"{display}: unresolved link {raw_target}")
    return errors


def validate_config_routes(root: Path) -> list[str]:
    config = root / ".vitepress" / "config.mts"
    if not config.exists():
        return [f"missing config: {config}"]
    errors: list[str] = []
    for route in CONFIG_LINK_RE.findall(config.read_text(encoding="utf-8")):
        target = clean_target(route)
        if target is None:
            continue
        candidates = source_candidates(root, config, target)
        if not any(candidate.exists() for candidate in candidates):
            errors.append(f".vitepress/config.mts: route has no source page {route}")
    return errors


def validate_language_mirrors(root: Path) -> list[str]:
    english = {
        path.relative_to(root).as_posix()
        for path in iter_source_files(root, {".md"})
        if path.relative_to(root).parts[0] != "zh"
    }
    chinese_root = root / "zh"
    chinese = {
        path.relative_to(chinese_root).as_posix()
        for path in iter_source_files(chinese_root, {".md"})
    }
    errors: list[str] = []
    for path in sorted(chinese - english):
        errors.append(f"missing English mirror for zh/{path}")
    for path in sorted(english - chinese):
        errors.append(f"missing Chinese mirror for {path}")
    return errors


def config_block(content: str, start: str, end: str) -> str:
    start_index = content.index(start)
    end_index = content.index(end, start_index)
    return content[start_index:end_index]


def normalized_config_links(content: str, start: str, end: str, locale_prefix: str = "") -> list[str]:
    links = CONFIG_LINK_RE.findall(config_block(content, start, end))
    if not locale_prefix:
        return links
    return [link.removeprefix(locale_prefix) or "/" for link in links]


def validate_locale_navigation(root: Path) -> list[str]:
    config = root / ".vitepress" / "config.mts"
    if not config.exists():
        return [f"missing config: {config}"]
    content = config.read_text(encoding="utf-8")
    errors: list[str] = []
    pairs = [
        ("const englishNav", "const englishSidebar", "const chineseNav", "const chineseSidebar", "nav"),
        ("const englishSidebar", "const chineseNav", "const chineseSidebar", "export default", "sidebar"),
    ]
    for english_start, english_end, chinese_start, chinese_end, label in pairs:
        english = normalized_config_links(content, english_start, english_end)
        chinese = normalized_config_links(content, chinese_start, chinese_end, "/zh")
        if english != chinese:
            errors.append(f"English and Chinese {label} routes do not have the same order")
    return errors


def route_html_candidates(dist: Path, route: str) -> list[Path]:
    path = route.split("#", 1)[0].split("?", 1)[0].strip("/")
    if not path:
        return [dist / "index.html"]
    return [dist / f"{path}.html", dist / path / "index.html"]


def existing_route_html(dist: Path, route: str) -> Path | None:
    return next((path for path in route_html_candidates(dist, route) if path.exists()), None)


def validate_source_text(root: Path, forbidden: list[str]) -> list[str]:
    errors: list[str] = []
    sources = iter_source_files(root, SOURCE_SUFFIXES)
    for text in forbidden:
        matches = [str(path.relative_to(root)) for path in sources if text in path.read_text(encoding="utf-8")]
        if matches:
            errors.append(f"forbidden source text {text!r}: {', '.join(matches)}")
    return errors


def validate_built_routes(args: argparse.Namespace, root: Path) -> list[str]:
    dist = root / ".vitepress" / "dist"
    errors: list[str] = []
    for route in args.expect_html_route + args.expect_compatible_route:
        if existing_route_html(dist, route) is None:
            errors.append(f"missing built HTML for route {route}")
    for route in args.expect_removed_route:
        if existing_route_html(dist, route) is not None:
            errors.append(f"removed route still generates HTML {route}")
    for expectation in args.expect_html_text:
        if "::" not in expectation:
            errors.append(f"invalid --expect-html-text value {expectation!r}; use ROUTE::TEXT")
            continue
        route, text = expectation.split("::", 1)
        html = existing_route_html(dist, route)
        if html is None:
            errors.append(f"missing built HTML for route {route}")
        elif text not in html.read_text(encoding="utf-8"):
            errors.append(f"built HTML for {route} does not contain {text!r}")
    return errors


def main() -> int:
    args = parse_args()
    root = args.wiki_root.resolve()
    if not root.is_dir():
        print(f"Wiki root does not exist: {root}", file=sys.stderr)
        return 2

    errors = validate_language_mirrors(root)
    errors.extend(validate_locale_navigation(root))
    errors.extend(validate_document_links(root))
    errors.extend(validate_config_routes(root))
    errors.extend(validate_source_text(root, args.forbid_source_text))
    errors.extend(validate_built_routes(args, root))
    if errors:
        print("Wiki validation failed:")
        for error in errors:
            print(f"- {error}")
        return 1
    print("Wiki validation passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
