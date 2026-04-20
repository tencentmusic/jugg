# Flow: With Auto-Run Entry

Use when `hasAutoRunEntry=true`. Full loop: modify → write auto-run code → deploy → auto-execute → verify.

---

## Pipeline (5-Step Loop)

Each step: **entry gate → action → exit checkpoint**. No advance until checkpoint satisfied.

### Step 1: Modify

- **Entry**: Phase 0 complete, auto-run entry available.
- **Action**: Edit source files. Output change summary:

  | File | Change | Purpose |
  |------|--------|---------|

- **Checkpoint ✓**: All sources finish editing, table output.

### Step 2: Write/Update Auto-Run Code

- **Entry**: Step 1 passed.
- **Action**: Write or update code in the auto-run entry to implement the test scenario.
  - Auto-run code guidelines → see `guide_auto_run_entry.md`.
  - Must include: page navigation, wait logic, verification actions, logging.
- **Checkpoint ✓**: (1) Auto-run code finish writing. (2) Contains `[JUGG_AR] START` and `[JUGG_AR] DONE` markers.

### Step 3: Deploy & Auto-Execute

- **Entry**: Step 2 passed.
- **Action**: Run `deploy`. App will launches and auto-run code executes automatically.
  - On error → load `error_patterns.md`, apply Error Reviewer.
  - On `NO_DEVICE` → stop, ask user.
- **Checkpoint ✓**: `status=OK`. Auto-run execution started.
- **Checkpoint ✗**: Fix → return to Step 1.
- **Post-deploy rule**: All prior runtime state is invalidated.

### Step 4: Verify Results

- **Entry**: Step 3 passed, auto-run execution completed.
- **Action**: Collect evidence using one or both methods:

  | Method | Tool | When to Use |
  |--------|------|-------------|
  | UI verification | `view-locate`, `view-inspect`, `layout-dump` | UI changes visible on screen |
  | Log verification | `adb logcat` with regex filter | Logic changes, data validation, auto-run output |

  **UI Verification Sub-flow**:
  1. Run `activity-stack` → confirm target page.
     ```
     PageGate: stack=<Activity> target=<target> match=<yes|no>
     ```
  2. On `match=no`: `restart` with navigation → re-run gate.
  3. Use priority: `view-locate` → `view-inspect` → `layout-dump` (fallback).
  4. Tolerance: ±2dp absolute or ±5% relative.

  **Log Verification Sub-flow**:
  1. Run `adb logcat -d -s <TAG>` or `adb logcat -d | grep -E '<regex>'`.
  2. Parse auto-run log output for expected markers/values.
  3. Match against expected results.

- **Checkpoint ✓**: All checks pass.

### Step 5: Verdict & Report

- **PASS** → output report. **FAIL** → return to Step 1 (full re-verify, no partial).
- **INCONCLUSIVE** → gather more evidence or ask user.
- Retry budget: max 3 per failure category.

---

## Verification Method Decision

| Change Type | Primary Method | Secondary Method |
|-------------|----------------|------------------|
| Pure UI change | UI verification (`view-locate` etc.) | Log (auto-run confirms rendering) |
| Pure logic change | Log verification (`adb logcat`) | UI (if logic affects display) |
| UI + logic change | Both UI and Log | — |

---

## Error Recovery

On build/deploy error:
1. Follow Build Fallback Chain → see `cli_manual.md` §Build Fallback Chain.
2. On runtime crash → use `adb logcat` post-mortem recipe (see `logcat_recipes.md` §8) → locate cause → fix → Step 1.

On auto-run execution error:
1. Check auto-run logs for error/timeout markers.
2. If timeout → check if wait conditions are too strict → adjust auto-run code → Step 2.
3. If exception → fix source or auto-run code → Step 1 or Step 2.

---

## Report Template

Output at task completion. Status: `✅ PASS` / `❌ FAIL` / `⏭ SKIP` / `🔄 RETRY(n)`.

```
# Jugg Dev Loop Report — Timestamp: {{ISO 8601}} | Scenario: with_auto_run | Project: {{projectDir}}
## Pipeline Trace
| Step | Status | Detail |
## Verification Evidence
| Method | Result | Detail |
## Verdict: **{{PASS | FAIL | INCONCLUSIVE}}**
```
