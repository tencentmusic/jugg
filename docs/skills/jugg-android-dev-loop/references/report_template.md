# Report Template & Examples

## Report Template (Generator Pattern)

At task completion (Step 5), generate this report. All fields mandatory.

```markdown
# Jugg Dev Loop Report
Timestamp: {{timestamp}}  (ISO 8601)
Project:   {{projectDir}}

## Context
| Variable | Value |
|----------|-------|
| targetPage | {{targetPage}} |
| designSource | {{designSource}} |
| deviceReady | {{deviceReady}} |

## Pipeline Trace
| Step | Status | Detail |
|------|--------|--------|
| 1. Modify | {{status}} | {{detail}} |
| 2. Build  | {{status}} | {{detail}} |
| 3. Verify | {{status}} | {{detail}} |
| 4. Verdict| {{status}} | {{detail}} |
| 5. Stage  | {{status}} | {{detail}} |

## Verification Summary
| Total | ✅ PASS | ❌ FAIL | ⚠️ UNMATCHED |
|-------|---------|---------|--------------|

## Failed Items (if any)
| # | Type | Elements | Expected | Actual | Diff |
|---|------|----------|----------|--------|------|

## Artifacts
| Type | Path |
|------|------|

## Verdict: **{{final_verdict}}**
```

**Fill rules**: status = `✅ PASS` / `❌ FAIL` / `⏭ SKIP` / `🔄 RETRY(n)`. verdict = `PASS` / `FAIL` / `INCONCLUSIVE`. Empty = `N/A`. Compile-only: Steps 3-5 = `⏭ SKIP`.

---

## Quick Example: Change a TextView Label

```
Phase 0: projectDir=/path | targetPage=MainActivity | navigationSeq=[] | designSource=none
Step 1: Edit android:text="Submit" → "Confirm" in activity_main.xml. ✅
Step 2: compile_and_deploy → poll → OK. ✅
Step 3: activity_stack → MainActivity. ui_find(btn_submit) → text=="Confirm". ✅
Step 4: Verdict: PASS.
Step 5: screenshot → final_screenshot.png. ✅
```

## Quick Example: Verify Against Figma Design

```
Phase 0: projectDir=/path | targetPage=SettingsPage | navigationSeq=[tap "Settings"] | designSource=design.json (dpr=1)
Step 1: No changes needed. ⏭ SKIP
Step 2: No source changes. ⏭ SKIP
Step 3: restart_app → activity_stack → SettingsActivity.
        figma_layout_verify(design.json, dpr=1) → 15 total, 12 pass, 3 fail. ❌
Step 4: FAIL → return to Step 1. Fix: Avatar→App spacing 14dp→18dp.
Step 1 (retry): fix layout XML. Step 2: compile_and_deploy → OK.
Step 3 (retry): figma_layout_verify → all pass. ✅
Step 4: PASS. Step 5: screenshot → final. ✅
```
