---
name: jugg-android-dev-loop
description: Use Jugg MCP tools for a deterministic Android modify/verify closed-loop (no runner scripts). Trigger ONLY when ALL conditions are met - (1) current project is an Android application project AND (2) user explicitly asks to build/deploy/verify on device, OR Android app source code/resources were modified and verification is the logical next step. Do NOT trigger for non-Android-app codebase even if they contain Kotlin/Java files or Android-related tooling code.
---

# Jugg MCP Android Dev Loop (Compact Router)

## Objective

Use Jugg MCP tools to finish Android tasks with a deterministic loop:

`modify -> build/deploy -> runtime verify -> collect evidence -> iterate`

Default to MCP-only flow and avoid raw adb in normal execution.

## Read Gate (Single Source of Truth)

This section is the only entry gate for loading extra docs.

Read only this file first, then output a `LoadDecision` before opening any reference file.

`LoadDecision` format:

- `stage`: `plan | execute | troubleshoot`
- `intent`: one short phrase
- `load`: file path list, or `none`
- `why`: one sentence

Reference load rules:

- Load only files required by current intent.
- Never bulk-load `references` or `examples`.
- If `load=none`, continue execution with this file only.

Intent -> first file mapping:

- runtime/evidence decision -> `references/tool_cards_runtime_observe.md`
- context troubleshooting decision -> `references/tool_cards_troubleshoot.md`
- failure signature matching -> `references/error_patterns.md`
- incremental vs gradle decision -> this file, section `Incremental Compile Limitations` (no extra load needed)
- concrete walkthrough asked by user -> `examples/*.md`

## 5-Step Loop

1. Modify sources.
2. Build/deploy:
   - default: `compile_and_deploy(projectDir)`
   - no device or compile-only ask: `compile_only(projectDir)` (same failure triage as `compile_and_deploy`, skip runtime verification)
3. Runtime actions & evidence (if needed):
   - `restart_app(projectDir)`, then interact and collect evidence per `references/tool_cards_runtime_observe.md`.
   - **If >=2 runtime tool calls or heavy-output analysis needed, delegate via Observe Delegation Policy.**
4. Verdict:
   - **PASS** → step 5.
   - **FAIL** → back to step 1 (fix source), respect retry budget.
   - **INCONCLUSIVE** → gather more evidence or ask user when exhausted all available methods.
5. Final staging:
   - clear `${projectDir}/build/mcp_fetch/final`
   - copy final artifacts as `final_screenshot.png` / `final_record.mp4`

## Core Rules

- `projectDir`: use current working directory by default.
- Max autonomous retries for same failure category: `3`.
- Never tap with guessed coordinates; never claim success without artifact evidence.
- For detailed runtime/observe procedures, load `references/tool_cards_runtime_observe.md`.
- Unknown/high-risk failure: stop and ask user.

## Observe Delegation Policy

`layout_dump` / `tap` / `screenshot` / recording produce large context (XML, images, video). Isolate observation from main agent context when possible.

Delegate (prefer sub-agent, fallback to main-agent-with-summarize) when **any** condition is true:

1. **>=2 runtime tool calls** — interaction chains, recording sessions, combined evidence.
2. **Analysis required on heavy output** — even 1 tool call, if the result needs reasoning over large data (e.g., `layout_dump` XML to judge layout correctness, or video/screenshot to verify visual behavior).

Direct call from main agent only when: single tool call **and** result is used as-is without analysis (e.g., one `activity_stack` to confirm page, one `screenshot` as final proof with verdict already decided).

**Default**: when unsure, delegate.

Sub-agent receives `projectDir` + intent + target hints, returns only `{verdict, summary, artifacts, issues}`, never raw XML/image. If sub-agent is not available, execute in main agent but summarize findings into the same structured format and discard raw tool output before continuing.

## Crash Triage Loop

When runtime result looks abnormal (unexpected activity, dead process, or missing UI evidence):

1. Call `crash_report(projectDir)` to try to find out the reason.
2. Fallback to `adb logcat` if step 1 is not working.
3. Continue normal 5-Step loop after fix.

## Async Compile Rule (Mandatory)

For `compile_and_deploy` or `force_gradle_compile`:

- If `isFinal=false`, immediately delegate polling to an `awaiter` sub-agent.
- Poll with `get_compile_status(jobId)` and follow `pollIntervalSuggestedMs` when present.
- Determine result only by terminal compile status.
- If `status=unknown`, treat as invalid job/context; stop and re-check `jobId` source.

## Incremental Compile Limitations

Jugg incremental compile supports **only** these annotation processors / compiler plugins:

`DataBinding`, `ViewBinding`, `Compose`, `Parcelize`, `Page` for Kuikly, `JsonClass` for Moshi

Key behaviors:

- **Unsupported annotations**: if you only modify regular source code (not adding/changing annotations), the change takes effect normally. But if you **add new annotations or change annotation values** for unsupported processors (Dagger/Hilt, Room, Glide, etc.), the annotation processor will not re-run and the change **silently does not take effect**.
- **Transform / bytecode instrumentation**: Jugg compiles `source → class → dex` without Gradle Transform. Any file recompiled by Jugg will have its **previously instrumented bytecode replaced by raw compiler output** (e.g., ASM-injected init hooks, AOP aspects disappear from the recompiled classes).

**Decision**: if your change adds/modifies unsupported annotations, or the changed file relies on Transform instrumentation, use `force_gradle_compile` directly. For symptom matching details, load `references/error_patterns.md`.

## Fallback Chain

Use this order for compile/deploy failures:

1. Parse `status/message/errorCode/data/artifacts`.
2. Retry `compile_and_deploy` up to 3 times.
3. If still no way to fix the expected error use `force_gradle_compile` (heavy), finish async polling, and retry `compile_and_deploy`. On final failure inspect `${projectDir}/build/jugg/log/compile_latest.log`.
4. If still broken, try `clean_reinstall_apk`.
5. If still unclear, stop and confirm with user.
6. Remote troubleshooting (`request_remote_ssh_info`) requires explicit user consent.

Special case:

- `MCP_NO_DEVICE`: stop and ask user to connect/start device, or switch to `compile_only`.

## Minimal MCP Result Contract

Use these fields for decisions:

- `status`: `OK | ERROR`
- `message`: concise result/error
- `data`: tool-specific structured output
- `artifacts`: produced files
- `errorCode`: stable failure code or `null`

## Error Handling Gate

- Known pattern + low-risk fix: auto apply.
- Unknown pattern or confidence `< 0.8`: ask user before large changes.
- For detailed mapping and signatures, load `references/error_patterns.md`.

## Reference Entry Points

- Runtime/observe cards: `references/tool_cards_runtime_observe.md`
- Troubleshoot cards: `references/tool_cards_troubleshoot.md`
- Error signatures/fixes: `references/error_patterns.md`
- Walkthroughs:
  - `examples/01_fix_compile_error.md`
  - `examples/02_ui_change_verify.md`
  - `examples/03_deploy_fallback.md`

## Response Checklist

Each result summary should include:

- `projectDir` used
- build path used
- key steps and statuses
- artifact absolute paths
- final pass/fail verdict
- next action on failure
