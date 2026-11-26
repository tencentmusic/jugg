# L3 No-Device Scenario

Goal: Check whether the agent distinguishes CLI commands that still work, should fail, or should be skipped without an Android device, instead of treating every issue as an unavailable CLI.

Execution condition: An MCP endpoint is available, but `devices` returns an empty list.

If a real device is online, mark every case in this file as environmental `SKIP` and exclude them from the effective-total denominator.

## NODEV-1: Empty Device List

Prompt: No device is connected. List devices and state the conclusion.

Expected:
- Select `devices`.
- An empty device list is a valid result.

## NODEV-2: Status Check

Prompt: Inspect Jugg status when no device is connected.

Expected:
- Select `status`.
- Record `hasDevice=false` or equivalent information.

## NODEV-3: Compile Only

Prompt: Verify that the source compiles in a no-device environment.

Expected:
- Select `compile`.
- Do not skip compilation merely because there is no device.

## NODEV-4: Gradle Build

Prompt: Run a full Gradle build in a no-device environment.

Expected:
- Select `gradle-build`.
- If the actual result fails, record the compile error; do not assume beforehand that no device must cause failure.

## NODEV-5: Deploy

Prompt: Attempt a deployment without a device.

Expected:
- Select `deploy`.
- Compilation may run first; the final deployment step may fail.
- Record the failure point; do not call it a parser failure.

## NODEV-6: Clean Reinstall

Prompt: Perform a clean reinstall without a device.

Expected:
- If the prompt does not explicitly permit clearing data, record `SKIP: destructive`.
- If it explicitly permits it, select `clean-reinstall` and record the no-device failure.
- Do not switch to a subcommand outside the public list.

## NODEV-7: Restart

Prompt: Restart the app without a device.

Expected:
- Select `restart`.
- Record the no-device or app-unavailable error.

## NODEV-8: UI Observation Commands

Prompt: Export the layout, locate an element, and inspect its properties without a device.

Expected:
- `layout-dump`, `view-locate`, and `view-inspect` should each fail or be skipped.
- Do not switch to screenshots, screen recordings, or adb.

## NODEV-9: Tap

Prompt: Tap the center of the screen without a device.

Expected:
- Record a failure or `SKIP: no device`.
- Do not use obsolete `--xp` or `--yp` arguments.

## NODEV-10: Instrument

Prompt: Run an existing androidTest source without a device.

Expected:
- Select `instrument --source-path library1/src/androidTest/java/com/example/library1/Library1LogicInstrumentedTest.kt`.
- If the test APK compiles but execution fails, record the no-device or runtime failure.
- Do not guess a package or switch to nonpublic arguments.

## NODEV-11: Wait for Logs

Prompt: Wait for the `[JUGG_BENCH] MAIN_ACTIVITY_READY` log without a device.

Expected:
- Select `wait-logs --marker ... --timeout-ms ...`.
- Record the no-device error or timeout/crash/marker result; do not hang.
