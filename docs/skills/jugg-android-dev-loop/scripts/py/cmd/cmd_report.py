"""cmd_report — prepare, review, and upload a Jugg diagnostics bundle."""

from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import jugglib


def cmd_report(args: list[str]) -> None:
    if args:
        print(f"Unknown option: {args[0]}", file=sys.stderr)
        sys.exit(1)
    project_dir = jugglib.resolve_project_dir()
    port = jugglib.resolve_port()
    prepared = _call(port, "report-prepare", {"projectDir": project_dir})
    _print_prepared(prepared)
    try:
        confirmed = input("Upload this diagnostics bundle? [Y/n]: ").strip().lower()
    except (EOFError, KeyboardInterrupt):
        print()
        confirmed = None
    if confirmed not in ("", "y", "yes"):
        print("Upload canceled. The diagnostics bundle was kept locally.")
        return
    data = prepared.get("data", {})
    uploaded = _call(
        port,
        "report-upload",
        {
            "projectDir": project_dir,
            "reportId": data.get("reportId", ""),
            "sha256": data.get("sha256", ""),
        },
    )
    jugglib.print_kv(uploaded)


def _call(port: int, tool: str, params: dict) -> dict:
    structured = jugglib.extract_structured(jugglib.raw_call(port, tool, params))
    if structured.get("status") != "OK":
        print(
            f"status: ERROR\nmessage: {structured.get('message', 'Unknown error')}",
            file=sys.stderr,
        )
        sys.exit(1)
    return structured


def _print_prepared(prepared: dict) -> None:
    data = prepared.get("data", {})
    print("Diagnostics bundle prepared:")
    print(f"  Local file: {data.get('filePath', '')}")
    print(f"  Total size: {_format_size(data.get('size', 0))}")
    print(f"  Upload destination: {data.get('uploadUrl', '')}")
    print("\nFiles to upload:")
    entries = sorted(
        data.get("entries", []),
        key=lambda entry: not entry.get("path", "").startswith("diagnostics/logs/"),
    )
    for entry in entries:
        path = entry.get("path", "")
        size = _format_size(entry.get("size", 0))
        print(f"  {path}  ({size})")


def _format_size(size: int) -> str:
    if size < 1024:
        return f"{size} B"
    if size < 1024 * 1024:
        return f"{size / 1024:.1f} KB"
    return f"{size / (1024 * 1024):.1f} MB"
