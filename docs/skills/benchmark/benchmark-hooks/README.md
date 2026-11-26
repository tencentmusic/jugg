# Jugg Benchmark — Agent Hooks

Purpose: Give different agents the same steps to verify that Jugg agent hooks behave as expected.

This is an agent-hook integration benchmark, not a CLI command benchmark. Cases check that hooks are configured and genuinely triggered by the tested agent's file edits, commands, and session-ending action. Do not call hook scripts directly or substitute a fake `jugg.py status` for the real trigger path.

Source files used to create Jugg pending changes must be under `app/src/main/java/com/example/myapplication/`. Only add, move, or modify isolated files named like `Hook*Trigger.kt`; do not edit existing business files. A case checking that non-sourceset files are not blocked will explicitly use `hook_benchmark_scratch/`.

Use relative paths in reports by default. An absolute hook-script path printed verbatim by the client may be retained in the feedback excerpt to prove what the agent actually saw.

## Sources of Truth

- Hook installation: `docs/skills/install/agent_setup.md`
- Behavior: `docs/ai/04_engineering_ide.md` and `docs/ai/08_mcp_tools_list.md`

## Files

| File | Coverage |
|------|----------|
| `l2_agent_hooks.md` | Real agent edits and commands trigger edit/command hooks; sourceset raw Gradle blocking and repeated-command allowance (visible warning for Codex/Claude, possibly silent for Cursor/Gemini); non-sourceset files and `jugg gradle-build` are not incorrectly blocked; added, modified, and multiple same-turn files are detected. |
| `l3_agent_feedback.md` | One added-source command path and one stop path test feedback visibility: initial raw Gradle block, repeated allowance, `jugg gradle-build` exception, initial stop block, and repeated allowance. Cursor/Gemini may allow the second stop silently; a human confirms the second Codex/Claude warning. |
