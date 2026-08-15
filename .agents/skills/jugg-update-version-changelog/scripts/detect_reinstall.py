#!/usr/bin/env python3
"""Determine whether a Jugg release must reinstall the IDEA plugin."""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path


REINSTALL_PREFIXES = {
    "idea/src/ide_entry/": "IDE entry classes are loaded by the host plugin classloader",
    "idea/src/main/resources/": "IDE plugin resources are loaded from the installed plugin",
    "main/src/main/java/com/sickworm/intellij/jugg/platform/": "Platform API is shared with the host plugin classloader",
    "main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/": "MCP HTTP server is started by the host plugin classloader",
    "deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/api/": "Platform bridge device API is shared with the host plugin classloader",
    "gradle/": "Gradle build metadata can change the packaged plugin",
}

REINSTALL_FILES = {
    "build.gradle": "Root build metadata can change the packaged plugin",
    "settings.gradle": "Project settings can change the packaged plugin",
    "gradle.properties": "Gradle properties can change the packaged plugin",
    "idea/build.gradle": "IDE plugin build metadata can change the packaged plugin",
    "idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/IdeaPlatformApi.kt": "Platform bridge implementation is loaded by the host plugin classloader",
    "main/src/main/java/com/sickworm/intellij/jugg/deploy/IDeviceAdb.kt": "Platform bridge device type is shared with the host plugin classloader",
    "main/src/main/java/com/sickworm/intellij/jugg/project/runtime/RuntimeInfo.kt": "Platform bridge runtime type is shared with the host plugin classloader",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=".", help="Jugg repository root (default: current directory)")
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--range", dest="commit_range", help="Git revision range, for example HEAD~3..HEAD")
    source.add_argument("--files", nargs="+", help="Changed repository-relative file paths")
    return parser.parse_args()


def resolve_repo(path: str) -> Path:
    result = subprocess.run(
        ["git", "-C", path, "rev-parse", "--show-toplevel"],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise ValueError(result.stderr.strip() or f"Not a Git repository: {path}")
    return Path(result.stdout.strip())


def changed_files(repo: Path, commit_range: str) -> list[str]:
    result = subprocess.run(
        ["git", "-C", str(repo), "diff", "--name-only", "--diff-filter=ACDMRTUXB", commit_range],
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise ValueError(result.stderr.strip() or f"Invalid revision range: {commit_range}")
    return [line for line in result.stdout.splitlines() if line]


def find_reinstall_reasons(files: list[str]) -> list[dict[str, str]]:
    reasons: list[dict[str, str]] = []
    for file_name in files:
        reason = REINSTALL_FILES.get(file_name)
        if reason is None:
            reason = next((value for prefix, value in REINSTALL_PREFIXES.items() if file_name.startswith(prefix)), None)
        if reason is not None:
            reasons.append({"file": file_name, "reason": reason})
    return reasons


def main() -> int:
    args = parse_args()
    try:
        repo = resolve_repo(args.repo)
        files = args.files or changed_files(repo, args.commit_range)
    except ValueError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2

    files = sorted(set(files))
    reinstall_files = find_reinstall_reasons(files)
    print(json.dumps({
        "isNeedReinstall": bool(reinstall_files),
        "checkedFiles": files,
        "reinstallFiles": reinstall_files,
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
