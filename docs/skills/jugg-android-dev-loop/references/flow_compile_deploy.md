# Flow: Compile and Deploy

This flow handles compile/deploy as the decisive result. It may include a light-weight on-device check when explicitly requested.

---

## Command Selection Decision Tree

Choose one decisive command:

```text
Unsupported annotation changes or confirmed unsupported instrumentation affecting recompiled files?
  -> gradle-build directly (see policy_incremental_compile_limits.md)
Need runtime/device/UI/log confirmation, app launch, or deploy-side behavior?
  -> deploy
Need only source buildability or generic "verify/check"?
  -> compile
compile/deploy failed after source fixes and 3 retries, or status/policy requires baseline recovery?
  -> gradle-build
```

- `compile`: default for source edits without device-side verification.
- `deploy`: compile + device deploy/start when runtime state matters.
- `gradle-build`: full Gradle build fallback followed by installation/startup in both IDEA and standalone.

Standalone build commands create the current build profile on demand before compiling; no separate initialization command is required.

---

## Pre-flight: Check Incremental Compile Eligibility

Check the current changes against [incremental compile limits](policy_incremental_compile_limits.md) before the first build. Apply its direct-fallback rule before selecting `compile` / `deploy`; no failed incremental attempt or retry is required. Explain the affected annotation/processor or instrumented files to the user.

Only when the user asks for "no Gradle fallback": stop if the policy requires Gradle; if it does not, also check project baseline eligibility:

```bash
python3 {SKILL_DIR}/scripts/jugg.py --console=json status | python3 -c "
import sys, json
data = json.load(sys.stdin).get('data', {})
print('NEEDS_GRADLE_BUILD' if data.get('needFallback', False) else 'OK')
"
```

- Output `OK` → proceed to Steps below.
- Output `NEEDS_GRADLE_BUILD` → **stop and inform the user** (see [Gradle-Build Fallback](#gradle-build-fallback)).

---

## Compile Mode

### Steps

1. **Modify** — Edit source files.
2. **Compile** — Run `compile`, or go directly to Gradle-Build Fallback Mode when pre-flight requires it.
   - On `status: OK` → Step 3.
   - `message: compile executed successfully. No pending file changes.` is also a successful terminal result. It means no new compile output was generated and deploy was skipped.
   - On `status: ERROR`:
     a. Read `message` from JSON output to identify the error.
     b. Load `error_patterns.md`, apply Error Reviewer to diagnose and fix.
     c. Re-run `compile`. Retry budget: max 3 attempts.
     d. If compile still fails after retries with unexpected/unresolvable errors → **fall back to `gradle-build`** (see [Gradle-Build Fallback](#gradle-build-fallback) below).
3. **Done** — Report the decisive command's actual compile/deploy results (`deploy=skip` for `compile`, actual deploy result for `gradle-build`). Verification = `⏭ SKIP (compile-only)`.

---

## Deploy Mode

### Steps

1. **Modify** — Edit source files.
2. **Deploy** — Run `deploy`, or go directly to Gradle-Build Fallback Mode when pre-flight requires it. Blocks until completion.
   - On `status: OK` + `isFinal: true` → Step 3.
   - `message` containing `No pending file changes` is also a successful terminal result. It means all Jugg-detected changes are already deployed.
   - On `status: ERROR`:
     a. Read `message` from JSON output to identify the error.
     b. Load `error_patterns.md`, apply Error Reviewer to diagnose and fix.
     c. Re-run `deploy`. Retry budget: max 3 attempts.
     d. If compile still fails after retries with unexpected/unresolvable errors → **fall back to `gradle-build`** (see [Gradle-Build Fallback](#gradle-build-fallback) below).
3. **Done** — Output report. Verification steps default to `skip`. If optional light-weight check ran, use `light-check` status (see [Light-Weight On-Device Check](#light-weight-on-device-check)).

---

## Gradle-Build Fallback Mode

`gradle-build` performs a full Gradle build + install/start. Use it immediately for known incremental limitations, or after failed incremental fixes exhaust retries.

**Trigger conditions** (any one)**:**
- `compile` retries exhausted (3×) and still failing with unexpected errors that cannot be attributed to source changes.
- The decision rule in `policy_incremental_compile_limits.md` requires a full baseline (unsupported generation/instrumentation, missing artifacts, cross-process resources, or removal of old content). These confirmed limitations do not require failed incremental retries.

**For failure-driven fallback, self-check** (direct policy fallback skips these retry checks):
- Is there a confirmed policy limitation, or is the unresolved failure in the compile phase rather than deploy transport?
- Did you retry with different fixes, not just re-run? 
- Is there a concrete reason incremental can't work? 

If unsure on any → stop and ask user first.

**Steps**:
1. Run `gradle-build`. In standalone mode it deploys to all online devices unless `--serial` selects one device.
2. On error → inspect `${projectDir}/build/jugg/log/compile_latest.log` for root cause.
3. If still unclear → stop and ask user.

---

## Light-Weight On-Device Check

**Trigger condition: only when the user explicitly requests runtime/device/UI verification** (e.g. "verify on device", "check current UI", "验证运行效果"). Generic "verify/check the modification" stays in Compile-Only Mode. Without an explicit runtime/device request, skip this section entirely — the verdict is based on compile success only.

When triggered, agent uses the read-only / low-interaction tools below for a single pass of confirmation. **Never** a PASS gate — the verdict is still based on compile + deploy success.

| Scenario | Tool (details → `cli_manual.md`) |
|----------|-----------------------------------|
| Confirm current page matches the changed one | `activity-stack` |
| Check a control's position / existence | `view-locate` |
| Read a control's property (`text` / `visibility` / `width` / `textSize` …) | `view-inspect` |
| Need a full UI tree when previous tools miss the target | `layout-dump` |
| Need one simple hop to reach the target page | `tap` (Element → Coordinate → Percent) |

#### Guardrails

- **Per-tool retry budget: at most 2 attempts.** If the second attempt still misses (element not found, wrong Activity, etc.), stop — do not chain into another tool as a fallback loop.
- **Single-hop navigation only.** If reaching the target page requires more than 1–2 `tap`s, skip the light check instead of scripting multi-step flows. Multi-step or log-dependent verification belongs in `flow_with_auto_run.md`; suggest the user declare an auto-run entry instead.
- **Not a PASS gate.** A successful light check upgrades the report detail, but a missed / inconclusive one does **not** turn the verdict into FAIL — it degrades to `⏭ SKIP`-equivalent semantics.

### Checkpoint Rules

- Each step must complete before advancing.
- On compile/deploy failure → fix source → restart from Step 1.
- Retry budget: max 3 per category.

---

## Report Template

Output exactly two lines at task completion. Use the Chinese template when replying in Chinese; otherwise use the English template. Keep `command` as the final decisive Jugg command, not every retry.

English template:

```
# Jugg Compile Result
scenario=`{{compile_only|compile_deploy}}`, command=`{{jugg compile|jugg deploy|jugg gradle-build}}`
Result: `{{pass|fail|inconclusive}}`. compile=`{{true|false|unknown}}`, deploy=`{{true|false|skip|unknown}}`, verify=`{{skip|light-check|inconclusive}}`. {{short reason}}
```

Chinese output template:

```
# Jugg 编译结果
场景=`{{仅编译|编译部署}}`，命令=`{{jugg compile|jugg deploy|jugg gradle-build}}`
结果：`{{通过|失败|不确定}}`。编译结果=`{{成功|失败|未知}}`，部署结果=`{{成功|失败|跳过|未知}}`，验证结果=`{{跳过|轻量检查|不确定}}`。{{简短原因}}
```
