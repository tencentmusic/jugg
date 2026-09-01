# Jugg CLI Manual

CLI entry: `python3 {SKILL_DIR}/scripts/jugg.py [global options] <subcommand> [options]`.

The CLI scans IDEA and standalone MCP runtimes and prefers an IDEA Runtime that owns the target project. It selects an owning standalone Runtime only when no matching IDEA Runtime exists. Use `--runtime idea|standalone` to override automatic selection. A command keeps the selected Runtime for its full lifetime, including compile status polling, and does not migrate when ownership changes or another Runtime appears. If no Runtime owns the project, the CLI reuses any running standalone Runtime and automatically registers the project on its first valid request. Only when no standalone exists does it acquire the global `~/.jugg/locks/standalone.launch.lock` and start `~/.jugg/standalone/bin/jugg-standalone` or `JUGG_STANDALONE_LAUNCHER`; `JUGG_STANDALONE_LAUNCH_LOCK` can override the lock path. Standalone startup and first-project registration each have a 60-second hard timeout. After 10 seconds, the CLI prints the latest structured entry from the target project's `build/jugg/log/standlone_cli/compile_latest.log` every 10 seconds so slow database recovery and file-monitor initialization remain observable. Missing runtime logs do not interrupt startup, and displayed entries are truncated to 500 characters. Hook subprocesses set `JUGG_CALLER=hook` and only start or register standalone when `build/jugg/database/compile_context.db/complete_flag` exists. The standalone Runtime supports `version`, `list-projects`, `init`, `compile`, `deploy`, `gradle-build`, `get-compile-status`, `status`, `report-prepare`, and `report-upload`. The standalone-only `stop` command is local to the CLI and launcher; it does not use MCP or start a Runtime and stops all standalone projects together.

Use global `--serial <adbSerial>` or `--serial=<adbSerial>` with device-related commands to override IDEA selection or standalone `ANDROID_SERIAL` for that request. The value is injected into `deploy`, `gradle-build`, `clean-reinstall`, `restart`, `instrument`, `status`, `devices`, `layout-dump`, `view-locate`, `view-inspect`, `tap`, `activity-stack`, and `wait-logs`; it is not sent to non-device tools. Explicit serial matching is exact and online-only, with no fallback to another device.

### CLI Output Format

Controlled by the global `--console` flag (must appear before the subcommand):

| Value | Spinner | Output style | Typical use |
|-------|---------|--------------|-------------|
| `plain` | off | human-readable key: value (default) | agent / script |
| `rich` | on | human-readable key: value | human interactive terminal (set by shell wrappers) |
| `json` | off | raw JSON `{status, message, data, ...}` | agent needing structured data |

Runtime discovery uses a transient spinner in an interactive `rich` terminal. Fast `plain` or captured calls stay silent; if discovery takes longer than one second, they print one checking line and the selected Runtime. Registering a project in an existing standalone Runtime, starting standalone, startup waits, and failures remain visible. `json` mode suppresses all of these progress messages.

**DO NOT USE** `--console=rich` on agent, TUI refresh behavior will pollute the context.

Example:
```
python3 {SKILL_DIR}/scripts/jugg.py --console=json deploy
```

Use `--project-dir <path>` when the command must target a project different from the current working directory:
```
python3 {SKILL_DIR}/scripts/jugg.py --project-dir /path/to/project status
python3 {SKILL_DIR}/scripts/jugg.py --project-dir=/path/to/project --console=json deploy
python3 {SKILL_DIR}/scripts/jugg.py --project-dir /path/to/project --runtime idea status
```

Automatic project discovery uses the nearest `settings.gradle` or `settings.gradle.kts` project and only lets an exact owner claim it. An independent nested Gradle project is therefore not captured by its parent IDEA Runtime; when it is not open in IDEA, the CLI registers it in a running standalone Runtime or starts one if none exists. For an explicit `--project-dir` subdirectory under an initialized IDEA project, the CLI still selects that IDEA Runtime and sends its opened project directory as MCP `projectDir`.

All flags accept both kebab-case (`--resource-id`) and camelCase (`--resourceId`).

Print local help without connecting to MCP:
```
python3 {SKILL_DIR}/scripts/jugg.py --help
python3 {SKILL_DIR}/scripts/jugg.py help deploy
python3 {SKILL_DIR}/scripts/jugg.py deploy --help
```

---

## Version Command

```
python3 {SKILL_DIR}/scripts/jugg.py version
```

Show CLI version plus the selected Runtime's plugin/runtime version, Runtime type, and capabilities.

Output when all projects share the same version:
```
cli version: 1.0.1
plugin version: 1.2.3
runtime type: idea
runtime version: 1.2.3
capabilities: [...]
```

Output when projects have differing versions (highest version shown first):
```
cli version: 1.0.1
plugin version: 1.2.3
  (versions differ across projects)
  /path/to/projectA: 1.2.3
  /path/to/projectB: 1.2.0
```

---

## Report Command

```text
python3 {SKILL_DIR}/scripts/jugg.py report
```

`report` works with IDEA and standalone Runtime. It first generates the final redacted ZIP and prints its local path, size, fixed upload destination, and every archive entry path and size. Jugg logs are listed first, matching IDEA ordering; sensitivity and redaction metadata are not displayed. The `[Y/n]` prompt uploads on Enter, `y`, or `yes`. Declining, EOF, or interruption keeps the ZIP locally without uploading. Before upload, the Runtime reloads the same `reportId` and rejects any manifest, ZIP-entry, or SHA-256 change. `report` currently uses the same interactive flow under `--console=json`.

---

## Build & Deploy Commands

All build commands **block** until completion; no polling needed.
Completion means the MCP compile/deploy job has reached a terminal state. The CLI does not expose
`waitAppReadyAfterSuccess`, so it does not add an extra app-ready wait after `deploy`, `gradle-build`,
`clean-reinstall`, or `restart`.

| Command | Purpose | When to Use |
|---------|---------|-------------|
| `compile` | Compile modified sources, skip deploy | Default after ordinary source edits, including generic "verify/check modification" |
| `deploy` | Compile + deploy to device | Need to launch/run app to inspect runtime/UI state, or perform device-side verification |
| `gradle-build` | Full Gradle compile fallback | After `deploy`/`compile` **retries exhausted and still failed** |
| `clean-reinstall` | Clear app data(compat with apply changes) + launch device | **Only** for clean APP data |
| `instrument` | Run androidTest | Verify android test result |

### `init`

Initialize or reuse the standalone run configuration for the target Gradle project. The command always selects the standalone Runtime and prints the resolved compile command.

```
python3 {SKILL_DIR}/scripts/jugg.py --project-dir /path/to/project init
```

If the project has no saved Gradle model yet, initialization runs the selected assemble task in local Gradle dry-run mode to discover it. Subsequent standalone build commands also initialize on demand. An already selected remote profile is reused without modification.

### `stop`

Stop all standalone Runtime processes and every project they host without deleting saved configuration, compile context, history, or logs.

```
python3 {SKILL_DIR}/scripts/jugg.py stop
python3 {SKILL_DIR}/scripts/jugg.py --project-dir /path/to/project stop
```

This command never resolves an MCP port and therefore does not start a missing Runtime. It stops every standalone daemon launched from the same Jugg root. On platforms that support normal process termination, it first requests a normal exit, waits up to five seconds, and forcibly terminates surviving processes; other platforms terminate them forcibly immediately. No matching process is a successful no-op. `--runtime idea` is rejected. Only run it when the user explicitly asks to stop standalone or the active workflow requires that lifecycle action.

### `compile`/`deploy`/`gradle-build`/`clean-reinstall`

```
python3 {SKILL_DIR}/scripts/jugg.py compile
python3 {SKILL_DIR}/scripts/jugg.py deploy
python3 {SKILL_DIR}/scripts/jugg.py gradle-build
python3 {SKILL_DIR}/scripts/jugg.py clean-reinstall
```

In standalone mode, `gradle-build` performs the full compile and refreshes the incremental baseline. With a remote profile, only the Gradle full build/fallback runs remotely; project-info dry-runs, incremental compilation, and device operations stay on the standalone host. Remote authentication is non-interactive, so configure SSH credentials in the profile or authenticate the external iFT client before running the command. It does not install or launch the app; use `deploy` next when device deployment is required. Standalone preserves an explicit `JAVA_HOME`. For deployment, prefer request-level `--serial`; otherwise standalone uses `ANDROID_SERIAL`, then only proceeds without either setting when exactly one device is online. `clean-reinstall`, `instrument`, device listing, runtime UI inspection, touch, activity-stack, restart, and wait-logs remain IDEA-only capabilities.

### `instrument`

Runs androidTest through Jugg compile/deploy chain, while keeping parameter style close to `am instrument`.
The command is source-file anchored: `--source-path` identifies the `src/androidTest` file used to resolve the test class/method, androidTest module, and test APK.
After one successful `jugg instrument`, all app source changes and androidTest source changes have been compiled and deployed into their corresponding APKs. If you then need broad androidTest regression coverage, raw `adb shell am instrument` is acceptable for normal class/package/suite filtering.

```
python3 {SKILL_DIR}/scripts/jugg.py instrument --source-path library1/src/androidTest/kotlin/com/example/FooTest.kt
python3 {SKILL_DIR}/scripts/jugg.py instrument --source-path library1/src/androidTest/kotlin/com/example/FooTest.kt --class com.example.FooTest
python3 {SKILL_DIR}/scripts/jugg.py instrument --source-path library1/src/androidTest/kotlin/com/example/FooTest.kt --class com.example.FooTest --method testSomething
python3 {SKILL_DIR}/scripts/jugg.py instrument --source-path library1/src/androidTest/kotlin/com/example/FooTest.kt --runner androidx.test.runner.AndroidJUnitRunner --extras 'size=large;clearPackageData=true'
```

| Flag | Description |
|------|-------------|
| `--source-path <path>` | test source file under `src/androidTest`; required by the MCP tool. |
| `--class <fqcn>` | optional test class in the source file. |
| `--method <name>` | optional test method in the resolved class. |
| `--runner <fqn>` | instrumentation runner override. |
| `--extras <k=v;k2=v2>` | batch extras format. |

Unsupported by `jugg instrument`: package, testPackage, regex, `--clazz`, `--instrumentationRunner`, and raw `-e`. Use `--source-path` with optional `--class`, `--method`, `--runner`, and `--extras`; for broad regression, first refresh APKs with `jugg instrument`, then use raw `adb shell am instrument`.

When the project has no AndroidTest full-build baseline, `instrument` returns `ERROR` / `INVALID_PARAMS` with `enabledAndroidTest=false` and the same enable steps as the pre-flight section: open the Jugg App Run Configuration, enable Android Test / `enableAndroidTest`, run a full build / `gradle-build`, then re-check `status`.


---

## Runtime & Observe Commands

| Command | Purpose |
|---------|---------|
| `stop` | Stop all standalone Runtimes and hosted projects without deleting project state |
| `restart` | Restart app |
| `wait-logs` | Block until app log marker, crash, or timeout |
| `activity-stack` | Show current Activity stack |
| `tap` | Tap/long-press/swipe on device |

### `restart`

```
python3 {SKILL_DIR}/scripts/jugg.py restart
```

### `wait-logs`

Block until a log marker appears, a crash is detected, or the timeout expires.
Uses the most-recent `deploy`/`restart` timestamp as the log start point.

```
python3 {SKILL_DIR}/scripts/jugg.py wait-logs --marker '\[JUGG_AR\] DONE'
python3 {SKILL_DIR}/scripts/jugg.py wait-logs --marker '\[JUGG_AR\] DONE' --tags MyAutoRun,AndroidRuntime --timeout-ms 30000
```

| Flag | Description |
|------|-------------|
| `--marker <regex>` | Java Pattern regex matched against log message. **Required.** |
| `--tags <t1,t2,...>` | Tag whitelist, comma-separated. Empty = no filter. |
| `--timeout-ms <ms>` | Hard timeout `[1000, 300000]`, default 30000. |

Output `stopReason`: `marker` → parse `logs`; `crash` → FAIL; `timeout` → INCONCLUSIVE.

### `tap`

Selector priority: Element → Coordinate → Percent.

```
python3 {SKILL_DIR}/scripts/jugg.py tap --text "Login"                          # element (preferred)
python3 {SKILL_DIR}/scripts/jugg.py tap --resource-id btn_submit                # element by ID
python3 {SKILL_DIR}/scripts/jugg.py tap --content-desc "Close button"           # element by content-desc
python3 {SKILL_DIR}/scripts/jugg.py tap --x 540 --y 960                         # coordinate (px)
python3 {SKILL_DIR}/scripts/jugg.py tap --x-percent 50 --y-percent 80           # percent (last resort)
python3 {SKILL_DIR}/scripts/jugg.py tap --text "Item" --action long-press        # long-press
python3 {SKILL_DIR}/scripts/jugg.py tap --x-percent 50 --y-percent 80 --action swipe --end-x-percent 50 --end-y-percent 20   # swipe
```

- `--action {tap|long-press|swipe}` — default: `tap`.
- `swipe` requires `--end-x/--end-y` or `--end-x-percent/--end-y-percent`.
- All flags also accept camelCase (= MCP param name), e.g. `--resourceId`, `--xPercent`.
- Element selectors use the same Dragonfly node model as layout dump. Compose taps currently dispatch a MotionEvent at node center and cannot guarantee Semantics action, disabled-state, or stale-node behavior.

---

## UI Inspection Commands

| Command | Purpose |
|---------|---------|
| `view-locate` | Find live element position, bounds, and source location |
| `view-inspect` | Query View properties via reflection |
| `layout-dump` | Dump full UI hierarchy to HTML |

### `view-locate`

```
python3 {SKILL_DIR}/scripts/jugg.py view-locate --text "Submit"
python3 {SKILL_DIR}/scripts/jugg.py view-locate --resource-id btn_confirm
python3 {SKILL_DIR}/scripts/jugg.py view-locate --content-desc "Back"
python3 {SKILL_DIR}/scripts/jugg.py view-locate --class-name android.widget.Button
python3 {SKILL_DIR}/scripts/jugg.py view-locate --text "Item" --class-name TextView --max-results 5
python3 {SKILL_DIR}/scripts/jugg.py view-locate --resource-id hidden_label --visible-only false
```

All non-empty selectors use AND logic. `className` accepts an exact full or simple class name.
`visibleOnly` defaults to `true`; `maxResults` defaults to `10` and accepts `1..100`.
The result reports total/returned counts and truncation. A unique match also exposes top-level
bounds/position/size; source file and line are included when the runtime can provide them.

At least one of `--text`/`--resource-id`/`--content-desc`/`--class-name` is required.
Output always includes `matchCount`, `returnedCount`, `truncated`, and `matches`; a unique match also includes
`bounds [left,top,right,bottom]`, `position {x,y}`, and `size {width,height}`. All coordinates are dp.
If `matchCount > 1`, narrow the selector before relying on one candidate for verification or interaction.

### `view-inspect`

```
python3 {SKILL_DIR}/scripts/jugg.py view-inspect --text "Submit" "getText()" "getVisibility()"
python3 {SKILL_DIR}/scripts/jugg.py view-inspect --resource-id btn_confirm "getBackground()" "getTextSize()"
python3 {SKILL_DIR}/scripts/jugg.py view-inspect --content-desc "Avatar" "getWidth()" "getHeight()" "getTranslationY()"
python3 {SKILL_DIR}/scripts/jugg.py view-inspect --resource-id bubble_container "layoutParams.leftMargin" "getLayoutParams().getMarginStart()"
```

- Selector: at least one of `--text`/`--resource-id`/`--content-desc`/`--class-name`; class name is an exact full or simple name.
- `<expr>`: read-only getter, Kotlin property, or public field expression. Common: `getText()`, `getVisibility()`, `layoutParams.leftMargin`, `getWidth()`, `getHeight()`, `getTextSize()`, `getCurrentTextColor()`, `getBackground()`, `getTranslationX()`, `getTranslationY()`, `getAlpha()`, `isClickable()`, `isEnabled()`.
- A name without `()` is resolved as a public field first, then as `getXxx()` / `isXxx()` (`layoutParams` → `getLayoutParams()`, `enabled` → `isEnabled()`).
- `view-inspect` may read non-clickable hidden views that stay in the hierarchy; hidden views are not safe tap targets.
- Android nodes inspect their original View; Compose nodes inspect the Dragonfly node object, so Android View-only getters return a per-expression error.
- Output: expression/value/type pairs + density for px→dp conversion, plus best-effort source file and line when available.

### `layout-dump`

```
python3 {SKILL_DIR}/scripts/jugg.py layout-dump
python3 {SKILL_DIR}/scripts/jugg.py layout-dump --root-layout content_frame   # cross-window subtree (View resource name, not R.id.xxx)
python3 {SKILL_DIR}/scripts/jugg.py layout-dump --include-gone                # include GONE views
python3 {SKILL_DIR}/scripts/jugg.py layout-dump --all-windows                 # all windows (dialogs, popups)
```

All app-side UI queries and actions use a fresh Dragonfly snapshot while keeping the existing HTML output. The 5000-node/60-level snapshot boundary also limits selectors, taps, inspections, and verification. Java-only projects return an explicit unsupported error. A Compose virtual ID remains stable only while Dragonfly traversal order and UI structure are unchanged. Compose element taps currently dispatch a MotionEvent at the node center rather than invoking a Semantics action.

Output: HTML file with full UI hierarchy.

---

## Diagnostic Commands

| Command | Purpose |
|---------|---------|
| `status` | Show deploy readiness and pending changed-file summary |
| `devices` | List connected devices |
| `ssh-info` | Remote troubleshooting info (**requires user consent**) |

### `status`

```
python3 {SKILL_DIR}/scripts/jugg.py status [--refresh-changes <true|false>] [--full-info <true|false>]
```

`status` refreshes git-tracked changed files by default. Pass `--refresh-changes false` to skip the refresh.

By default, `status` returns at most 20 pending file paths. Pass `--full-info true` to return full status information, including all pending file paths.

`status` returns `data.executionType` and `data.enabledAndroidTest`. `executionType=remote` means the current Jugg run configuration uses remote Gradle fallback; command hooks block the first raw Gradle attempt in that mode even when the current agent session has no recorded source write. Reuse an existing hook block's `Jugg status` output when it is already in context; otherwise run `--console=json status` before choosing the androidTest / `instrument` route. `enabledAndroidTest=true` means the latest persisted full-build baseline used AndroidTest target.

If a user asks to run androidTest or instrumented unit tests and `enabledAndroidTest=false`, do not run `instrument`. Tell the user to open the Jugg App Run Configuration, enable Android Test / `enableAndroidTest`, run the configuration once with a full build / `gradle-build`, then re-check `status` until `data.enabledAndroidTest=true`.

### `ssh-info`

```
python3 {SKILL_DIR}/scripts/jugg.py ssh-info --reason "deploy fails after 3 retries, gradle-build also fails"
```

`--reason` is required. Only use after all other fallback steps exhausted and with user consent.

### Non-CLI: `adb logcat`

`adb logcat` is run directly via shell, not through `jugg`. Use for long-tail log collection outside Jugg flow.

```bash
adb logcat -d -s <TAG>                       # filter by tag
adb logcat -d | grep -E "\\[JUGG_AR\\]"         # filter auto-run logs
```
