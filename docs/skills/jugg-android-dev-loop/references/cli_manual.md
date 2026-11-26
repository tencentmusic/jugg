# Jugg CLI Manual

CLI entry: `python3 {SKILL_DIR}/scripts/jugg.py [global options] <subcommand> [options]`.
Flags accept kebab-case and camelCase, e.g. `--resource-id` / `--resourceId`.

The CLI scans IDEA and standalone MCP runtimes in parallel and prefers an IDEA Runtime that owns the target project. Parallel probing bounds one scan by the slowest port instead of accumulating timeout delays from every unused Windows port. It selects an owning standalone Runtime only when no matching IDEA Runtime exists. Use `--runtime idea|standalone` to override automatic selection. A command keeps the selected Runtime for its full lifetime, including compile status polling, and does not migrate when ownership changes or another Runtime appears. If no Runtime owns the project, the CLI reuses any running standalone Runtime and automatically registers the project on its first valid request. Only when no standalone exists does it acquire the global `~/.jugg/locks/standalone.launch.lock` and start `~/.jugg/standalone/bin/jugg-standalone` or `JUGG_STANDALONE_LAUNCHER`; `JUGG_STANDALONE_LAUNCH_LOCK` can override the lock path. Standalone startup and first-project registration each have a 60-second hard timeout. Before reporting a startup timeout for a still-running daemon, the CLI performs one final Runtime discovery. If ping succeeds but `version` or `list-projects` discovery fails, the failure output includes the handshake details. After 10 seconds, the CLI prints the latest structured entry from the target project's `build/jugg/log/standlone_cli/compile_latest.log` every 10 seconds so slow database recovery and file-monitor initialization remain observable. Missing runtime logs do not interrupt startup, and displayed entries are truncated to 500 characters. Hook subprocesses set `JUGG_CALLER=hook` and only start or register standalone when `build/jugg/database/compile_context.db/complete_flag` exists. Standalone build commands initialize the current build profile on demand. The standalone Runtime supports `version`, `list-projects`, `settings`, `compile`, `deploy`, `gradle-build`, `get-compile-status`, `status`, `restart`, `report-prepare`, and `report-upload`. The standalone-only `stop` command is local to the CLI and launcher; it does not use MCP or start a Runtime and stops all standalone projects together.

Use global `--serial <adbSerial>` or `--serial=<adbSerial>` with device-related commands to override IDEA selection or standalone `ANDROID_SERIAL` for that request. The value is injected into `deploy`, `gradle-build`, `clean-reinstall`, `restart`, `instrument`, `status`, `devices`, `layout-dump`, `view-locate`, `view-inspect`, `tap`, `activity-stack`, `wait-logs`, and the `report-prepare` phase of `report`; it is not sent to non-device tools. Explicit serial matching is exact and online-only, with no fallback to another device. Reports retain the parameter for compatibility but ignore it and collect logs from all target devices.

### CLI Output Format

Put global options before the subcommand:

| `--console` | Output | Use |
|---|---|---|
| `plain` | key: value, no spinner; default for direct Python calls | agent / script |
| `json` | raw JSON `{status, message, data, ...}`, no spinner | structured data |
| `rich` | key: value with spinner; shell wrapper default | human only; never use for agents, as TUI refresh pollutes context |

Runtime discovery uses a transient spinner in an interactive `rich` terminal. Fast `plain` or captured calls stay silent; if discovery takes longer than one second, they print one checking line and the selected Runtime. Registering a project in an existing standalone Runtime, starting standalone, startup waits, and failures remain visible. `json` mode suppresses all of these progress messages.

**DO NOT USE** `--console=rich` on agent, TUI refresh behavior will pollute the context.

```
python3 {SKILL_DIR}/scripts/jugg.py --project-dir /path/to/project --console=json deploy
```

Use `--project-dir <path>` when the command must target a project different from the current working directory:
```
python3 {SKILL_DIR}/scripts/jugg.py --project-dir /path/to/project status
python3 {SKILL_DIR}/scripts/jugg.py --project-dir=/path/to/project --console=json deploy
python3 {SKILL_DIR}/scripts/jugg.py --project-dir /path/to/project --runtime idea status
```

Automatic project discovery uses the nearest `settings.gradle` or `settings.gradle.kts` project and only lets an exact owner claim it. An independent nested Gradle project is therefore not captured by its parent IDEA Runtime; when it is not open in IDEA, the CLI registers it in a running standalone Runtime or starts one if none exists. For an explicit `--project-dir` subdirectory under an initialized IDEA project, the CLI still selects that IDEA Runtime and sends its opened project directory as MCP `projectDir`.

### Global settings

```bash
jugg settings list
jugg settings get isEnableDirectOverlayDeploy
jugg settings set maxCompileSourceModules 30
jugg settings set isEmbeddedToApk true --confirm true
jugg settings reset isEnableBackupClasspath --confirm true
```

`jugg settings` means list. Keys are exact camelCase property names. The Runtime parses booleans (`true`/`false` only), bounded Int/Long numbers, strings, existing record JSON arrays, and the run-configuration template JSON object. Read-only capabilities appear in list/get and reject edits. Invalid values fail without writing. Lists include key, value, and shared description; get omits description; set/reset return key, previousValue, value, and changed. Template password/environment values are redacted; redacted placeholders cannot be saved.

Settings are persisted globally in `~/.jugg/settings.json` and shared by IDEA/standalone using that root. Runtime selection is automatic; `--runtime` only chooses the executor. No project, device, or Gradle baseline is needed. Without a Runtime, the CLI starts standalone with zero projects and does not create `build/jugg` in the working directory. `--project-dir` is only a routing hint; `--serial` and `--if-compiling` are rejected.

Guarded edits return `CONFIRMATION_REQUIRED` and the full IDE prompt until explicit `--confirm true` is supplied. Confirm only after reviewing that prompt. CLI never opens a remote dialog. Backup classpath changes invalidate each project's old baseline before its next operation; explicit device compatibility changes schedule reinstall. JSON errors still print structured output and exit nonzero.

Option flags accept both kebab-case (`--resource-id`) and camelCase (`--resourceId`).

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
python3 {SKILL_DIR}/scripts/jugg.py --serial emulator-5554 report
```

`report` works with IDEA and standalone Runtime. It first generates the final redacted ZIP and prints its local path, size, fixed upload destination, and every archive entry path and size. Jugg logs are listed first, matching IDEA ordering; sensitivity and redaction metadata are not displayed. The `[Y/n]` prompt uploads on Enter, `y`, or `yes`. Declining, EOF, or interruption keeps the ZIP locally without uploading. Before upload, the Runtime reloads the same `reportId` and rejects any manifest, ZIP-entry, or SHA-256 change. `report` currently uses the same interactive flow under `--console=json`.

When multiple devices are online, the prepared bundle includes error logcat from every target device on a best-effort basis. Global `--serial` is accepted for compatibility but does not filter report contents.

---

## Build & Deploy Commands

Run `compile`, `deploy`, `gradle-build`, and `instrument` as one foreground CLI call and wait for process exit before parsing output once. They may trigger Gradle internally; do not background them or poll status, processes, logs, files, or Git to infer progress. Process exit is the only completion signal.
Completion means the MCP job reached a terminal state. CLI does not expose `waitAppReadyAfterSuccess` or add an app-ready wait after `deploy`, `gradle-build`, `clean-reinstall`, or `restart`.

| Command | Purpose / when to use |
|---|---|
| `compile` | Compile modified sources without deploy; default after ordinary edits or generic “verify/check modification” requests |
| `deploy` | Compile + deploy; launch/run the app for runtime, UI, or device-side verification |
| `gradle-build` | Full Gradle fallback required by `policy_incremental_compile_limits.md`, or after incremental retries are exhausted |
| `clean-reinstall` | Clear app data, compatible with apply changes, and launch on device; **only** for clearing app data |
| `instrument` | Compile, deploy, and run androidTest |

### `stop`

Stop all standalone Runtime processes and every project they host without deleting saved configuration, compile context, history, or logs.

```
python3 {SKILL_DIR}/scripts/jugg.py stop
python3 {SKILL_DIR}/scripts/jugg.py --project-dir /path/to/project stop
```

This command never resolves an MCP port and therefore does not start a missing Runtime. It stops every standalone daemon launched from the same Jugg root. On platforms that support normal process termination, it first requests a normal exit, waits up to five seconds, and forcibly terminates surviving processes; other platforms terminate them forcibly immediately. No matching process is a successful no-op. `--runtime idea` is rejected. Only run it when the user explicitly asks to stop standalone or the active workflow requires that lifecycle action.

### `compile`/`deploy`/`gradle-build`/`clean-reinstall`

Invoke the CLI entry with the command above. Global `--if-compiling wait|interrupt` (default `wait`) controls whether compile-class commands wait for an active build or interrupt it.

- **`deploy --always-restart-app <true|false>`**: Defaults to `true` (restart after deploy). Pass `--always-restart-app false` to allow hot reload (Apply Changes) when class structure changes permit.
- **Terminal result checks**: Inspect both `isCompileSuccess` and `isDeploySuccess` (and terminal `data.status`). A top-level MCP `status=OK` alone does not override a failed/canceled/unknown job, and a 0-compiled-files message means only that no source was compiled — it does not prove no APK or deployment action ran.
- **Version-code downgrade**: When default APK installation fails because the device has a higher `versionCode`, CLI/MCP automatically authorizes uninstall and reinstall, which **clears app data** (there is no CLI flag for `adb install -d`). Manual IDE Run/Debug offers a separate **Allow downgrade** option.

In standalone mode, `gradle-build` performs the full compile, refreshes the incremental baseline, and then installs or deploys to the target device (if no online device is connected, compilation can finish while deployment fails). With a remote profile, only the Gradle full build/fallback runs remotely; project-info dry-runs, incremental compilation, and device operations stay on the standalone host. Remote authentication is non-interactive, so configure SSH credentials in the profile or authenticate the external iFT client before running the command. Standalone preserves an explicit `JAVA_HOME`. For deployment, restart, and device listing, request-level `--serial` takes priority, then standalone `ANDROID_SERIAL`; without either value, deployment and restart target all online devices while `devices` lists all online devices. `clean-reinstall`, `instrument`, runtime UI inspection, touch, activity-stack, and wait-logs remain IDEA-only capabilities.

### `instrument`

`--source-path` anchors resolution of the test class/method, androidTest module, and test APK to a file under `src/androidTest`.
After a successful `instrument`, app and androidTest changes are compiled/deployed into their APKs; raw `adb shell am instrument` may then run broad class/package/suite regression filters.

```
python3 {SKILL_DIR}/scripts/jugg.py instrument --source-path library1/src/androidTest/kotlin/com/example/FooTest.kt --class com.example.FooTest --method testSomething --runner androidx.test.runner.AndroidJUnitRunner --extras 'size=large;clearPackageData=true'
```

| Flag | Description |
|---|---|
| `--source-path <path>` | Required test source file under `src/androidTest` |
| `--class <fqcn>` | Optional test class in the file |
| `--method <name>` | Optional test method in the resolved class |
| `--runner <fqn>` | Instrumentation runner override |
| `--extras <k=v;k2=v2>` | Semicolon-separated extras |

Unsupported: package, testPackage, regex, `--clazz`, `--instrumentationRunner`, and raw `-e`. Use the flags above; refresh APKs with `instrument` before raw adb regression.
Without an AndroidTest full-build baseline, it returns `ERROR` / `INVALID_PARAMS` with `enabledAndroidTest=false`; follow the enable steps under [status](#status).

## Runtime & Observe Commands

| Command | Purpose |
|---------|---------|
| `stop` | Stop all standalone Runtimes and hosted projects without deleting project state |
| `restart` | Restart app |
| `wait-logs` | Wait for app log marker, crash, or timeout |
| `activity-stack` | Show current Activity stack |
| `tap` | Tap, long-press, or swipe on device |

### `restart`

`python3 {SKILL_DIR}/scripts/jugg.py restart` restarts the app without an extra app-ready wait.

Without global `--serial`, restart targets every selected device. With `--serial`, it targets only that online device.

### `wait-logs`

Blocks until marker, crash, or timeout, starting from the most-recent `deploy`/`restart` timestamp.

```
python3 {SKILL_DIR}/scripts/jugg.py wait-logs --marker '\[JUGG_AR\] DONE' --tags MyAutoRun,AndroidRuntime --timeout-ms 30000
```

| Flag | Description |
|---|---|
| `--marker <regex>` | **Required** Java Pattern regex matched against the log message |
| `--tags <t1,t2,...>` | Comma-separated tag whitelist; empty = no filter |
| `--timeout-ms <ms>` | Hard timeout `[1000, 300000]`, default 30000 |

Output `stopReason`: `marker` → parse `logs`; `crash` → FAIL; `timeout` → INCONCLUSIVE.

### `tap`

Prefer a unique element selector, then absolute coordinates or percentages.

```
python3 {SKILL_DIR}/scripts/jugg.py tap --text "Login"
python3 {SKILL_DIR}/scripts/jugg.py tap --x 365 --y 798 --unit dp
python3 {SKILL_DIR}/scripts/jugg.py tap --x-percent 50 --y-percent 80 --action swipe --end-x-percent 50 --end-y-percent 20
```

- Element selectors: `--text`, `--resource-id`, or `--content-desc`; `--class-name` can narrow the match.
- `--action <tap|long-press|swipe>` defaults to `tap`; add `--action long-press` for a long press.
- Absolute coordinates require `--unit dp|px`. For a layout dump, use `units.bounds`. For an absolute swipe, the unit covers both endpoints. Add `--unit px` to existing pixel-coordinate commands.
- Percent taps use `--x-percent` / `--y-percent`; swipe also requires matching `--end-x` / `--end-y` or `--end-x-percent` / `--end-y-percent`.
- Elements share the Dragonfly dump node model. Compose taps dispatch a MotionEvent at node center; Semantics action, disabled-state, and stale-node behavior are not guaranteed.

## UI Inspection Commands

| Command | Purpose |
|---|---|
| `view-locate` | Find live element position, bounds, and source location |
| `view-inspect` | Read properties via reflection |
| `layout-dump` | Export sparse JSON in interaction or UI mode |

### `view-locate`

```
python3 {SKILL_DIR}/scripts/jugg.py view-locate --text "Item" --class-name TextView --max-results 5
python3 {SKILL_DIR}/scripts/jugg.py view-locate --resource-id hidden_label --visible-only false
```

- Require at least one of `--text`, `--resource-id`, `--content-desc`, or `--class-name`. Non-empty selectors use AND logic; class names match exact full or simple names.
- `--visible-only <true|false>` defaults to `true`; `--max-results <1..100>` defaults to `10`.
- Output: `matchCount`, `returnedCount`, `truncated`, `matches`; a unique match also has top-level `bounds [left,top,right,bottom]`, `position {x,y}`, and `size {width,height}`, all in dp. Source file/line are best-effort.
- If `matchCount > 1`, narrow the selector before using a candidate for verification or interaction.

### `view-inspect`

```
python3 {SKILL_DIR}/scripts/jugg.py view-inspect --text "Submit" "getText()" "getVisibility()"
python3 {SKILL_DIR}/scripts/jugg.py view-inspect --resource-id bubble_container "layoutParams.leftMargin" "getLayoutParams().getMarginStart()"
```

- Require at least one of `--text`, `--resource-id`, `--content-desc`, or `--class-name`; class names match exact full or simple names.
- Expressions are read-only getters, Kotlin properties, or public fields: e.g. `getWidth()`, `getTextSize()`, `getCurrentTextColor()`, `getBackground()`, `getTranslationY()`, `getAlpha()`, `isClickable()`, `isEnabled()`.
- Names without `()` resolve public fields first, then `getXxx()` / `isXxx()` (`layoutParams` → `getLayoutParams()`, `enabled` → `isEnabled()`).
- Hidden, non-clickable views still in the hierarchy may be inspected; hidden views are not safe tap targets.
- Android nodes inspect the original View; Compose nodes inspect the Dragonfly node, so View-only getters return per-expression errors.
- Output: expression/value/type pairs, density for px→dp conversion, and best-effort source file/line.

### `layout-dump`

```
python3 {SKILL_DIR}/scripts/jugg.py layout-dump --mode interaction
python3 {SKILL_DIR}/scripts/jugg.py layout-dump --mode ui --root-layout content_frame --include-gone
python3 {SKILL_DIR}/scripts/jugg.py layout-dump --all-windows
```

| Task | Mode / tool |
|---|---|
| Explore unfamiliar text, buttons, or navigation | `interaction` (default) |
| Validate geometry, spacing, original hierarchy, decoration, or hidden state | `ui`; add `--include-gone` for GONE nodes |
| Limit scope / include dialogs and popups | `--root-layout <id>` searches across windows for a subtree (View resource name, not `R.id.xxx`); `--all-windows` includes all windows |
| Locate or tap a known selector | Direct `view-locate` or element-mode `tap` |
| Read properties absent from the dump | `view-inspect` |
| Recreate UI structure and geometry | `ui` plus screenshots, assets, and source; dump alone lacks complete visual/source information |

**Read results:** `data.file` is the sparse JSON artifact; `contentBytes` is its UTF-8 size. Files ≤16 KiB (16,384 bytes, including separators) also return complete `data.layout` in either mode; use it without rereading the file. Larger results return the complete file without inline preview. This is a file-size threshold, not a response/token limit. CLI plain/json preserves Unicode; MCP exposes the object in `structuredContent.data.layout` and summarizes it in text.

The artifact includes mode, bounds/padding units, windows, density, budget `truncated`, and optional warnings. Sparse defaults: visible, alpha=1, clickable=false, enabled=true, zero padding, empty children; missing `textColor` may mean unavailable/default, not necessarily black.

Files are nested JSON, not JSONL, with real nodes on separate lines and inline synthetic `jugg_details` children. For large results, use a known selector or request a subtree; otherwise search text/IDs and read nearby lines before expanding. A few lines do not represent the complete hierarchy.

**Interaction mode:** removes invisible/gone subtrees even with `--include-gone`, omits padding/textColor, and retains className. Visible roots and nodes with real IDs, text, descriptions, clickability, or diagnostics remain; other wrappers are removed and children promoted, subject to offscreen folding below.

In ordinary windows, fully offscreen branches retain their first node's interaction fields but replace descendants with a `jugg_details` placeholder; offscreen leaves remain. Placeholders are not controls or counted nodes/clickables. Partially visible or zero-area nodes, and branches with intersecting descendants, unknown descendant geometry, or descendant diagnostics are not folded. Missing window bounds and rootLayout requests disable viewport folding, retaining semantic pruning. Use `--mode ui --root-layout <id>` for omitted descendants; order/bounds/className guide exploration, then re-query after swiping.

The interaction summary compares shown and captured node counts from the same request. Use `--mode ui` to check whether a control was captured or inspect the original hierarchy. Pruning and folding leave the collection-budget `truncated` flag unchanged.

**UI mode and limits:** preserves the captured hierarchy, hidden nodes, and visual fields within scope; GONE requires `--include-gone`. Field meanings, window/subtree scope, and collection limits are shared across modes. Incomplete/truncated trees cannot prove absence. Queries/actions use fresh Dragonfly snapshots; re-query after interaction. The 5000-node/60-level boundary also limits selectors, taps, inspections, and verification. Private runtime supports Java-only projects.

Virtual IDs use `_vid_<hash>`; selectors also accept legacy `_vir_id_`. Compose IDs remain stable only while traversal order and UI structure stay unchanged; Compose tap limitations are described under [tap](#tap).

## Diagnostic Commands

| Command | Purpose |
|---|---|
| `status` | Deploy readiness and pending changed-file summary |
| `devices` | List connected devices |
| `ssh-info` | Remote troubleshooting info; **requires user consent** |

### `status`

```
python3 {SKILL_DIR}/scripts/jugg.py status [--refresh-changes <true|false>] [--full-info <true|false>]
```

- Refreshes git-tracked changed files by default; `--refresh-changes false` skips it.
- While a compile or project write holds the lock, `status` returns an immediate read-only snapshot without refreshing Git or deploy state; do not treat a busy snapshot as proof that a build finished or pending files disappeared.
- Returns at most 20 pending paths by default; `--full-info true` returns full status and all pending paths.
- `data.executionType=remote`: remote Gradle fallback is configured; hooks block the first raw Gradle attempt even without a source write in this agent session.
- Before choosing androidTest / `instrument`, reuse credible existing status (e.g. a hook block's `Jugg status` output), or run `--console=json status`.
- `data.enabledAndroidTest=true` means the latest persisted full-build baseline used AndroidTest target. If false, **do not run instrument**: open Jugg App Run Configuration, enable Android Test / `enableAndroidTest`, run a full build / `gradle-build`, then re-check status until true.

### `ssh-info`

```
python3 {SKILL_DIR}/scripts/jugg.py ssh-info --reason "deploy fails after 3 retries, gradle-build also fails"
```

`--reason` is required; use only after all other fallback steps are exhausted and with user consent.

### Logs & State Paths

- `{projectDir}/build/jugg/log/compile_latest.log`: Full compile/deploy log for the latest Run. **Overwritten by the next Run** — do not use a later Run's log to diagnose an earlier failure.
- `{projectDir}/build/jugg/log/standlone_cli/standalone_startup.log` and `compile_latest.log`: Standalone daemon startup and runtime logs.
- `~/.jugg/settings.json` (or the directory redirected by `~/.juggdir`): Global persisted settings. Always use `jugg settings`; do not edit the JSON file directly.

### Non-CLI: `adb logcat`

For long-tail logs outside Jugg flow, run directly via shell:

```bash
adb logcat -d -s <TAG>
```
