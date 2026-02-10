# State Machine (Jugg MCP Android Loop)

## States

- `IDLE`: waiting for task or user input
- `ANALYZING`: parse user goal, current failure, and constraints
- `MODIFYING`: apply code/config changes
- `COMPILING`: build verification stage
- `DEPLOYING`: install/deploy artifacts to device (deploy or clean_reinstall_apk)
- `OBSERVING`: collect runtime signals and artifacts
- `VALIDATING`: decide pass/fail against acceptance criteria
- `RECOVERING`: deterministic fallback and bounded retry

## Transition Rules

- `IDLE -> ANALYZING`: receive new task or follow-up failure
- `ANALYZING -> MODIFYING`: code/config updates are needed
- `ANALYZING -> COMPILING`: no modification needed, verify directly
- `MODIFYING -> COMPILING`: modification applied
- `COMPILING -> DEPLOYING`: compile succeeds
- `COMPILING -> MODIFYING`: compile fails with known and fixable pattern
- `COMPILING -> RECOVERING`: compile fails but no low-risk fix
- `DEPLOYING -> OBSERVING`: deploy/start succeeds
- `DEPLOYING -> RECOVERING`: install/start failure
- `OBSERVING -> VALIDATING`: required observation collected
- `VALIDATING -> IDLE`: acceptance criteria pass
- `VALIDATING -> MODIFYING`: criteria fail and root cause is actionable
- `ANY -> RECOVERING`: MCP/tool infra error or timeout

## Allowed Actions by State

- `ANALYZING`: `list_projects`, `device_list`, read previous context
- `MODIFYING`: apply focused patch only; avoid broad refactor without confirmation
- `COMPILING`: `compile_and_deploy` (default), `compile_only` (no device), or `force_gradle_compile` (fallback)
- `DEPLOYING`: `compile_and_deploy`, `clean_reinstall_apk`
- `OBSERVING`: `start_app`, `tap`, `screenshot`, `layout_dump`, optional `record`
- `RECOVERING`: `force_gradle_compile`, retry `compile_and_deploy`, then `clean_reinstall_apk`

## Retry Budget

- Max retries per failure category: `3`
- Same error signature after 3 retries: stop and ask user
- Unknown error category: no blind retry, request confirmation

## Stop Conditions (Must Ask User)

- confidence `< 0.8` for fix proposal
- potential destructive change (manifest/signing/gradle-wide)
- unknown failure category after first diagnosis pass
- conflicting requirements or missing acceptance criteria

## Acceptance Criteria

Task is successful only when all are true:

1. Build/deploy path returns success.
2. Runtime action returns success.
3. At least one verification artifact exists on disk.
4. Agent response contains pass/fail verdict, artifact evidence paths, and next-step suggestion on failure.
