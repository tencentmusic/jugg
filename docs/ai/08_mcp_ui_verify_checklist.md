# MCP UI Verification Execution Checklist

> Last checked: 2026-08-21
> Basis: `08_mcp_layout_verify_design.md`, `08_mcp_tools_list.md`, and current `McpToolActionRegistry.defaultActions()`.
> Use this to assess whether an Agent collected UI evidence through currently public MCP tools. Give a factual answer to each question and explain any noncompliance.

---

## A. Page and tool boundaries

1. Did you first confirm that the current page is the target page using `activity-stack` or equivalent evidence? If not, why?
2. Are all UI tools you used in the public list from `tools/list` / `08_mcp_tools_list.md`?
3. Did you avoid using unregistered `layout-verify`, `figma-layout-verify`, and `screenshot` as default verification tools? If you used one, provide runtime `tools/list` evidence.

## B. Expected-value sources

4. Where did each expected value come from: design, code formula, product copy, or an explicit user-provided number?
5. For size, spacing, and alignment assertions, did you write at least two formulas, such as `right.left - left.right` or `(left + right) / 2`?
6. If a Figma value is px, did you state `dpr` and convert it to dp?

## C. Actual-value evidence

7. Do element position, size, spacing, and alignment come from `view-locate` `bounds` / `size`, rather than visual estimates?
8. Do internal View properties (color, font size, maxLines, ellipsize, enabled, clickable, etc.) come from `view-inspect` getter output?
9. When global structure or a selector fails, did you inspect candidates in `layout-dump` HTML instead of immediately marking the check skipped?
10. When runtime closure is needed, does a `wait-logs` `marker` / `crash` / `timeout` result support the conclusion?

## D. Selectors and multiple matches

11. Did selectors prefer stable `resourceId` and, when needed, combine `text` / `contentDesc` / `className` with AND? Is className an exact full/simple name rather than a substring?
12. With `view-locate.data.matchCount > 1`, did you avoid nonexistent top-level bounds and disambiguate through `matches[]` before asserting or clicking?
13. With `truncated=true`, did you recognize `matchCount > returnedCount` and narrow the selector or adjust `maxResults` within `1..100`? If a selector found no element, did you inspect actual text/id/contentDesc/className and visibility with `layout-dump`?
14. For hidden or GONE nodes, did you distinguish "properties remain readable" from "unsafe click target"?

## E. Numbers and units

15. Are all bounds, size, and spacing conclusions expressed in dp?
16. When `view-inspect` returns px or a raw getter value, did you convert px to dp using `density`?
17. Did approximate judgments explicitly state a tolerance (recommended `<= 2dp` or `<= 5%`) rather than using a nonexistent `tolerance` argument?
18. Do color values state the source format, retaining alpha (`#AARRGGBB`) if converted to hex?

## F. Interaction verification

19. Before clicking, did you confirm whether `tap` uses coordinate, percent, or element mode?
20. Before an element-mode click, did you confirm a unique match? On multiple matches, did you use a stronger selector or coordinates?
21. After clicking, did you collect fresh page evidence rather than reuse pre-click `layout-dump` / `view-locate` output?

## G. Verification report

22. Does the report enumerate `Expected`, `Actual`, `Diff`, `Evidence tool`, and `Verdict` for each assertion?
23. Did you avoid silently omitting failed or unverifiable assertions?
24. Does each FAIL include a concrete fix direction, preferably citing `view-locate` / `view-inspect` `source.file` and `source.line`; if no source location is available, does it identify the layout or View getter to inspect?
25. After a fix, did you fully re-verify the affected page rather than inspect only the prior failing value?
