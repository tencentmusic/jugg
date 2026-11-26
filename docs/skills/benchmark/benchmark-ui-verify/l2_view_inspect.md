# L2 Unit: view-inspect

Goal: Check that the agent reads View properties with `view-inspect` using the right selector and expression.

## INSPECT-1: Read Text

Prompt: Read the text of `tv_mcp_body_text`.

Expected:
- Select `view-inspect --resource-id tv_mcp_body_text getText().toString()`.
- Include `Body Text Sample` or the actual returned value in the conclusion.

## INSPECT-2: Read Text Color

Prompt: Read the text color of `tv_mcp_style_title`.

Expected:
- Select `view-inspect --resource-id tv_mcp_style_title getCurrentTextColor()`.
- Do not substitute a static `view-locate` field for the getter.

## INSPECT-3: Read Text Size

Prompt: Read the textSize of `tv_mcp_style_title`.

Expected:
- Select `view-inspect --resource-id tv_mcp_style_title getTextSize()`.
- Report the numeric value and any uncertainty about its unit.

## INSPECT-4: Read Background

Prompt: Read the background object of `view_mcp_bg_block`.

Expected:
- Select `view-inspect --resource-id view_mcp_bg_block getBackground()`.
- Record a summary of the return value.

## INSPECT-5: Read Dimensions

Prompt: Read the width and height of `iv_mcp_icon`.

Expected:
- Select `view-inspect --resource-id iv_mcp_icon getWidth() getHeight()`.
- Multiple expressions may be passed in one call.

## INSPECT-6: Read Padding

Prompt: Read the left padding of `tv_mcp_label`.

Expected:
- Select `view-inspect --resource-id tv_mcp_label getPaddingLeft()`.

## INSPECT-7: Batch Style Properties

Prompt: Read the text, text color, and text size of `tv_mcp_style_title` in one call.

Expected:
- Select `view-inspect --resource-id tv_mcp_style_title getText().toString() getCurrentTextColor() getTextSize()`.
- Do not split the call unless the CLI reports an expression-level failure.

## INSPECT-8: Read Clickable, Enabled, and Alpha

Prompt: Check the text, clickable state, enabled state, and alpha of `btn_mcp_resource_target`.

Expected:
- Select `view-inspect --resource-id btn_mcp_resource_target getText().toString() isClickable() isEnabled() getAlpha()`.

## INSPECT-9: Read an INVISIBLE Node

Prompt: Read the visibility of `btn_mcp_visibility_hidden`.

Expected:
- Select `view-inspect --resource-id btn_mcp_visibility_hidden getVisibility()`.
- Inspecting an `INVISIBLE` node is allowed, but the conclusion must state it is not a tappable target.

## INSPECT-10: className Filter

Prompt: Check whether the Button labeled `Resource Tap Target` is enabled.

Expected:
- Select `view-inspect --text "Resource Tap Target" --class-name android.widget.Button isEnabled()` or an equivalent selector.
- Do not turn className into a separate command.

## INSPECT-11: Missing Expression

Prompt: Verify that property inspection without an expression cannot succeed.

Expected:
- Know that `view-inspect` requires a selector and at least one expression.
- If it is run without the expression, classify the argument error as the expected failure.
