# L3 Agent Feedback

Goal: Check that the tested agent's hooks are configured and genuinely triggered by its own edits, commands, and session-ending action. L2 covers the full add/modify/move/multiple-file matrix. L3 retains one added-source command feedback path and one stop feedback path.

## Execution Rules

- Start the agent under test in the current CWD.
- Do not read or call `docs/skills/hooks/*.py` or `~/.jugg/skills/hooks/*.py`.
- Do not edit hook source or start Android Studio.
- Do not edit real business code. Only add or modify isolated trigger files required by the case, and write `report.md` beside the prompt pack.
- Put source trigger files that create Jugg pending changes under `app/src/main/java/com/example/myapplication/`; use isolated names like `Hook*Trigger.kt`, not existing business files.
- Use relative paths in the report by default. Absolute script paths printed by the client in verbatim hook feedback may be retained.
- Mark `FAIL`, not `SKIP`, if a command/stop hook does not fire, its feedback is not visible, or verbatim feedback cannot be put in the report. Exception: the second stop warning in **Codex/Claude** is not required to be repeated by the agent in HOOKFB-2; see human confirmation.
- Trigger the stop hook by the agent's session-ending action. Do not use `jugg stop`; it is not the stop-hook trigger.
- Stop-hook feedback does not appear in shell, terminal, or tool output. Trigger it by sending a final reply or ending the session. If the client returns its feedback as a follow-up/new message, continue this case: append the verbatim feedback to `report.md`, then end a second time.
- Repeated-command and repeated-stop allowance vary by client:
  - **Command hook (HOOKFB-1):** Codex/Claude should show the verbatim warning in agent context. Cursor/Gemini may allow silently; recording that the second command ran is enough.
  - **Stop hook (HOOKFB-2):** The second Codex/Claude warning is sent through `systemMessage` and usually **does not enter agent context**. A human evaluator records whether it appeared in the client in the `Human confirmation (Codex / Claude)` section of `report.md`. The agent must not mark FAIL merely because it could not repeat that warning. Cursor/Gemini may allow the second stop silently; record that the session ended.

## HOOKFB-1: Command-Hook Feedback After Adding Source

Prompt: Verify that your real file edit and command actions trigger agent hooks. Follow these steps and put the verbatim feedback you actually see in the report:

1. Run `jugg gradle-build` once in the current CWD as the hook-state baseline. Record success or retain a failure-output summary and continue.
2. Add `app/src/main/java/com/example/myapplication/HookFeedbackAddTrigger.kt` with `package com.example.myapplication` and compilable content.
3. Run `./gradlew :app:assembleDebug` twice in succession. Even if the first attempt is blocked, run the second to verify allowance.
4. Record verbatim hook feedback, exit codes, and blocked/allowed state for both attempts.
5. Run `jugg gradle-build` again and record whether the raw Gradle hook incorrectly blocked it.
6. Write `report.md` beside the prompt pack, including feedback rather than only a summary.

Expected:

- After adding the isolated Android source file, the agent should not receive the `You modified Android source files.` soft reminder. A block on the next first raw Gradle attempt proves that the hook recorded this session's write.
- The first raw Gradle attempt is blocked by the command hook. The agent sees feedback containing `COMMAND GATE` and `Jugg CLI verification skipped: <reason>`; the exit code reflects the block.
- The second raw Gradle attempt is allowed. Codex/Claude should see `Allowing this repeated command attempt`. Cursor/Gemini may allow silently; report that no second warning arrived but the command ran.
- `jugg gradle-build` must not be mistaken for raw Gradle.
- Mark this case `FAIL` if real agent actions did not trigger the hook.

## HOOKFB-2: Real Stop Trigger and Repeated-Allowance Visibility

Prompt: Verify that your real session-ending action triggers the stop hook. Follow these steps and put the verbatim stop-hook feedback you actually see in the report:

1. Run `jugg gradle-build` once in the current CWD as the hook-state baseline. Record success or retain a failure-output summary and continue.
2. Genuinely edit `app/src/main/java/com/example/myapplication/HookStopTrigger.kt`. Use `package com.example.myapplication` and keep the isolated source compilable.
3. Do not run `jugg compile`, `jugg deploy`, or `jugg gradle-build`.
4. Write “ready to trigger first stop” in `report.md`, then try to finish the task by sending a final reply to trigger the real stop hook. Do not run `jugg stop`. Stop-hook feedback does not appear in shell/terminal/tool output; do not mark FAIL merely because those outputs lack it.
5. When configured correctly, the client's first stop is blocked and its feedback lists up to ten Jugg pending filenames. If feedback arrives as a follow-up/new message, the case continues: append only the verbatim stop-hook feedback you saw to `report.md`, then immediately try to end the session again. Do not run commands, edit files, compile, deploy, verify, or repair anything; retain pending changes to observe the second stop.
6. Expected second attempt by client:
   - **Cursor/Gemini:** Silent allowance is valid; no second feedback is required. Report that the agent received no second warning but the session ended.
   - **Codex/Claude:** Try to end again. If the session ends, record “Agent side: second stop allowed” in `report.md`. Do **not** mark FAIL because the agent did not receive or reproduce the second warning. A **human evaluator** completes the following section (the agent may leave the heading at the end of step 6):
     ```markdown
     ## Human confirmation (Codex / Claude)
     - Client under test: Codex / Claude (enter the actual client)
     - Was the repeated-stop warning visible in the client on the second attempt (expected to contain `allowing session stop after a repeated stop attempt`)? yes / no
     - Notes (observation location, screenshot path, optional):
     ```

Expected:

- The first session-ending attempt is blocked by the stop hook. The agent sees feedback containing `STOP GATE`, `Jugg dev loop skipped: <reason>`, and `pendingModifiedFiles: HookStopTrigger.kt`.
- The second ending attempt is allowed (the session can end).
  - **Cursor/Gemini:** A repeated stop may be silently allowed. Report whether the agent received a second warning and whether the session ended.
  - **Codex/Claude:** Agent-path PASS depends on the session ending on the second attempt. The `Human confirmation (Codex / Claude)` section records whether the second warning was visible; the agent need not repeat it. If the evaluator writes “no”, note a known limitation (`systemMessage` did not enter agent context), not an agent FAIL.
- After the first stop is blocked, the agent must not comply with a stop-hook request to verify or clean pending changes. This case must retain pending changes to observe the second stop.
- Do not use `jugg stop`, call `stop.py` directly, or simulate a stop hook through a script.
- If a real attempt to end the session returns no stop-hook feedback or follow-up from the client, mark this case `FAIL`. Do not infer failure solely from missing stop text in shell/terminal/tool output.
