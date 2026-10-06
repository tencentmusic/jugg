# MCP UI Verification Checklist

> Last checked: 2026-10-07
> Use with `08_mcp_layout_verify_design.md` and the connected Runtime's `tools/list`. Record evidence for each applicable item; mark unavailable checks explicitly.

## 1. Establish scope

- [ ] Confirm the connected Runtime actually lists `activity-stack`, `layout-dump`, `view-locate`, `view-inspect`, `tap`, or `wait-logs` before using them. The standalone Runtime currently exposes none of these. Do not call unregistered `layout-verify`, `figma-layout-verify`, or `screenshot`.
- [ ] Supply the correct `projectDir` and, where needed, one exact device `serial`. Confirm the target Activity with `activity-stack` or equally direct evidence before measuring a page.
- [ ] Identify every expected value's source: design node and DPR, code formula, product copy, or user-provided number. State which page, window, and UI state the expectation applies to.

## 2. Collect actual evidence

- [ ] Use `layout-dump` HTML to orient within windows and discover selectors. Treat the public artifact as HTML; internal JSON is not an Agent contract.
- [ ] Use `view-locate` for node existence and dp bounds. Prefer a stable `resourceId`; combine exact `text`, `contentDesc`, or exact full/simple `className` when needed.
- [ ] Check `matchCount`, `returnedCount`, `truncated`, and `matches[]`. A unique match is needed for top-level bounds or element-mode tap. Narrow ambiguous selectors; a truncated list does not prove an omitted node is absent.
- [ ] Use `view-inspect` for runtime properties, not `view-locate` or visual estimates. Review each `data.values[]` entry for its own `error` even if the tool status is OK. Hidden/GONE properties may be readable without making that node a safe click target.
- [ ] If a selector fails, inspect actual text/ID/description/class and visibility in a fresh `layout-dump` before calling the assertion unverified. Record socket, app-ready, or single-device failures as evidence limits.

## 3. Calculate and close

- [ ] Express bounds, size, spacing, and alignment in dp. Show the relevant formulas (for example gap = right.left − left.right; center = (left + right) / 2). Convert design px by stated DPR and raw runtime px by `view-inspect.data.density`. Preserve alpha in color values.
- [ ] State any approximate tolerance in the report (the conventional rule is ≤2 dp absolute or ≤5% relative); do not pass a nonexistent `tolerance` argument to public tools.
- [ ] Before `tap`, identify coordinate, percent, or element mode. Element mode needs a unique match. Collect fresh `activity-stack` / `layout-dump` / `view-locate` evidence afterward; use `wait-logs` only when marker/crash/timeout is part of the claim.
- [ ] Report each assertion as **Expected / Actual / Diff / Evidence tool / Verdict**. Keep failures and unverifiable claims visible. For FAIL, give a concrete inspection or fix direction; cite best-effort `source.file` / `source.line` only when returned, otherwise name the layout or getter. Recheck the affected page after a fix.
