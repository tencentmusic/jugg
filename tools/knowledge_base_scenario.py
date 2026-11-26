#!/usr/bin/env python3
"""Temporarily remove the AI knowledge base; recover tracked files from the index."""

import argparse
from pathlib import Path
import re
import shutil
import subprocess


ROOT = Path(__file__).resolve().parent.parent
TARGETS = ["AGENTS.md", "docs/ai"]


def git(*args):
    return subprocess.check_output(["git", "-C", str(ROOT), *args], text=True)


def remove():
    # Checkout cannot recover local edits or untracked/ignored knowledge files.
    changes = git("status", "--porcelain", "--untracked-files=all", "--ignored",
                  "--", *TARGETS)
    if changes:
        raise RuntimeError("Targets must be clean before remove:\n" + changes)
    agents = ROOT / "AGENTS.md"
    original = agents.read_text(encoding="utf-8")
    updated = re.sub(r"^## Mandatory AI workflow\n.*?(?=^## |\Z)", "",
                     original, flags=re.MULTILINE | re.DOTALL)
    updated = updated.replace(
        "Write current maintainer documentation in English in `docs/ai/`, "
        "the sole current knowledge base. ", "")
    updated = "".join(line for line in updated.splitlines(keepends=True)
                      if "docs/ai" not in line)
    agents.write_text(updated, encoding="utf-8")
    shutil.rmtree(ROOT / "docs/ai")
    print("Removed knowledge-base instructions and docs/ai (unstaged).")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["remove", "recover"])
    args = parser.parse_args()
    try:
        if args.command == "remove":
            remove()
        else:
            git("checkout", "--", *TARGETS)
            print("Recovered AGENTS.md and docs/ai from the Git index.")
    except (RuntimeError, OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, str(error) + "\n")


if __name__ == "__main__":
    main()
