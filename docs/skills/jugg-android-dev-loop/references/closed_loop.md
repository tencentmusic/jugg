# Closed-loop Policy (Jugg MCP)

## Goal

Agent directly calls Jugg MCP tools to complete the Android compile/deploy/verify closed-loop and provide objective evidence. No runner scripts needed.

## Required MCP Steps

1. **`list_projects`** — confirm valid `projectDir` from IDE.
2. **`device_list`** — ensure at least one online device is available.
3. **Build + Deploy** — `compile_and_deploy` (default path: compiles then deploys) or `clean_reinstall_apk` (strong fallback: uninstalls app, clears Jugg code_cache patches, full reinstall). Use `compile_only` when no device is available or only compilation check is needed.
4. **Runtime actions** — `start_app` then `tap` for UI interaction.
5. **Verification artifacts** — `screenshot` and/or `layout_dump` (optional `record`).

## Pass Criteria

- Build/deploy path returns `status="OK"`.
- Runtime actions (`start_app`, `tap`) return `status="OK"`.
- At least one verification artifact exists on disk.

## MCP-only Policy

- Strongly prefer MCP toolchain end-to-end: `start_app`, `tap`, `layout_dump`, `screenshot`, `record`.
- Avoid direct external adb commands in normal closed-loop flow.
- If interaction is flaky, add short pre-tap delay and repeat tap (e.g., 2 taps with 1-2s interval).

## Compile Failure Triage Policy

When compile/deploy fails, use this sequence:

1. Parse `structuredContent.message` and `structuredContent.data` from MCP response.
2. Check `structuredContent.errorCode` for quick classification.
3. Try to classify the failure category:
   - `MCP_PROJECT_NOT_INITIALIZED`
   - `MCP_NO_DEVICE`
   - source compile errors (unresolved reference/syntax/import)
   - AndroidManifest/resource merge errors
   - deploy stage errors
4. If classification succeeds, provide concrete next action.
5. If classification fails and no approved auto-fallback exists, stop and ask user for confirmation.

## Auto Downgrade Policy

Agent decision flow for automatic downgrade:

1. `compile_and_deploy` fails -> retry `compile_and_deploy` (up to 3 consecutive attempts)
2. If all 3 attempts fail -> try `force_gradle_compile` (heavy fallback)
3. `force_gradle_compile` succeeds -> retry `compile_and_deploy`
4. Still fails -> try `clean_reinstall_apk`
5. `clean_reinstall_apk` fails -> stop and report to user with full diagnosis

Avoid unbounded retry loops. `force_gradle_compile` is allowed only after 3 consecutive `compile_and_deploy` failures.

## Failure Handling

- `MCP_PROJECT_NOT_INITIALIZED`: ensure IDE project is opened and Jugg initialized.
- `MCP_NO_DEVICE`: connect device/emulator, re-check `device_list`.
- `MCP_INTERNAL_ERROR` during incremental path: retry `compile_and_deploy` up to 3 times, then try `force_gradle_compile`, then `clean_reinstall_apk`.
- `MCP_INVALID_PARAMS`: verify tool arguments against schema and retry.

## Agent Response Template

Always include:

- `projectDir` used
- build path used (`compile_and_deploy` / `compile_only` / `force_gradle_compile` / `clean_reinstall_apk`)
- step-by-step status summary
- artifact absolute paths
- pass/fail verdict with evidence
- next-step suggestion on failure
