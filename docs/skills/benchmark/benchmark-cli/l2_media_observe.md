# L2 Runtime Observation and UI Inspection

Goal: Check whether the agent uses the public CLI for runtime observation, layout export, element location, property inspection, and log waiting. Screenshots and screen recordings are not public CLI commands, so this file has no cases for them.

## McpTestActivity Route

For a case involving `McpTestActivity` or its elements, first run:

```bash
jugg restart && sleep 2 && jugg tap --text "MCP Test Page"
```

Then confirm `McpTestActivity` through `activity-stack` or `layout-dump`. If routing or confirmation fails, record `SKIP: page route failed`; do not execute the target selector.

## OBS-1: Inspect the Activity Stack

Prompt: Identify the current foreground Activity.

Expected:
- Select `activity-stack`.
- Record the foreground Activity name.
- If no device is available or the app is not running, record the actual error; do not switch to adb.

## OBS-2: Export the Current Layout

Prompt: Export the current page layout, including GONE nodes, to help choose a stable selector later.

Expected:
- Select `layout-dump`.
- Use `--include-gone`.
- Record the relative path or a summary of the HTML or structured output.

## OBS-3: Export All Windows

Prompt: A dialog may be open. Export the layout of every window.

Expected:
- Select `layout-dump --all-windows`.
- Do not use obsolete `--root`; the subtree argument is `--root-layout`.

## OBS-4: Locate an Element by Text

Prompt: On McpTestActivity, find the button labeled `Unique MCP Target` and report its position and size.

Expected:
- Run the McpTestActivity route and record gate evidence first.
- Select `view-locate --text "Unique MCP Target"`.
- The result should include bounds or coordinates.

## OBS-5: Locate an Element by Resource ID

Prompt: Find the element with resource ID `btn_mcp_resource_target`.

Expected:
- Run the McpTestActivity route and record gate evidence first.
- Select `view-locate --resource-id btn_mcp_resource_target`.
- Do not use obsolete `--id`.

## OBS-6: Read View Properties

Prompt: Read the text, clickable state, and enabled state of `btn_mcp_resource_target`.

Expected:
- Run the McpTestActivity route and record gate evidence first.
- Select `view-inspect`.
- Use `--resource-id btn_mcp_resource_target` as the selector.
- Include expressions for text, clickable, and enabled at minimum.

## OBS-7: No Matching Element

Prompt: Confirm that the page has no element labeled `NonExistentElementXYZ`.

Expected:
- Select `view-locate --text "NonExistentElementXYZ"`.
- Record “not found” as the expected result; do not switch to a fuzzy selector to force a match.

## LOG-1: Wait for a Log Marker

Prompt: After restarting the app, wait up to three seconds for `[JUGG_BENCH] MAIN_ACTIVITY_READY` in the logs.

Expected:
- Run `restart` first.
- Then run `wait-logs --marker '\[JUGG_BENCH\] MAIN_ACTIVITY_READY' --timeout-ms 3000`.
- Marker, crash, and timeout are all valid structured results; the command must not wait indefinitely.

## LOG-2: Missing wait-logs Marker

Prompt: Verify that the log-wait command correctly rejects a request without a marker.

Expected:
- The agent should know that `--marker` is required.
- If it runs the command without that argument, classify the local argument error as the expected failure.
