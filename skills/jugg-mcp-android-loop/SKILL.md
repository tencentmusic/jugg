---
name: jugg-mcp-android-loop
description: Run a closed-loop Android engineering workflow through Jugg MCP, including project discovery, compile/deploy (with clean_reinstall fallback), device execution, and verification artifact collection. Use when Codex/Claude Code needs to autonomously control Android build-and-verify flow end-to-end, especially for tasks like "compile and validate on device", "run MCP verification loop", "collect screenshot/layout artifacts after deploy", or "stabilize CI-like local verification via MCP tools".
---

# Jugg MCP Android Loop

## Overview

Execute deterministic Android compile-and-verify loops via Jugg MCP so the agent can complete tasks without manual IDE clicking.

## Quick Start

- Run `python3 skills/jugg-mcp-android-loop/scripts/jugg_mcp_loop.py --project-dir <ABS_PROJECT_DIR>`.
- Add `--serial <device_serial>` to pin a device.
- Add `--mode clean_reinstall` when you need a strong fallback path.
- For team rollout, run `bash skills/jugg-mcp-android-loop/scripts/install_skill.sh --force` then `python3 skills/jugg-mcp-android-loop/scripts/setup_mcp_clients.py --all`.

## Workflow

1. Probe MCP endpoint (`12320..12329`) or use `--port`.
2. Run MCP handshake (`initialize` + `notifications/initialized`).
3. Check tool availability with `tools/list`.
4. Validate project with `list_projects` and ensure `projectDir` is correct.
5. Check device readiness with `device_list`.
6. Execute build path:
   - `compile` + `deploy` (default)
   - or `clean_reinstall` (explicit mode)
7. Execute runtime action: `restart_app`.
8. Collect verification artifacts:
   - `screenshot`
   - `layout_dump`
   - optional `record`
9. Return a machine-readable summary with pass/fail, step results, and artifact paths.

## Decision Rules

- Prefer `--mode compile_deploy` for fast iteration.
- Switch to `--mode clean_reinstall` when incremental path is unstable.
- If `compile`/`deploy` fails and `--fallback-clean-reinstall` is set, auto-run `clean_reinstall`.
- Treat missing devices as hard failure (error code `MCP_NO_DEVICE`).

## Output Contract

The script prints a final JSON summary:

- `ok`: overall pass/fail
- `endpoint`: MCP endpoint
- `mode`: execution mode
- `steps[]`: each step name + status + message
- `artifacts[]`: collected file paths from MCP `artifacts`

Use this summary as the single source of truth in agent responses.

## Resources

### scripts/

- `scripts/jugg_mcp_loop.py`: deterministic closed-loop runner for MCP workflow.
- `scripts/install_skill.sh`: install the skill folder to Codex skill home for team distribution.
- `scripts/setup_mcp_clients.py`: configure MCP endpoint for Codex, Claude Code, and Gemini clients.

### references/

- `references/closed_loop.md`: execution policy and troubleshooting guidance.
- `references/client_setup.md`: client-specific installation and MCP configuration guide.
- `references/team_onboarding.md`: one-page onboarding for teammates (5-minute setup + troubleshooting).
