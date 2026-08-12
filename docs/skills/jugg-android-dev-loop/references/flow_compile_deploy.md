# Flow: Compile and Deploy

This flow handles compile/deploy as the decisive result. It may include a light-weight on-device check when explicitly requested.

---

## Command Selection Decision Tree

Choose one decisive command:

```text
Need runtime/device/UI/log confirmation, app launch, or deploy-side behavior?
  -> deploy
Need only source buildability or generic "verify/check"?
  -> compile
compile/deploy failed after source fixes and 3 retries, or status/policy requires baseline recovery?
  -> gradle-build
```

- `compile`: default for source edits without device-side verification.
- `deploy`: compile + device deploy/start when runtime state matters.
- `gradle-build`: full Gradle build + install/start, fallback only.

---

## Pre-flight: Check Incremental Compile Eligibility

> Run this pre-flight only when the user ask "no Gradle fallback". Otherwise, skip.

Before compiling, check whether the project can use incremental compile (i.e., will NOT require `gradle-build`):

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
2. **Compile** — Run `compile`.
   - On `status: OK` → Step 3.
   - `message: compile executed successfully. No pending file changes.` is also a successful terminal result. It means no new compile output was generated and deploy was skipped.
   - On `status: ERROR`:
     a. Read `message` from JSON output to identify the error.
     b. Load `error_patterns.md`, apply Error Reviewer to diagnose and fix.
     c. Re-run `compile`. Retry budget: max 3 attempts.
     d. If compile still fails after retries with unexpected/unresolvable errors → **fall back to `gradle-build`** (see [Gradle-Build Fallback](#gradle-build-fallback) below).
3. **Done** — Output report. Deploy and verification = `⏭ SKIP (compile-only)`.

```
Step 1: Modify → [files changed]
Step 2: compile → status: OK ✅
Verdict: PASS (compile-only)
```

---

## Deploy Mode

### Steps

1. **Modify** — Edit source files.
2. **Deploy** — Run `deploy`. Blocks until completion.
   - On `status: OK` + `isFinal: true` → Step 3.
   - `message` containing `No pending file changes` is also a successful terminal result. It means all Jugg-detected changes are already deployed.
   - On `status: ERROR`:
     a. Read `message` from JSON output to identify the error.
     b. Load `error_patterns.md`, apply Error Reviewer to diagnose and fix.
     c. Re-run `deploy`. Retry budget: max 3 attempts.
     d. If compile still fails after retries with unexpected/unresolvable errors → **fall back to `gradle-build`** (see [Gradle-Build Fallback](#gradle-build-fallback) below).
3. **Done** — Output report. Verification steps default to `skip`. If optional light-weight check ran, use `light-check` status (see [Light-Weight On-Device Check](##Light-Weight On-Device Check)).

---

## Gradle-Build Fallback Mode

> ⚠️ **`gradle-build` is a heavyweight operation** — full Gradle compilation, significantly slower than incremental `compile`. Use only as a last resort.

**Trigger conditions** (any one)**:**
- `compile` retries exhausted (3×) and still failing with unexpected errors that cannot be attributed to source changes.
- Changes involve unsupported annotation processors or Transform/instrumentation (→ see `policy_incremental_compile_limits.md` for decision rule).

**Before using, self-check**:
- Is the failure in the compile phase (not deploy transport)? 
- Did you retry with different fixes, not just re-run? 
- Is there a concrete reason incremental can't work? 

If unsure on any → stop and ask user first.

**Steps**:
1. Run `gradle-build`.
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

中文模板：

```
# Jugg 编译结果
场景=`{{仅编译|编译部署}}`，命令=`{{jugg compile|jugg deploy|jugg gradle-build}}`
结果：`{{通过|失败|不确定}}`。编译结果=`{{成功|失败|未知}}`，部署结果=`{{成功|失败|跳过|未知}}`，验证结果=`{{跳过|轻量检查|不确定}}`。{{简短原因}}
```
