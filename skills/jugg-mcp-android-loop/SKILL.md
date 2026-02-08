---
name: jugg-mcp-android-loop
description: Run a closed-loop Android engineering workflow through Jugg MCP, including project discovery, compile/deploy (with clean_reinstall fallback), device execution, and verification artifact collection. Use when Codex/Claude Code needs to autonomously control Android build-and-verify flow end-to-end, especially for tasks like "compile and validate on device", "run MCP verification loop", "collect screenshot/layout artifacts after deploy", or "stabilize CI-like local verification via MCP tools".
---

# Jugg MCP Android Loop

## Overview

Execute deterministic Android compile-and-verify loops via Jugg MCP so the agent can complete tasks without manual IDE clicking.

Strongly prefer MCP tools and avoid direct external adb commands.

## Quick Start

- Run `python3 skills/jugg-mcp-android-loop/scripts/jugg_mcp_loop.py --project-dir <ABS_PROJECT_DIR> --fallback-clean-reinstall`.
- Add `--serial <device_serial>` to pin a device.
- Add `--mode clean_reinstall` when you need a strong fallback path.
- For team rollout, run `bash skills/jugg-mcp-android-loop/scripts/install_skill.sh --force` then `python3 skills/jugg-mcp-android-loop/scripts/setup_mcp_clients.py --all`.

## Recommended Command Templates

One-line (copy/paste):

`python3 skills/jugg-mcp-android-loop/scripts/jugg_mcp_loop.py --project-dir <ABS_PROJECT_DIR> --fallback-clean-reinstall --start-activity .MainActivity --tap-x 540 --tap-y 530 --pre-tap-delay-sec 2.0 --tap-repeat 2 --tap-interval-sec 1.5`

Multi-line (readable):

```bash
python3 skills/jugg-mcp-android-loop/scripts/jugg_mcp_loop.py \
  --project-dir <ABS_PROJECT_DIR> \
  --fallback-clean-reinstall \
  --start-activity .MainActivity \
  --tap-x 540 --tap-y 530 \
  --pre-tap-delay-sec 2.0 --tap-repeat 2 --tap-interval-sec 1.5
```

## Workflow

1. Probe MCP endpoint (`12320..12329`) or use `--port`.
2. Run MCP handshake (`initialize` + `notifications/initialized`).
3. Check tool availability with `tools/list`.
4. Validate project with `list_projects` and ensure `projectDir` is correct.
5. Check device readiness with `device_list`.
6. Execute build path:
   - `compile` + `deploy` (default)
   - or `clean_reinstall` (explicit mode)
7. Execute runtime actions via MCP: `app_start` then `tap`.
8. Collect verification artifacts:
   - `screenshot`
   - `layout_dump`
   - optional `record`
9. Return a machine-readable summary with pass/fail, step results, and artifact paths.

## Failure-first Rules

When compile/deploy fails, follow this strict order:

1. **Parse error first**: read MCP `structuredContent.message` and `structuredContent.data` for root-cause clues.
2. **Try deterministic diagnosis**: classify known categories (project not initialized / no device / source compile error / manifest/resource merge issue / deploy failure).
3. **Use auto downgrade only when allowed**:
   - if current run already configured fallback (for example `--fallback-clean-reinstall`) or
   - if prior conversation explicitly allows automatic downgrade.
4. **Stop and confirm with user** when root cause is still unclear and no approved fallback exists.

Do not silently loop retries without diagnosis.

## Decision Rules

- Prefer `--mode compile_deploy` for fast iteration.
- Switch to `--mode clean_reinstall` when incremental path is unstable.
- If `compile`/`deploy` fails and `--fallback-clean-reinstall` is set, script will internally try `force_gradle_compile` (if available), retry `compile`/`deploy`, then fallback to `clean_reinstall`.
- Treat missing devices as hard failure (error code `MCP_NO_DEVICE`).
- Strongly prefer MCP-only execution (`app_start`, `tap`, `layout_dump`, `screenshot`, `record`) and avoid raw adb in normal flow.

## Required Capabilities (Next Implementation)

This skill relies on two capabilities that should be implemented in MCP/tooling:

1. **Compile/deploy error passthrough to MCP client**
   - MCP response should expose structured failure details (not only generic failed message).
   - Agent can diagnose from response payload directly.
2. **Downgrade tool(s)**
   - Provide explicit downgrade path/tool (for example clean-reinstall fallback toolchain).
   - Agent executes downgrade automatically only under approved policy (see Failure-first Rules).

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
