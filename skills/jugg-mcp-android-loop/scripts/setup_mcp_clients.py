#!/usr/bin/env python3
import argparse
import json
import os
import shutil
import subprocess
from pathlib import Path
from typing import List


def run_command(cmd: List[str]) -> tuple[int, str]:
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, check=False)
        out = (proc.stdout or "") + ("\n" + proc.stderr if proc.stderr else "")
        return proc.returncode, out.strip()
    except FileNotFoundError:
        return 127, f"command not found: {cmd[0]}"


def setup_codex(endpoint: str, server_name: str) -> str:
    code, output = run_command(["codex", "mcp", "add", server_name, "--url", endpoint])
    if code == 0:
        return "Codex: configured via `codex mcp add`."

    config_path = Path.home() / ".codex" / "config.toml"
    config_path.parent.mkdir(parents=True, exist_ok=True)
    existing = config_path.read_text(encoding="utf-8") if config_path.exists() else ""
    section = f"[mcp_servers.{server_name}]"
    if section in existing:
        return f"Codex: section already exists in {config_path}. (CLI failed: {output})"

    block = f"\n\n{section}\nurl = \"{endpoint}\"\n"
    config_path.write_text(existing + block, encoding="utf-8")
    return f"Codex: appended config to {config_path}. (CLI failed: {output})"


def setup_claude(endpoint: str, server_name: str) -> str:
    code, output = run_command(["claude", "mcp", "add", "--transport", "http", server_name, endpoint])
    if code == 0:
        return "Claude Code: configured via `claude mcp add --transport http`."

    return (
        "Claude Code: CLI setup failed. Run manually:\n"
        f"  claude mcp add --transport http {server_name} {endpoint}\n"
        f"Reason: {output}"
    )


def setup_gemini(endpoint: str, server_name: str) -> str:
    config_path = Path.home() / ".gemini" / "settings.json"
    config_path.parent.mkdir(parents=True, exist_ok=True)

    if config_path.exists():
        try:
            config = json.loads(config_path.read_text(encoding="utf-8"))
        except Exception:
            backup = config_path.with_suffix(".json.bak")
            shutil.copy2(config_path, backup)
            config = {}
            note = f"Gemini: invalid JSON detected, backed up to {backup}. "
        else:
            note = ""
    else:
        config = {}
        note = ""

    mcp_servers = config.get("mcpServers")
    if not isinstance(mcp_servers, dict):
        mcp_servers = {}
        config["mcpServers"] = mcp_servers

    mcp_servers[server_name] = {
        "httpUrl": endpoint
    }

    config_path.write_text(json.dumps(config, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return f"{note}Gemini CLI: configured in {config_path}."


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Setup Jugg MCP endpoint for Codex, Claude Code, and Gemini")
    parser.add_argument("--endpoint", default="http://localhost:12320/mcp", help="MCP endpoint URL")
    parser.add_argument("--server-name", default="jugg", help="MCP server name")
    parser.add_argument("--codex", action="store_true", help="Setup Codex")
    parser.add_argument("--claude", action="store_true", help="Setup Claude Code")
    parser.add_argument("--gemini", action="store_true", help="Setup Gemini")
    parser.add_argument("--all", action="store_true", help="Setup all clients (default if none selected)")
    return parser.parse_args()


def main() -> int:
    args = parse_args()

    selected = args.all or not (args.codex or args.claude or args.gemini)
    do_codex = selected or args.codex
    do_claude = selected or args.claude
    do_gemini = selected or args.gemini

    print(f"Endpoint: {args.endpoint}")
    print(f"Server: {args.server_name}\n")

    results = []
    if do_codex:
        results.append(setup_codex(args.endpoint, args.server_name))
    if do_claude:
        results.append(setup_claude(args.endpoint, args.server_name))
    if do_gemini:
        results.append(setup_gemini(args.endpoint, args.server_name))

    print("\n".join(f"- {item}" for item in results))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
