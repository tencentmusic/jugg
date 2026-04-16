# Flow: No Auto-Run Entry

Use when `hasAutoRunEntry=false`, or user explicitly says "compile only" / "no deploy" / "verification code compiles successfully".

This flow handles compile and deploy only; no on-device verification.

---

## Compile-Only Mode

### Pre-flight: Check Incremental Compile Eligibility

> Run this pre-flight when the user ask "no Gradle fallback". Otherwise, skip.

Before compiling, check whether the project can use incremental compile (i.e., will NOT require `gradle-build`):

```bash
python3 {SKILL_DIR}/scripts/jugg.py status --json | python3 -c "
import sys, json
data = json.load(sys.stdin).get('data', {})
print('NEEDS_GRADLE_BUILD' if data.get('needFallback', False) else 'OK')
"
```

- Output `OK` → proceed to Steps below.
- Output `NEEDS_GRADLE_BUILD` → **stop and inform the user** (see [Gradle-Build Fallback](#gradle-build-fallback)).


### Steps

1. **Modify** — Edit source files.
2. **Compile** — Run `compile`.
   - On `status: OK` → Step 3.
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

## Gradle-Build Fallback

> ⚠️ **`gradle-build` is a heavyweight operation** — it triggers a full Gradle compilation, which is significantly slower than incremental `compile`. Use only as a last resort.

**Trigger conditions** (any one):
- `compile` retries exhausted (3×) and still failing with unexpected errors that cannot be attributed to source changes.
- Changes involve unsupported annotation processors or Transform/instrumentation (→ see `policy_incremental_compile_limits.md` for decision rule).

**Steps**:
1. Run `gradle-build`.
2. On error → inspect `${projectDir}/build/jugg/log/compile_latest.log` for root cause.
3. If still unclear → stop and ask user.

---

## Deploy Mode (No Verification)

Trigger: `hasAutoRunEntry=false` AND user did not say compile-only.

### Steps

1. **Modify** — Edit source files.
2. **Deploy** — Run `deploy`. Blocks until completion.
   - On `status: OK` + `isFinal: true` → Step 3.
   - On error → follow Build Fallback Chain (→ see [Gradle-Build Fallback](#gradle-build-fallback)).
   - On `NO_DEVICE` / `No device` → switch to Compile-Only Mode above.
3. **Done** — Output report. Verification steps = `⏭ SKIP (no auto-run entry)`.

```
Step 1: Modify → [files changed]
Step 2: deploy → status: OK, isFinal: true ✅
Verdict: PASS (deployed, no verification)
```

### Checkpoint Rules

- Each step must complete before advancing.
- On compile/deploy failure → fix source → restart from Step 1.
- Retry budget: max 3 per category.

---

## When User Wants Verification Without Auto-Run Entry

If user requests on-device verification but has no auto-run entry configured:

```
⚠️ Auto-run entry not configured. → see guide_auto_run_entry.md §quick-start
Without an auto-run entry, Jugg can only compile and deploy. Proceed with deploy-only? (y/n)
```

---

## Report Template

Output at task completion. Status: `✅ PASS` / `❌ FAIL` / `⏭ SKIP` / `🔄 RETRY(n)`. Compile-only: deploy and verification steps = `⏭ SKIP`.

```
# Jugg Dev Loop Report — Timestamp: {{ISO 8601}} | Scenario: {{compile_only|no_auto_run}} | Project: {{projectDir}}
## Pipeline Trace
| Step | Status | Detail |
## Verdict: **{{PASS | FAIL | INCONCLUSIVE}}**
```
