# Flow: AndroidTest / Instrument

Use when the user asks to run androidTest / instrumentation, or the task is anchored in `src/androidTest`.

This flow first refreshes APKs through Jugg's compile/deploy/instrument chain. Raw `adb shell am instrument` is only allowed after one successful Jugg `instrument` run when broader regression coverage is needed.

---

## Pre-flight: AndroidTest Baseline

1. Read `enabledAndroidTest` from the current context.
   - If a hook block already printed `Jugg status`, reuse that plain key-value output.
   - If no credible project status exists in context, run:
     ```bash
     python3 {SKILL_DIR}/scripts/jugg.py --console=json status
     ```
     Then read `data.enabledAndroidTest`.
2. If `enabledAndroidTest=false`, stop and report that the latest persisted full-build baseline was not built with AndroidTest target. Ask the user to enable Android Test in the App RunConfig and establish an AndroidTest full-build baseline before using `instrument`.
3. If `enabledAndroidTest=true`, continue to the steps below.

`enabledAndroidTest` is a project status field from `jugg status`. It means the latest persisted full-build baseline used AndroidTest target; it is not merely a UI toggle name.

---

## Steps

1. **Select test anchor** — Identify one source file under `src/androidTest`.
   - Required: `--source-path <path>`.
   - Optional: `--class <fqcn>` when the file has multiple test classes or inference is ambiguous.
   - Optional: `--method <name>` for one test method.
   - Optional: `--runner <fqcn>` only when the default manifest runner is not desired.
   - Optional: `--extras <k=v;k2=v2>` for instrumentation extras.
2. **Run instrument** — Execute one source-anchored command:
   ```bash
   python3 {SKILL_DIR}/scripts/jugg.py instrument --source-path app/src/androidTest/java/com/example/FooTest.kt
   python3 {SKILL_DIR}/scripts/jugg.py instrument --source-path app/src/androidTest/java/com/example/FooTest.kt --method testLogin
   python3 {SKILL_DIR}/scripts/jugg.py instrument --source-path app/src/androidTest/java/com/example/FooTest.kt --class com.example.FooTest --method testLogin
   ```
3. **Read result** — `instrument` blocks until final status.
   - On `status: OK` with `isCompileSuccess: true` and `isDeploySuccess: true` → PASS.
   - On compile/deploy error → follow Build Fallback Chain in `SKILL.md`.
   - On instrumentation failure output → load `error_patterns.md` only if the failure is not explained by the test assertion itself.
4. **Optional broad regression** — If a larger androidTest regression is required after Step 3 succeeds, raw `adb shell am instrument` is allowed. At that point, Jugg has already compiled and deployed both app source changes and androidTest source changes into their corresponding APKs. Use normal `am instrument` filters for broad class/package/suite coverage.

---

## Guardrails

- Do not call raw Gradle as the primary verifier for Android source changes.
- Do not call raw `adb shell am instrument` before the first successful `jugg instrument` in this flow; use `jugg instrument` to refresh the APKs first.
- Do not use package, testPackage, regex, `--clazz`, `--instrumentationRunner`, or `-e`; the CLI intentionally rejects these. Use `--source-path` with optional `--class`, `--method`, `--runner`, and `--extras`.
- `--source-path` is the target anchor. Jugg uses it to resolve test class/method, androidTest module, and test APK.

---

## Report Template

```
# Jugg Dev Loop Report | Scenario: android_test
## Pipeline Trace
| Step | Status | Detail |
| Context | {{PASS|FAIL}} | enabledAndroidTest={{true|false|unknown}} |
| Instrument | {{PASS|FAIL|SKIP}} | {{command/result}} |
## Verdict: **{{PASS | FAIL | INCONCLUSIVE}}**
```
