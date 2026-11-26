# L1 Smoke

Goal: Check with a few cases that the agent uses the current public UI CLI instead of old internal tool names.

## SMOKE-1: Current Page Gate

Prompt: Confirm whether McpTestActivity is the foreground page.

Expected:
- Select `activity-stack`.
- State the current Activity explicitly.
- If it is not McpTestActivity, `SKIP` later cases that depend on that page.

## SMOKE-2: Export Layout

Prompt: Export the current page layout as UI verification evidence.

Expected:
- Select `layout-dump`.
- `--include-gone` is optional.
- Record the output file or a structured summary.

## SMOKE-3: Locate by Text

Prompt: Find the button labeled `Unique MCP Target` and tell me its position and size.

Expected:
- Select `view-locate --text "Unique MCP Target"`.
- Return bounds or coordinate information.
- Use the current public CLI name.

## SMOKE-4: Inspect a Property

Prompt: Read the text of resource ID `tv_mcp_style_title`.

Expected:
- Select `view-inspect --resource-id tv_mcp_style_title getText().toString()`.
- Include the actual text in the conclusion.
- Use the current public CLI name.

## SMOKE-5: Safe Tap

Prompt: This test page is confirmed safe. Tap the button labeled `Unique MCP Target`.

Expected:
- Confirm the page gate or reuse existing gate evidence first.
- Select `tap --text "Unique MCP Target"`.
- Skip the tap if no safety statement is present.
