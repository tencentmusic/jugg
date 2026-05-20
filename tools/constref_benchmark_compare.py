#!/usr/bin/env python3
"""Compare ConstRefFullScanResourceBenchmarkTest summary JSON outputs."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any


def load_summary(path: Path) -> dict[str, Any]:
    if path.is_dir():
        path = path / "constref_fullscan_summary.json"
    return json.loads(path.read_text())


def get_round(summary: dict[str, Any], round_name: str) -> dict[str, Any]:
    value = summary.get(round_name)
    if not isinstance(value, dict):
        raise ValueError(f"missing round {round_name}")
    return value


def format_ms(value: Any) -> str:
    if not isinstance(value, (int, float)):
        return ""
    return f"{value / 1000.0:.1f}s"


def format_ratio(value: Any) -> str:
    if not isinstance(value, (int, float)):
        return ""
    return f"{value:.2f}"


def row(label: str, round_name: str, data: dict[str, Any]) -> list[str]:
    full_scan = data.get("fullScan", {})
    cpu_load = data.get("cpuLoad", {})
    heap = data.get("heap", {})
    phase = data.get("phaseBreakdown", {})
    io_proxy = data.get("ioProxy", {})
    return [
        label,
        round_name,
        format_ms(data.get("durationMs")),
        str(full_scan.get("totalFiles", "")),
        str(full_scan.get("totalReused", "")),
        str(full_scan.get("totalAnalyzed", "")),
        format_ratio(data.get("processCpuToWallRatio")),
        format_ratio(cpu_load.get("p95")),
        format_ratio(heap.get("peakMb")),
        format_ms(phase.get("phaseTotalMs")),
        str(io_proxy.get("dbSizeBytes", "")),
        str(full_scan.get("throttle", "")),
    ]


def print_table(before_label: str, before: dict[str, Any], after_label: str, after: dict[str, Any]) -> None:
    headers = [
        "label",
        "round",
        "duration",
        "files",
        "reused",
        "analyzed",
        "cpu/wall",
        "cpu p95",
        "heap peak MB",
        "phase logged",
        "db bytes",
        "throttle",
    ]
    print("| " + " | ".join(headers) + " |")
    print("| " + " | ".join("---" for _ in headers) + " |")
    for label, summary in ((before_label, before), (after_label, after)):
        for round_name in ("cold", "warm"):
            print("| " + " | ".join(row(label, round_name, get_round(summary, round_name))) + " |")


def print_delta(before: dict[str, Any], after: dict[str, Any]) -> None:
    print()
    print("Delta after - before:")
    for round_name in ("cold", "warm"):
        before_round = get_round(before, round_name)
        after_round = get_round(after, round_name)
        duration_delta = after_round.get("durationMs", 0) - before_round.get("durationMs", 0)
        cpu_delta = after_round.get("processCpuToWallRatio", 0.0) - before_round.get("processCpuToWallRatio", 0.0)
        print(f"- {round_name}: duration {duration_delta / 1000.0:.1f}s, cpu/wall {cpu_delta:.2f}")


def main() -> None:
    parser = argparse.ArgumentParser(description="Compare const-ref benchmark summaries.")
    parser.add_argument("--before", required=True, type=Path, help="Before output dir or summary JSON.")
    parser.add_argument("--after", required=True, type=Path, help="After output dir or summary JSON.")
    parser.add_argument("--before-label", default="before")
    parser.add_argument("--after-label", default="after")
    args = parser.parse_args()

    before = load_summary(args.before)
    after = load_summary(args.after)
    print_table(args.before_label, before, args.after_label, after)
    print_delta(before, after)


if __name__ == "__main__":
    main()
