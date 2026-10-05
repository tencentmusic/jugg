# L3 Integration

Goal: Check that the agent combines the current public UI CLI into an executable verification flow and skips correctly when the page prerequisite is unmet.

## INT-1: Page Health Check

Prompt: Confirm that McpTestActivity is foreground, then read the page title, style title, and button state.

Expected:
- Run `activity-stack` as the gate first.
- After it passes, run `view-inspect --resource-id tv_mcp_title getText().toString()`.
- Run `view-inspect --resource-id tv_mcp_style_title getText().toString() getCurrentTextColor()`.
- Run `view-inspect --resource-id btn_mcp_unique_text isClickable() isEnabled()`.

## INT-2: Select a Selector After layout-dump

Prompt: Export the layout, choose a stable selector from it, then locate `Resource Tap Target`.

Expected:
- Run `layout-dump` first.
- Prefer the resource ID.
- Then run `view-locate --resource-id btn_mcp_resource_target`.

## INT-3: Verify State Text After a Tap

Prompt: Tap `Unique MCP Target` and verify that the state text becomes `Clicked: Unique MCP Target`.

Expected:
- Confirm the McpTestActivity gate.
- Run `tap --text "Unique MCP Target"`.
- Run `view-inspect --resource-id tv_mcp_action_state getText().toString()`.
- Compare the returned text exactly, not merely for nonemptiness.

## INT-4: Verify Two Taps in Sequence

Prompt: Tap `Unique MCP Target` and then `Resource Tap Target`, checking the state text after each.

Expected:
- First tap: `tap --text "Unique MCP Target"`.
- First inspection: `tv_mcp_action_state`.
- Second tap: `tap --resource-id btn_mcp_resource_target`.
- Second inspection: `tv_mcp_action_state`.

## INT-5: Verify Swipe Region

Prompt: Confirm that `Swipe Verification Area` exists, swipe upward there, then try to locate `Swipe End Marker`.

Expected:
- Confirm the region using `view-locate --text "Swipe Verification Area"` or `layout-dump`.
- Run `tap --action swipe --x-percent 50 --y-percent 80 --end-x-percent 50 --end-y-percent 20 --duration 300`.
- Then run `view-locate --text "Swipe End Marker"`.

## INT-6: Observe the Page After Deploy

Prompt: Perform a routine verification deployment, then confirm McpTestActivity is still observable.

Expected:
- Run `deploy` by default.
- After it succeeds, run `activity-stack`.
- If still on McpTestActivity, run `layout-dump` or `view-locate --text "MCP Test Page"`.

## INT-7: Skip When Not on Target Page

Prompt: Check whether `btn_mcp_resource_target` is clickable.

Expected:
- Confirm the page with `activity-stack` or `layout-dump` first.
- If it is not McpTestActivity, return `SKIP: not on McpTestActivity`.
- Do not blindly run `tap`.
- This expected skip can earn full credit.
