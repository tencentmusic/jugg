# L3 No-Device Scenario — instrument

Goal: Check that the agent handles `instrument` without an Android device and distinguishes an unmet prerequisite from a defect in the command.

Execution condition: An MCP endpoint is available, `jugg devices` returns an empty list, and an AndroidTest baseline exists (`enabledAndroidTest=true`).

If a real device is online, mark every case here as environmental `SKIP` and exclude them from the effective-total denominator.

## INST-NODEV-1: Attempt instrument Without a Device

Prompt: Run `AppLogicInstrumentedTest`.

Expected:
- Check `status` and `devices` prerequisites first.
- With `enabledAndroidTest=true` but no devices:
  - Instrument may still run; the compile phase can succeed.
  - Deployment or instrumentation execution will fail.
  - Record the actual reason (`no device` or equivalent), not a compile failure.
- Recording `SKIP: no device` without running instrument is also acceptable because the test cannot run without a device.

## INST-NODEV-2: Argument Validation Still Applies

Prompt: Run the nonexistent androidTest file `app/src/androidTest/java/com/example/myapplication/FakeTest.kt`.

Expected:
- Notice first that `source-path` names a nonexistent file.
- Handle the argument-level error before the device condition.
- Record `SKIP: source file not found` or equivalent.

## INST-NODEV-3: Check enabledAndroidTest

Prompt: Check whether androidTest can currently run.

Expected:
- Run `jugg status` first and read `enabledAndroidTest`.
- Then run `jugg devices` and inspect the list.
- Conclude: `enabledAndroidTest=X`, `hasDevice=false` → instrument cannot complete successfully.
- Distinguish an absent baseline from an absent device.

## INST-NODEV-4: adb Cannot Substitute

Prompt: The device list is empty. Run `AppLogicInstrumentedTest`.

Expected:
- Do not substitute `adb shell am instrument` for `jugg instrument`.
- Do not bypass the condition with `adb connect`, emulator startup, or other external actions.
- Use `jugg instrument` or record `SKIP: no device`.

## INST-NODEV-5: Library-Module Instrumentation Without a Device

Prompt: Run `Library1LogicInstrumentedTest` in a no-device environment.

Expected:
- Apply the same logic as INST-NODEV-1: compilation may succeed, but deployment/execution fails without a device.
- Identify the library1 androidTest source correctly.
- Do not substitute the app module's test APK.
