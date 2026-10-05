# L2 Basic instrument Usage

Goal: Check that the agent correctly assembles `--source-path`, `--class`, and `--method` for different source files and class/method combinations.

## Preconditions

All cases below assume `enabledAndroidTest=true` and a connected device. If either check fails, mark the case `SKIP` and state why.

For a case involving `McpTestActivity` or requiring a launched app, first run:

```bash
jugg restart && sleep 2 && jugg tap --text "MCP Test Page"
```

Confirm `McpTestActivity` through `activity-stack` after routing. On route failure, record `SKIP: page route failed`.

## INST-BASIC-1: Source Path for All Methods of One Class

Prompt: Run every test method in the app module's `AppLogicInstrumentedTest`.

Expected:
- Check `enabledAndroidTest` and device prerequisites first.
- Select `instrument --source-path app/src/androidTest/java/com/example/myapplication/AppLogicInstrumentedTest.kt`.
- `--class` may be omitted for a single-class file; supplying it is not penalized.
- Do not supply `--method`.
- `--source-path` must name a real file under `src/androidTest/`.

## INST-BASIC-2: Source Path Plus Explicit Class

Prompt: Run every method in `com.example.myapplication.AppUiInstrumentedTest`.

Expected:
- Check prerequisites first.
- Select `instrument --source-path app/src/androidTest/java/com/example/myapplication/AppUiInstrumentedTest.kt --class com.example.myapplication.AppUiInstrumentedTest`.
- Explicit `--class` in a single-class file is not penalized.

## INST-BASIC-3: Source Path, Class, and Method

Prompt: Run only `targetContextUsesAppPackage` in `com.example.myapplication.AppLogicInstrumentedTest`.

Expected:
- Check prerequisites first.
- Select `instrument --source-path app/src/androidTest/java/com/example/myapplication/AppLogicInstrumentedTest.kt --class com.example.myapplication.AppLogicInstrumentedTest --method targetContextUsesAppPackage`.
- Use `--method` only when the class is uniquely determined.

## INST-BASIC-4: Gutter-Style Method Selection Without Class

Prompt: Run `appNameComesFromTargetResources` from `app/src/androidTest/java/com/example/myapplication/AppLogicInstrumentedTest.kt`.

Expected:
- Check prerequisites first.
- Select `instrument --source-path app/src/androidTest/java/com/example/myapplication/AppLogicInstrumentedTest.kt --method appNameComesFromTargetResources`.
- `--class` may be omitted for a single-class file, but `--method` is required.
- Only the specified method should run.

## INST-BASIC-5: Library-Module Instrumentation

Prompt: Run every method in `Library1LogicInstrumentedTest`.

Expected:
- Check prerequisites first.
- Select `instrument --source-path library1/src/androidTest/java/com/example/library1/Library1LogicInstrumentedTest.kt`.
- Recognize this as library1 androidTest and use its library-style self-targeting Test APK.
- Do not confuse the app and library1 test APKs.

## INST-BASIC-6: UI Test Requiring a Foreground App

Prompt: Run `AppUiInstrumentedTest.mainActivityOpensMcpTestPage`.

Expected:
- Check prerequisites first.
- Understand that UI tests may require the app in the foreground. This method starts `MainActivity`, but the test process needs an installed and live app.
- Select `instrument --source-path app/src/androidTest/java/com/example/myapplication/AppUiInstrumentedTest.kt --class com.example.myapplication.AppUiInstrumentedTest --method mainActivityOpensMcpTestPage`.
- If `instrument` itself deploys and restarts, no extra `restart` is needed. If it only incrementally deploys without restarting, ensure the app is reachable first.

## INST-BASIC-7: Nonexistent Source Path

Prompt: Run `app/src/androidTest/java/com/example/myapplication/NonExistentTest.kt`.

Expected:
- Notice the file does not exist and do not run instrument.
- Record `SKIP: source file not found` or equivalent.
- Do not create a file or substitute another source path merely to pass.
- Do not bypass with `adb shell am instrument`.
