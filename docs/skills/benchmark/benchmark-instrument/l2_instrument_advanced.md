# L2 Advanced instrument Usage

Goal: Check that the agent handles `--runner` and `--extras` correctly and refuses to bypass unmet prerequisites.

## Preconditions

Whenever a case's prerequisite is known to be missing, record `SKIP` with the reason. Do not force execution around the condition.

## INST-ADV-1: Runner Override

Prompt: Run `AppLogicInstrumentedTest` with custom runner `com.example.myapplication.CustomTestRunner`.

Expected:
- Check prerequisites first.
- Select `instrument --source-path app/src/androidTest/java/com/example/myapplication/AppLogicInstrumentedTest.kt --runner com.example.myapplication.CustomTestRunner`.
- The complete runner syntax is `<testPkg>/<runner>`, but the CLI needs only the runner FQCN.
- Do not use obsolete `--instrumentation-runner`.

## INST-ADV-2: Forward Extras

Prompt: Run `AppLogicInstrumentedTest.extrasReceivesBenchmarkModeAndTimeout` with extras `benchmark_mode=true` and `timeout=5000`.

Expected:
- Check prerequisites first.
- Select `instrument --source-path app/src/androidTest/java/com/example/myapplication/AppLogicInstrumentedTest.kt --class com.example.myapplication.AppLogicInstrumentedTest --method extrasReceivesBenchmarkModeAndTimeout --extras benchmark_mode=true;timeout=5000`.
- `--extras` separates key=value pairs with semicolons.
- Do not pass raw am-instrument-style `-e` arguments.
- `extrasReceivesBenchmarkModeAndTimeout` must PASS; it asserts through `InstrumentationRegistry.getArguments()` that the values reached the device.

## INST-ADV-2b: Special Characters in Extra Values

Prompt: Run `AppLogicInstrumentedTest.extrasHandlesSpecialCharacters` with extras `filter=name=foo;bar` and `tags=smoke;regression`. The values contain `=` and `;`, which may conflict with CLI separators.

Expected:
- Check prerequisites first.
- Assemble arguments so that `extrasHandlesSpecialCharacters` passes.
- The method asserts through `InstrumentationRegistry.getArguments()`:
  - `getArguments().getString("filter")` == `"name=foo;bar"`
  - `getArguments().getString("tags")` == `"smoke;regression"`
- Acceptable approaches include escaping (for example `--extras 'filter=name\=foo\;bar;tags=smoke;regression'`), quoting values that contain special characters, or another sound way to deliver the exact values to the device.
- Passing unescaped `--extras filter=name=foo;bar;tags=smoke;regression` causes parse errors and a failing test: score at most 2.
- If the test fails and the agent does not identify special characters in extras as the cause, score at most 2.

## INST-ADV-3: Combine Runner and Extras

Prompt: Run `AppUiInstrumentedTest.mainActivityShowsTitle` with runner `com.example.myapplication.CustomTestRunner` and extra `log_level=debug`.

Expected:
- Check prerequisites first.
- Select `instrument --source-path app/src/androidTest/java/com/example/myapplication/AppUiInstrumentedTest.kt --class com.example.myapplication.AppUiInstrumentedTest --method mainActivityShowsTitle --runner com.example.myapplication.CustomTestRunner --extras log_level=debug`.
- Supply all arguments in one instrument call.

## INST-ADV-4: Handle enabledAndroidTest=false

Prompt: Run every method in `AppLogicInstrumentedTest`.

Expected:
- If `jugg status` returns `enabledAndroidTest=false`:
  - Do not run `instrument`.
  - Explain how to open the Jugg App Run Configuration, enable Android Test / `enableAndroidTest`, and run a full build / `gradle-build` to establish a baseline.
  - Record `SKIP: enabledAndroidTest=false`.
- If `enabledAndroidTest=true`, run instrument normally.
- Do not bypass the prerequisite.

## INST-ADV-5: Missing --source-path Negative Test

Prompt: Run the androidTest class `com.example.myapplication.AppLogicInstrumentedTest`.

Expected:
- Supply `--source-path` by finding the androidTest source file from the class name.
- Calling `jugg instrument --class com.example.myapplication.AppLogicInstrumentedTest` without `--source-path` is a missing-argument error.
- Do not replace instrument with `adb shell am instrument`.
- Do not invent a nonexistent source path.

## INST-ADV-6: Non-androidTest Source Negative Test

Prompt: Run the test for `app/src/main/java/com/example/myapplication/MainActivity.kt`.

Expected:
- Recognize that this path is under `src/main/java`, not `src/androidTest`, and is not a valid androidTest source.
- Record `SKIP: not an androidTest source file`.
- Do not pass the file directly as `instrument --source-path`.

## INST-ADV-7: Ambiguous Method in a Multi-Class File

Prompt: Create `MultiClassInstrumentedTest.kt` under `app/src/androidTest/java/com/example/myapplication/`. Put two test classes, `FirstTest` and `SecondTest`, in it, each with a `@Test` method named `testCommonBehavior`. Then attempt to run `testCommonBehavior` through instrument without `--class`, observe the result, delete the temporary file, and confirm recovery.

Expected:
- Create a valid androidTest source with two classes and the same method name.
- Run `jugg instrument --source-path app/src/androidTest/java/com/example/myapplication/MultiClassInstrumentedTest.kt --method testCommonBehavior` without `--class`.
- Recognize that `--method` does not identify a unique class in this file: request `--class` to disambiguate or record `SKIP: multiple classes contain method testCommonBehavior`.
- Do not pick a class arbitrarily and run instrument.
- Delete `MultiClassInstrumentedTest.kt` and confirm that it no longer exists.

## INST-ADV-8: Improper adb Substitution

Prompt: Run `AppLogicInstrumentedTest` directly on a device that already has the test APK deployed.

Expected:
- Use `jugg instrument --source-path ...`; do not substitute `adb shell am instrument`.
- adb is allowed only when a case explicitly calls for an initial build/deploy with `jugg instrument` followed by broad regression through adb.
- Direct substitution with adb is a violation.

## INST-ADV-9: Library Runner and Extras

Prompt: Run `Library1UiInstrumentedTest` with runner `com.example.library1.test.CustomRunner` and extra `suite=benchmark`.

Expected:
- Check prerequisites first.
- Select `instrument --source-path library1/src/androidTest/java/com/example/library1/Library1UiInstrumentedTest.kt --runner com.example.library1.test.CustomRunner --extras suite=benchmark`.
- Route to library1's self-targeting Test APK.
