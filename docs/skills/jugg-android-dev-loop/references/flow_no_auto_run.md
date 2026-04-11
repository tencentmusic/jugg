# Flow: No Auto-Run Entry

Use when `hasAutoRunEntry=false`. This flow handles compile and deploy only; no on-device verification.

---

## Compile-Only Mode

Trigger: user explicitly says "compile only" / "no deploy".

### Steps

1. **Modify** — Edit source files, output change summary table.
2. **Compile** — Run `compile`. On error → load `error_patterns.md`, apply Error Reviewer.
3. **Done** — Output report with Steps 3-5 = `⏭ SKIP`.

```
Step 1: Modify → [files changed]
Step 2: compile → status: OK ✅
Verdict: PASS (compile-only)
```

---

## Deploy Mode (No Verification)

Trigger: `hasAutoRunEntry=false` AND user did not say compile-only.

### Steps

1. **Modify** — Edit source files, output change summary table.
2. **Deploy** — Run `deploy`. Blocks until completion.
   - On `status: OK` + `isFinal: true` → Step 3.
   - On error → follow Build Fallback Chain (→ see `cli_manual.md` §Build Fallback Chain).
   - On `MCP_NO_DEVICE` / `No device` → switch to Compile-Only Mode above.
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

## Incremental Compile Limits

When changes involve unsupported annotation processors or Transform/instrumentation:
→ see `policy_incremental_compile_limits.md` for decision rule on switching to `gradle-build`.

---

## Report Template

Output at task completion. Status: `✅ PASS` / `❌ FAIL` / `⏭ SKIP` / `🔄 RETRY(n)`. Compile-only: verification steps = `⏭ SKIP`.

```
# Jugg Dev Loop Report — Timestamp: {{ISO 8601}} | Scenario: {{compile_only|no_auto_run}} | Project: {{projectDir}}
## Pipeline Trace
| Step | Status | Detail |
## Verdict: **{{PASS | FAIL | INCONCLUSIVE}}**
```
