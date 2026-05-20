#!/usr/bin/env python3
"""Summarize const-ref resource capture outputs."""

from __future__ import annotations

import argparse
import csv
import re
from pathlib import Path
from statistics import mean


FINAL_RE = re.compile(
    r"final=true, .*?totalFiles=(?P<files>\d+), totalReused=(?P<reused>\d+), "
    r"totalAnalyzed=(?P<analyzed>\d+), totalCost=(?P<cost>\d+)ms"
)
PHASE_RE = re.compile(
    r"analyzeFiles phase breakdown, totalMs=(?P<total>\d+).*?"
    r"checksumMs=(?P<checksum>\d+).*?"
    r"phase1ParseMs=(?P<phase1>\d+).*?"
    r"phase1DbWriteMs=(?P<phase1db>\d+).*?"
    r"phase2RefMs=(?P<phase2>\d+).*?"
    r"phase2DbLookupMs=(?P<phase2lookup>\d+).*?"
    r"phase2DbWriteMs=(?P<phase2db>\d+)"
)
POWER_RE = re.compile(r"(CPU|Processor|Package)\s+Power:\s+([0-9.]+)\s*(mW|W)", re.IGNORECASE)


def read_metadata(run_dir: Path) -> dict[str, str]:
    metadata = {}
    path = run_dir / "metadata.env"
    if not path.exists():
        return metadata
    for line in path.read_text(errors="replace").splitlines():
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        metadata[key] = value
    return metadata


def summarize_ps(run_dir: Path) -> dict[str, str]:
    values = []
    path = run_dir / "ps.log"
    if not path.exists():
        return {}
    for line in path.read_text(errors="replace").splitlines():
        parts = line.split()
        if len(parts) < 9 or "PROCESS_EXITED" in parts:
            continue
        try:
            values.append(
                {
                    "cpu": float(parts[4]),
                    "mem": float(parts[5]),
                    "rss_mb": int(parts[6]) / 1024.0,
                }
            )
        except ValueError:
            continue
    if not values:
        return {}
    return {
        "samples": str(len(values)),
        "avg_cpu": f"{mean(v['cpu'] for v in values):.2f}",
        "peak_cpu": f"{max(v['cpu'] for v in values):.2f}",
        "avg_mem": f"{mean(v['mem'] for v in values):.2f}",
        "peak_rss_mb": f"{max(v['rss_mb'] for v in values):.1f}",
    }


def summarize_compile(run_dir: Path) -> dict[str, str]:
    path = run_dir / "compile_tail.log"
    if not path.exists():
        return {}
    text = path.read_text(errors="replace")
    final_matches = list(FINAL_RE.finditer(text))
    result = {}
    if final_matches:
        final = final_matches[-1]
        cost_ms = int(final.group("cost"))
        result.update(
            {
                "total_time_s": f"{cost_ms / 1000.0:.1f}",
                "total_files": final.group("files"),
                "reused_files": final.group("reused"),
                "analyzed_files": final.group("analyzed"),
            }
        )
    throttle_line = next((line for line in text.splitlines() if "ConstRefEngine io throttle enabled" in line), "")
    if throttle_line:
        result["throttle"] = throttle_line.split("ConstRefEngine io throttle enabled, ", 1)[-1]
    phase_totals = {
        "phase_total_ms": 0,
        "checksum_ms": 0,
        "phase1_parse_ms": 0,
        "phase2_ref_ms": 0,
        "db_ms": 0,
    }
    phase_count = 0
    for match in PHASE_RE.finditer(text):
        phase_count += 1
        phase_totals["phase_total_ms"] += int(match.group("total"))
        phase_totals["checksum_ms"] += int(match.group("checksum"))
        phase_totals["phase1_parse_ms"] += int(match.group("phase1"))
        phase_totals["phase2_ref_ms"] += int(match.group("phase2"))
        phase_totals["db_ms"] += (
            int(match.group("phase1db")) +
            int(match.group("phase2lookup")) +
            int(match.group("phase2db"))
        )
    if phase_count:
        result.update({key: str(value) for key, value in phase_totals.items()})
        result["phase_log_count"] = str(phase_count)
    result["freeze_signal"] = "yes" if "uiFreezeStarted" in text or "threadDumps-freeze" in text else "no"
    return result


def summarize_power(run_dir: Path) -> dict[str, str]:
    path = run_dir / "powermetrics.log"
    if not path.exists():
        return {}
    values_mw = []
    for line in path.read_text(errors="replace").splitlines():
        match = POWER_RE.search(line)
        if not match:
            continue
        value = float(match.group(2))
        if match.group(3).lower() == "w":
            value *= 1000.0
        values_mw.append(value)
    if not values_mw:
        return {}
    return {
        "avg_power_mw": f"{mean(values_mw):.1f}",
        "peak_power_mw": f"{max(values_mw):.1f}",
    }


def summarize_run(run_dir: Path) -> dict[str, str]:
    metadata = read_metadata(run_dir)
    result = {
        "run": run_dir.name,
        "label": metadata.get("label", ""),
        "project": metadata.get("project_dir", ""),
        "reset_cache": metadata.get("reset_cache", ""),
    }
    result.update(summarize_compile(run_dir))
    result.update(summarize_ps(run_dir))
    result.update(summarize_power(run_dir))
    return result


def print_markdown(rows: list[dict[str, str]]) -> None:
    columns = [
        "label",
        "reset_cache",
        "total_time_s",
        "total_files",
        "reused_files",
        "analyzed_files",
        "avg_cpu",
        "peak_cpu",
        "peak_rss_mb",
        "avg_power_mw",
        "peak_power_mw",
        "freeze_signal",
    ]
    print("| " + " | ".join(columns) + " |")
    print("| " + " | ".join("---" for _ in columns) + " |")
    for row in rows:
        print("| " + " | ".join(row.get(column, "") for column in columns) + " |")


def write_csv(rows: list[dict[str, str]], output: Path) -> None:
    columns = sorted({key for row in rows for key in row.keys()})
    with output.open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=columns)
        writer.writeheader()
        writer.writerows(rows)


def main() -> None:
    parser = argparse.ArgumentParser(description="Summarize const-ref resource capture directories.")
    parser.add_argument("run_dirs", nargs="+", type=Path)
    parser.add_argument("--csv", type=Path, help="Optional CSV output path.")
    args = parser.parse_args()

    rows = [summarize_run(path) for path in args.run_dirs]
    print_markdown(rows)
    if args.csv:
        write_csv(rows, args.csv)


if __name__ == "__main__":
    main()

