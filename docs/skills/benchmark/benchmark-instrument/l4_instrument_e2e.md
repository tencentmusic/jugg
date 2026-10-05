# L4 End-to-End instrument Combinations

Goal: Check whether the agent correctly combines prerequisite checks, compilation/deployment, instrument execution, and result assessment.

## INST-E2E-1: Complete App-Module Loop

Prompt: Use jugg instrument to verify app-module androidTest. Run all methods in `AppLogicInstrumentedTest` and record their results.

Expected:
- Check prerequisites first: `jugg status` confirms `enabledAndroidTest=true` and `jugg devices` confirms an online device.
- On an unmet prerequisite, record `SKIP` and explain why.
- Run `jugg instrument --source-path app/src/androidTest/java/com/example/myapplication/AppLogicInstrumentedTest.kt`.
- Wait for a terminal result (automatic polling until `isFinal=true`).
- Record `isCompileSuccess`, `isDeploySuccess`, and each method's pass/fail/error result.
- Do not substitute `adb shell am instrument`.

## INST-E2E-2: Complete Library-Module Loop

Prompt: Run `Library1LogicInstrumentedTest` in the `library1` module and record the result.

Expected:
- Check prerequisites first.
- Run `jugg instrument --source-path library1/src/androidTest/java/com/example/library1/Library1LogicInstrumentedTest.kt`.
- Recognize its library-style self-targeting Test APK.
- Record compile, deploy, and test results.

## INST-E2E-3: Single-Method Loop

Prompt: Run only `mainActivityShowsTitle` in `AppUiInstrumentedTest`. It starts MainActivity and checks the page title.

Expected:
- Check prerequisites first.
- Run `jugg instrument --source-path app/src/androidTest/java/com/example/myapplication/AppUiInstrumentedTest.kt --class com.example.myapplication.AppUiInstrumentedTest --method mainActivityShowsTitle`.
- Record the method's pass/fail result.
- Verify only that method ran, not the other UI test methods.

## INST-E2E-4: Refuse When Prerequisites Fail

Prompt: Run an instrument test for me.

Expected:
- Check `jugg status` and `jugg devices` first.
- If `enabledAndroidTest=false`, stop before instrument, explain how to open the Jugg App Run Configuration → enable Android Test → run `gradle-build` to establish the baseline → retry, and record `SKIP: enabledAndroidTest=false`.
- If there is no device, record `SKIP: no device`.
- Do not run instrument before checking prerequisites.

## INST-E2E-5: Instrument First, Then Broad adb Regression

Prompt: Use `jugg instrument` to deploy the app and test source changes; then I want to run a broader regression with adb.

Expected:
- First run `jugg instrument --source-path app/src/androidTest/java/com/example/myapplication/AppLogicInstrumentedTest.kt` to complete compilation and deployment.
- After instrument succeeds, explain that ordinary `adb shell am instrument` can run class/package/suite-level regression.
- Do not start with adb and skip jugg instrument's build/deploy stage.

## INST-E2E-6: Correct Global Argument Placement

Prompt: Run `AppLogicInstrumentedTest` with instrument in JSON mode.

Expected:
- Select `jugg --console=json instrument --source-path app/src/androidTest/java/com/example/myapplication/AppLogicInstrumentedTest.kt`.
- Place `--console=json` before `instrument`.
- Treat `jugg instrument --console=json --source-path ...` as incorrect argument placement.

## INST-E2E-7: Identify Method Logs After Execution

Prompt: Run `AppLogicInstrumentedTest` and confirm the result and corresponding logs for `targetContextUsesAppPackage`.

Expected:
- Complete the full instrument loop.
- Extract `targetContextUsesAppPackage` pass/fail status and its logcat messages, if any, from instrument output.
- Do not attribute another method's or device-level log errors to this method.

## INST-E2E-8: Instrument Two Modules in Sequence

Prompt: Run `AppLogicInstrumentedTest` in app, then `Library1LogicInstrumentedTest` in library1, and record both results.

Expected:
- Recheck prerequisites before each instrument call.
- First: `jugg instrument --source-path app/src/androidTest/java/com/example/myapplication/AppLogicInstrumentedTest.kt`.
- Second: `jugg instrument --source-path library1/src/androidTest/java/com/example/library1/Library1LogicInstrumentedTest.kt`.
- Record the results separately.
- Do not start the second while the first is still running.
