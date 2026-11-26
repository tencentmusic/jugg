# L2 Agent Hooks

Goal: Check that Jugg agent hooks are configured and triggered by the tested agent's real file edits and commands. The stop hook requires ending a session and is tested in the final L3 case.

## Coverage

| Case | Expected behavior |
|------|-------------------|
| HOOK-1 | An edit hook silently records a same-session write after the agent genuinely edits an isolated Android source file. |
| HOOK-2 | When Jugg changes remain pending after that write, the command hook blocks the first raw Gradle command and allows the second. Codex/Claude must show a warning; Cursor/Gemini may allow silently. |
| HOOK-3 | `jugg gradle-build` is not incorrectly blocked by the raw Gradle hook. |
| HOOK-4 | Editing an isolated non-Android-sourceset file does not cause the command hook to block raw Gradle. |
| HOOK-5 | Adding an isolated file inside an Android sourceset causes the command hook to block raw Gradle. |
| HOOK-6 | Modifying an isolated file inside an Android sourceset causes the command hook to block raw Gradle. |
| HOOK-7 | Changing multiple isolated Android sourceset files in one turn causes the command hook to block raw Gradle. |

## Execution Rules

- Start the agent under test in the current CWD.
- Do not read or call `docs/skills/hooks/*.py` or `~/.jugg/skills/hooks/*.py`.
- Do not edit hook source or start Android Studio.
- Do not edit real business code. Only add, move, or modify isolated trigger files required by a case, and write `report.md` beside the prompt pack.
- Put source trigger files that create Jugg pending changes under `app/src/main/java/com/example/myapplication/`; use isolated names like `Hook*Trigger.kt` and leave existing business files alone.
- Use relative paths in the report by default. Absolute script paths printed by the client in verbatim hook feedback may be retained.
- Trigger hooks through the agent's own real edits and commands.
- For a case expecting a block, mark `FAIL` if the command hook does not fire, its feedback is not visible, or the verbatim feedback cannot be written to the report. Do not mark `SKIP`.
- For a case expecting no block, explicitly record that no blocking/warning feedback arrived. Silent allowance is valid here.
- On the second allowance, Codex/Claude should show the warning verbatim. Cursor/Gemini may allow silently; recording that the second command ran is enough.

## HOOKS-SMOKE: Real Hook Trigger Smoke Test

Prompt: Verify that Jugg agent hooks are configured and can be triggered by your actual agent actions. Do not read or call hook scripts, start Android Studio, or edit real business code. Follow these steps and put the verbatim hook feedback you actually see in the report:

1. Run `jugg gradle-build` once in the current CWD as the hook-state baseline and record whether it succeeded. If it fails, continue while retaining a summary of the failure output.
2. Genuinely edit the isolated source file `app/src/main/java/com/example/myapplication/HookSmokeTrigger.kt`. Use `package com.example.myapplication` and keep the file compilable.
3. Run the raw Gradle command `./gradlew :app:assembleDebug` twice in succession. Even if the first attempt is blocked, run it a second time to test repeated-command allowance.
4. Record the verbatim command-hook feedback, exit code, and blocked/allowed state for both attempts.
5. Run `jugg gradle-build` again and record whether the raw Gradle hook incorrectly blocked it.
6. Write results to `report.md` beside the prompt pack, including verbatim feedback rather than only a summary.

## HOOKS-SOURCE-ADD: Added Sourceset File Blocks Raw Gradle

Prompt: Verify that adding an isolated file inside an Android sourceset causes the command hook to block raw Gradle. Do not read or call hook scripts, start Android Studio, or edit real business code. Follow these steps and put the verbatim feedback you actually see in the report:

1. Run `jugg gradle-build` once in the current CWD as the hook-state baseline; record success or retain a failure summary and continue.
2. Add `app/src/main/java/com/example/myapplication/HookAddTrigger.kt` with `package com.example.myapplication` and compilable content.
3. Run `./gradlew :app:assembleDebug` once.
4. Record the verbatim command-hook feedback, exit code, and blocked/allowed state.
5. Write `report.md` beside the prompt pack, including feedback rather than only a summary.

## HOOKS-SOURCE-MODIFY: Modified Sourceset File Blocks Raw Gradle

Prompt: Verify that modifying an isolated file inside an Android sourceset causes the command hook to block raw Gradle. Do not read or call hook scripts, start Android Studio, or edit real business code. Follow these steps and put the verbatim feedback you actually see in the report:

1. Ensure `app/src/main/java/com/example/myapplication/HookModifyTrigger.kt` exists and compiles. If you must prepare it, run `jugg gradle-build` afterward to reestablish the baseline.
2. Run `jugg gradle-build` once in the current CWD as this case's hook-state baseline; record success or retain a failure summary and continue.
3. Change a constant, return value, or comment in `HookModifyTrigger.kt` while keeping it compilable.
4. Run `./gradlew :app:assembleDebug` once.
5. Record the verbatim command-hook feedback, exit code, and blocked/allowed state.
6. Write `report.md` beside the prompt pack, including feedback rather than only a summary.

## HOOKS-SOURCE-MULTI: Multiple Files in One Turn Block Raw Gradle

Prompt: Verify that changing multiple isolated Android sourceset files in one turn causes the command hook to block raw Gradle. Do not read or call hook scripts, start Android Studio, or edit real business code. Follow these steps and put the verbatim feedback you actually see in the report:

1. Ensure `HookMultiModifyTrigger.kt` and `HookMultiMoveTrigger.kt` exist and compile. If you must prepare them, run `jugg gradle-build` afterward to reestablish the baseline.
2. Run `jugg gradle-build` once in the current CWD as this case's hook-state baseline; record success or retain a failure summary and continue.
3. In one file-change turn, add `HookMultiAddTrigger.kt`, modify `HookMultiModifyTrigger.kt`, and move or rename `HookMultiMoveTrigger.kt` to `HookMultiMoveRenamedTrigger.kt`. Keep all files under `app/src/main/java/com/example/myapplication/` and the remaining source compilable.
4. Run `./gradlew :app:assembleDebug` once.
5. Record verbatim command-hook feedback, exit code, blocked/allowed state, and the relative paths changed in this turn.
6. Write `report.md` beside the prompt pack, including feedback rather than only a summary.

## HOOKS-NONSOURCE: Non-Sourceset File Does Not Block Raw Gradle

Prompt: Verify that editing an isolated file outside Android sourcesets does not cause the command hook to block raw Gradle. Do not read or call hook scripts, start Android Studio, or edit real business code. Follow these steps and put the actual result you see in the report:

1. Run `jugg gradle-build` once in the current CWD as the hook-state baseline; record success or retain a failure summary and continue.
2. Genuinely edit `hook_benchmark_scratch/app/src/main/java/com/example/myapplication/HookNonSourceTrigger.kt`, an isolated file outside the sourceset.
3. Run `./gradlew :app:assembleDebug` twice in succession.
4. Record both exit codes, whether the command hook blocked either command, and verbatim feedback received. If no block or warning arrived, state that explicitly in the report.
5. Write `report.md` beside the prompt pack, not just a summary.

## Pass Criteria

- `HOOK-1` PASS: After a real edit to an isolated Android source under `app/src/main/java/com/example/myapplication/`, no `You modified Android source files.` soft reminder appears, and the following first raw Gradle command is blocked. This proves the edit hook recorded the same-session write.
- `HOOK-2` PASS: First raw Gradle feedback includes `COMMAND GATE` and `Jugg CLI verification skipped: <reason>`; the second attempt is allowed. Codex/Claude report the verbatim `Allowing this repeated command attempt` warning. Cursor/Gemini may allow silently if the report says no second warning arrived but the command ran.
- `HOOK-3` PASS: `jugg gradle-build` is not blocked by the raw Gradle hook.
- `HOOK-4` PASS: After editing a non-sourceset file under `hook_benchmark_scratch/`, neither raw Gradle attempt is blocked; the report explicitly notes absence of blocking/warning feedback.
- `HOOK-5` PASS: After adding `HookAddTrigger.kt`, raw Gradle is blocked and verbatim feedback includes `COMMAND GATE`.
- `HOOK-6` PASS: After modifying `HookModifyTrigger.kt`, raw Gradle is blocked and verbatim feedback includes `COMMAND GATE`.
- `HOOK-7` PASS: After changing multiple isolated Android sourceset files in one turn, raw Gradle is blocked and verbatim feedback includes `COMMAND GATE`.
- Mark the corresponding item `FAIL` if the hook was not triggered by a real agent action or an expected-block case lacks verbatim command-hook feedback in the report.
