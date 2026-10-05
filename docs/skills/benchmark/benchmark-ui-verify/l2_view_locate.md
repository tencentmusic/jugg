# L2 Unit: view-locate

Goal: Check that the agent locates elements with `view-locate` and handles multiple matches, missing and invisible elements, and content descriptions correctly.

## LOC-1: Exact Text Match

Prompt: Find the button labeled `Unique MCP Target` on McpTestActivity.

Expected:
- Select `view-locate --text "Unique MCP Target"`.
- Return bounds or center coordinates for the unique element.

## LOC-2: Resource-ID Match

Prompt: Find the button with resource ID `btn_mcp_resource_target`.

Expected:
- Select `view-locate --resource-id btn_mcp_resource_target`.
- Do not use obsolete `--id`.

## LOC-3: Content-Description Match

Prompt: Find the element with content description `mcp-resource-target`.

Expected:
- Select `view-locate --content-desc mcp-resource-target`.
- Do not treat contentDescription as text.

## LOC-4: Multiple Text Matches

Prompt: Locate the element labeled `Repeat Tap Target`.

Expected:
- Select `view-locate --text "Repeat Tap Target"`.
- If `matchCount > 1` or a candidate list is returned, report the ambiguity rather than picking one randomly.

## LOC-5: Missing Element

Prompt: Confirm that the page has no element labeled `NonExistentElementXYZ`.

Expected:
- Select `view-locate --text "NonExistentElementXYZ"`.
- “Not found” is the expected result; do not switch to fuzzy matching.

## LOC-6: Prefer Visible Elements

Prompt: Locate the visible button labeled `Visibility Tap Target`.

Expected:
- Select `view-locate --text "Visibility Tap Target"`.
- Do not treat hidden `btn_mcp_visibility_hidden` as a tappable target.

## LOC-7: Deeply Nested Text

Prompt: Find the element labeled `Nested Label`.

Expected:
- Select `view-locate --text "Nested Label"`.
- Record its actual position within the parent container.

## LOC-8: Icon Content Description

Prompt: Find the icon with content description `mcp icon`.

Expected:
- Select `view-locate --content-desc "mcp icon"`.
- If it is offscreen at the current scroll position, state that page state or `layout-dump` evidence is needed.

## LOC-9: Offscreen Element

Prompt: Find the element labeled `Swipe End Marker`.

Expected:
- Try `view-locate --text "Swipe End Marker"` first.
- If it is not visible, report the current viewport miss; do not invent coordinates.

## LOC-10: Missing Selector

Prompt: Verify that an element-location command without a selector cannot succeed.

Expected:
- Know that `view-locate` requires `--text`, `--resource-id`, or `--content-desc`.
- If it is run without one, classify the argument error as the expected failure.
