# Jugg Benchmark — instrument Command

Purpose: Give different agents the same steps to test correct use of `jugg instrument`.

This directory is an agent-behavior benchmark, not an operation manual. Each case states a task in natural language. It scores whether the agent uses the `jugg-android-dev-loop` skill to select `instrument`, assemble arguments, handle unmet prerequisites, and record evidence.

## Sources of Truth

- Skill entry: `docs/skills/jugg-android-dev-loop/SKILL.md`
- CLI argument catalog: `docs/ai/08_cli_tools_list.md` (`2 `instrument`)
- androidTest guide: `docs/ai/06_android_test.md`
- Android test project: `android_demo_project`
- Existing androidTest sources:
  - `app/src/androidTest/java/com/example/myapplication/AppLogicInstrumentedTest.kt`
  - `app/src/androidTest/java/com/example/myapplication/AppUiInstrumentedTest.kt`
  - `library1/src/androidTest/java/com/example/library1/Library1LogicInstrumentedTest.kt`
  - `library1/src/androidTest/java/com/example/library1/Library1UiInstrumentedTest.kt`

## Execution Preconditions

Run the CLI inside `android_demo_project` or a subdirectory. The repository root is only for reading skill and benchmark documents; it is not the Android `projectDir`.

Do not put machine-specific absolute paths in benchmark documents or reports. Use relative paths, such as `android_demo_project` or `docs/skills/jugg-android-dev-loop`.

Each case is independent by default. Do not rely on a previous case's test results, logs, or temporary files. Each case prepares its prerequisites, verifies the result, and cleans up side effects.

## instrument Arguments

```text
jugg instrument --source-path <src/androidTest/.../FooTest.kt>
                [--class <Fqcn>] [--method <method>] [--runner <runnerFqn>]
                [--extras <k=v;k2=v2>]
```

| Argument | Required | Meaning |
|----------|----------|---------|
| `--source-path` | Yes | androidTest source path; anchor for resolving module and test APK |
| `--class` | No | Test class FQCN; can be omitted for a single-class file |
| `--method` | No | Test method, once the class is uniquely identified |
| `--runner` | No | Instrumentation runner override |
| `--extras` | No | Multiple extras in `k=v;k2=v2` format |

## Prerequisites

`instrument` succeeds only when all three conditions hold:

1. **AndroidTest baseline exists:** `jugg status` returns `enabledAndroidTest=true`.
2. **Device is connected:** `jugg devices` returns a nonempty list.
3. **Valid source anchor:** `--source-path` names an existing file under `src/androidTest/`.

When a prerequisite is missing, truthfully record the blocker and SKIP or mark the expected failure. Do not bypass it.

If `jugg status` returns `enabledAndroidTest=false`, stop before `instrument`. Tell the user to open the Jugg App Run Configuration, enable Android Test / `enableAndroidTest`, run one full build / `gradle-build` to establish the baseline, then confirm `status.data.enabledAndroidTest=true`.

## Agent Rules

- Use only the Jugg CLI supplied by the `jugg-android-dev-loop` skill.
- Do not call MCP directly, debug CLI internals, or edit benchmark cases.
- Use `--console=json` for structured evidence if needed, placing global arguments before the subcommand.
- On failure, record the symptom and output. Do not temporarily fix code or environment just to pass.
- State an unmet condition as a `SKIP` reason, such as `no device`, `enabledAndroidTest=false`, `no test APK`, or `source file not found`.
- Exclude environmental `SKIP` cases from the effective-total denominator.
- `--source-path` must name a real file under `src/androidTest/`; do not invent a path.
- Do not substitute `adb shell am instrument` for `jugg instrument` unless a case explicitly allows it.

## Scoring

| Score | Criterion |
|-------|-----------|
| 5 | Instrument arguments, order, prerequisite checks, and conclusion are all correct. |
| 4 | Instrument choice is correct with a small deviation in nonessential evidence or wording. |
| 3 | Instrument was called, but arguments, prerequisites, or condition checks have a significant flaw. |
| 2 | Instrument was used in the wrong direction (missing source path or substituting adb). |
| 1 | Wrong `projectDir`, skipped a critical prerequisite, or invented a source path. |
| 0 | Did not call `jugg instrument`, called MCP directly, omitted the report, or went entirely off task. |

### Score Caps

- Running instrument before checking `enabledAndroidTest`: at most 3.
- `--source-path` outside `src/androidTest/`: at most 2.
- Running instrument without `--source-path`: at most 2.
- Inventing a nonexistent source path: at most 1.
- Replacing `jugg instrument` with `adb shell am instrument` where not allowed: at most 2.

## Result Template

Append after each case:

```markdown
### CASE-ID: Case title
- Prompt: Natural-language task from the case
- Working dir: `android_demo_project` or a subdirectory
- Precondition: Whether prerequisites are met, or the SKIP reason
- CLI sequence:
  1. `subcommand [args]`
- Evidence: Key stdout/stderr excerpt or relative report path
- Cleanup: Temporary-file removal and recovery result; N/A if no cleanup was needed
- Verdict: PASS / FAIL / SKIP
- Score: N / 5
- Notes:
```

Append at the end of the full evaluation:

```markdown
## Summary

| File | Case | Verdict | Score | Notes |
|------|------|---------|-------|-------|

Total: XX / YY
Skipped: Z
Effective Total: XX / YY (excluding environmental SKIP cases)
Blockers:
```

## Available Test Sources

### app module

| File | FQCN | Methods |
|------|------|---------|
| `app/src/androidTest/java/com/example/myapplication/AppLogicInstrumentedTest.kt` | `com.example.myapplication.AppLogicInstrumentedTest` | `targetContextUsesAppPackage`, `appNameComesFromTargetResources`, `extrasReceivesBenchmarkModeAndTimeout`, `extrasHandlesSpecialCharacters` |
| `app/src/androidTest/java/com/example/myapplication/AppUiInstrumentedTest.kt` | `com.example.myapplication.AppUiInstrumentedTest` | `mainActivityShowsTitle`, `mainActivityShowsNavigationButton`, `mainActivityOpensMcpTestPage` |

### library1 module

| File | FQCN | Methods |
|------|------|---------|
| `library1/src/androidTest/java/com/example/library1/Library1LogicInstrumentedTest.kt` | `com.example.library1.Library1LogicInstrumentedTest` | `targetContextUsesHostAppPackage`, `demoUsersKeepExpectedValues` |
| `library1/src/androidTest/java/com/example/library1/Library1UiInstrumentedTest.kt` | `com.example.library1.Library1UiInstrumentedTest` | `javaDataBindingActivityShowsUserName`, `kotlinDataBindingActivityShowsUserAge` |

## File Groups

| File | Coverage |
|------|----------|
| `l2_instrument_basic.md` | `--source-path`, `--class`, `--method`, single/multiple-class selection |
| `l2_instrument_advanced.md` | `--runner`, `--extras`, prerequisite checks, missing-argument negative test |
| `l3_instrument_no_device.md` | No-device instrument behavior and skip decisions |
| `l4_instrument_e2e.md` | End-to-end prerequisite → compile/deploy → instrument → result parsing |
