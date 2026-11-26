# L4 Argument Placement and End-to-End Combinations

Goal: Check whether the agent still follows the current `jugg-android-dev-loop` skill and CLI catalog for global argument placement and combined tasks. This file does not assess clarification of invalid user arguments.

## PARAM-1: Global JSON Argument Placement

Prompt: Show status in JSON mode.

Expected:
- Select `status`.
- Put `--console=json` before `status` when using JSON.
- Treat `status --console=json` as incorrect argument placement.

## E2E-1: Default Development Loop

Prompt: I finished editing the code. Run a routine verification and confirm that the app returns to its default home page, where the `MCP Test Page` entry is visible.

Expected:
- Run inside `android_demo_project`.
- Start with `deploy` by default.
- After deployment succeeds, use `activity-stack` to inspect the foreground Activity.
- Use `layout-dump` or `view-locate --text "MCP Test Page"` to confirm that the entry appears on the default home page.
- If deployment succeeds but the page is not the default home page, record the actual Activity and page evidence; do not use a vague “expected page” as the criterion.

## E2E-2: UI Verification Loop

Prompt: Confirm that `Unique MCP Target` is visible on McpTestActivity, tap it, and check whether the status text changes. No status-text selector is supplied: first find a stable selector through layout export or element location.

Expected:
- First run `jugg restart && sleep 2 && jugg tap --text "MCP Test Page"` to enter McpTestActivity.
- Confirm the current page is McpTestActivity using `activity-stack` or `layout-dump`.
- Locate the target button with `view-locate --text "Unique MCP Target"`.
- Discover a stable selector for the status text using `layout-dump --include-gone`, `view-locate`, or an equivalent CLI command.
- Do not invent a resource ID; reading with an unverified selector and failing caps the score at 3.
- Read the status text before tapping.
- Tap the target using `tap --text "Unique MCP Target"`.
- Read the status text again afterward.
- Compare the before and after text in the conclusion.

## E2E-3: androidTest Loop

Prompt: Run an existing androidTest in the demo project and record its result.

Expected:
- Find a relative androidTest source path under `android_demo_project`.
- Select `instrument --source-path <relative file>`.
- Use `--class` and `--method` when narrowing to a method.
- Use only currently public `instrument` arguments.

## E2E-4: Log Verification Loop

Prompt: After restarting the app, wait up to five seconds for the `[JUGG_BENCH] MAIN_ACTIVITY_READY` log and decide from the result whether verification completed.

Expected:
- Run `restart` first.
- Then run `wait-logs --marker '\[JUGG_BENCH\] MAIN_ACTIVITY_READY' --timeout-ms 5000`.
- Marker means PASS, crash means FAIL, and timeout means INCONCLUSIVE; record evidence for every outcome.
