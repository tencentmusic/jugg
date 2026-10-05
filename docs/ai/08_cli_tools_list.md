# jugg CLI Arguments and MCP Mapping

> Last checked: 2026-09-10
> Consistency rule: when documentation conflicts with code, code is authoritative.

---

## 1. Scope

This page covers only public `jugg` CLI subcommands, global arguments, CLI-flag-to-MCP-argument mapping, and several easily misunderstood CLI-only behaviors.

It does not reproduce the complete MCP tool schemas; see [`08_mcp_tools_list.md`](08_mcp_tools_list.md) and `tools/list` for full arguments. The benchmark prompt pack, hook acceptance criteria, and tested Agent report format live in `docs/skills/benchmark/`, outside this argument list.

---

## 2. Core source index

| File | Role |
|------|------|
| `docs/skills/jugg-android-dev-loop/scripts/jugg.py` | CLI entry point; parses global arguments, handles local help, and lazily loads subcommands. |
| `docs/skills/jugg-android-dev-loop/scripts/py/help_registry.py` | Side-effect-free help text; `COMMAND_HELP` must cover every public CLI subcommand. |
| `docs/skills/jugg-android-dev-loop/scripts/py/jugglib.py` | MCP port discovery, projectDir resolution, kebab-case normalization, asynchronous polling, and output formatting. |
| `docs/skills/jugg-android-dev-loop/scripts/py/cmd/cmd_*.py` | Subcommand argument parsing; usually passes MCP arguments through with necessary local validation; `stop` calls the standalone launcher directly. |
| `main/src/main/java/com/sickworm/intellij/jugg/ai/mcp/actions/McpToolActionRegistry.kt` | Source of truth for registered MCP tools; CLI subcommands other than local lifecycle commands map to public tools here. |

---

## 3. Global behavior

### 3.0 Python version requirement

The `jugg` CLI supports Python 3.7 and later. The macOS/Linux wrapper falls back from `python3` to `python`; the Windows wrapper actually checks interpreter versions and tries `python3`, `python`, then `py -3`, avoiding reliance on `where.exe` PATH results alone. CLI scripts uniformly enable postponed annotations so annotations such as `list[str]`, `dict[str]`, and `bool | None` are not evaluated during Python 3.7 import.

`docs/skills/python_compat.json` is the compatibility source of truth. Run regression checks with `tools/check_python_compat.py --target jugg_cli`. Strict runtime validation requires `python3.7` on PATH and the `--strict-runtime` option.

### 3.1 projectDir resolution

Default path:

```text
jugg.py
  -> jugglib.resolve_project_dir()
  -> find the nearest settings.gradle(.kts) upward from the current directory
  -> prefer an IDEA Runtime that owns this exact Gradle project
  -> otherwise select a standalone Runtime that owns the project
  -> if none owns it, reuse any standalone Runtime and auto-register on the first valid project request
  -> start a new process only when no standalone Runtime exists
```

During automatic resolution, an independently nested Gradle project is not intercepted by an open parent IDEA project. For example, if both the parent repository and its `android_demo_project` have `settings.gradle(.kts)`, invoking the CLI from the latter uses its Runtime; if it is not open, the CLI reuses or starts a standalone Runtime and registers the nested project on the first project request.

With `--project-dir <path>` or `--project-dir=<path>`, the CLI still uses that path to discover a Runtime and may send the initialized project directory found by longest-prefix matching as MCP `projectDir`. An ordinary subdirectory explicitly specified beneath an IDEA project root is therefore handled by that IDEA Runtime; only an unmatched path follows the standalone startup flow. The camelCase global alias `--projectDir` is normalized too.

On macOS, Runtime ownership matching uses a case-folded path key. Runtime discovery progress, a missing IDE Runtime message, and standalone startup progress display the original casing of the user-provided or current project path.

### 3.1.1 Device serial

`--serial <adbSerial>` / `--serial=<adbSerial>` is a global argument at the same level as `--project-dir`. It injects MCP `serial` into commands that consume a device target: `deploy`, `gradle-build`, `clean-reinstall`, `restart`, `instrument`, `status`, `devices`, `layout-dump`, `view-locate`, `view-inspect`, `tap`, `activity-stack`, `wait-logs`, and the `report-prepare` stage of `report`. `version`, `stop`, `compile`, `ssh-info`, `report-upload`, and internal `get-compile-status` do not receive it.

An explicit serial requires an exact, case-sensitive online-device match. It takes precedence over the currently selected IDEA device and `ANDROID_SERIAL` inherited when the standalone daemon started. It affects only this CLI request; it changes neither IDE selection, Run Configuration, nor subsequent calls. Without serial, existing Host behavior remains.

Without serial, `compile`, `status`, and `devices` do not require a unique device. `deploy`, `clean-reinstall`, and `instrument` retain multi-device deployment and process all target devices; `restart` restarts all target devices. Single-device operations such as `layout-dump`, `view-locate`, `view-inspect`, `tap`, `activity-stack`, and `wait-logs` return structured `MULTIPLE_DEVICE` when multiple target devices exist, instructing the user to specify `--serial`; they must not turn that into HTTP 500. For compatibility, `report` still accepts global serial but ignores its value and collects error logs from all target devices on a best-effort basis.

### 3.2 Ports and cache

The CLI scans `12320..12329` in parallel, then calls `version` and `list-projects` to choose a Runtime for `projectDir`. One scan takes as long as its slowest port, rather than accumulating sequential Windows idle-port timeouts. The port cache only prioritizes probing; it cannot override project ownership. In default mode, if the same project exists in IDEA and standalone Runtimes, IDEA is chosen consistently, without switching back to standalone based on transient `runtime.lock.owner.json` or recent `runtime.owner.json`. Only if IDEA does not match does owner information guide selection among other Runtimes. Global `--runtime idea|standalone` overrides automatic selection. Once a CLI command chooses a port, it uses that port for the process lifetime, without migrating if ownership changes or another Runtime appears; the command fails if its chosen port becomes unavailable. With no project owner and no forced IDEA mode, it reuses any running standalone Runtime, retaining the target as pending projectDir until the first valid project request auto-registers it.

When there is no standalone Runtime, an ordinary CLI command acquires `~/.jugg/locks/standalone.launch.lock` and rechecks Runtime discovery under the lock. It starts the standalone launcher only if none is found, then holds the lock while waiting for port registration so concurrent projects do not create multiple daemons. Tests or special environments can override the lock path through `JUGG_STANDALONE_LAUNCH_LOCK`. The default launcher is `~/.jugg/standalone/bin/jugg-standalone` (`.bat` on Windows), overridable with `JUGG_STANDALONE_LAUNCHER`. Startup and first-project auto-registration each have a hard 60-second wait timeout; launch-lock acquisition waits at most 75 seconds. After initialization exceeds 10 seconds, the CLI reads the last structured log entry from the target project's `build/jugg/log/standlone_cli/compile_latest.log` every 10 seconds and prints a heartbeat to stderr. Missing or unreadable logs only produce a temporary-log-unavailable message, without interrupting startup. A log line is limited to 500 characters. New-process stdout/stderr still goes to the startup project's `build/jugg/log/standlone_cli/standalone_startup.log`. If the process exits before its port is ready, the CLI immediately shows the exit code, log tail, and full log path. Hook calls must set `JUGG_CALLER=hook`; they may start a process or register a new project in an existing standalone Runtime only if the target project's `build/jugg/database/compile_context.db/complete_flag` exists. Otherwise, they skip successfully.

Standalone Step 11 supports `compile`, `deploy`, `gradle-build`, `restart`, `devices`, `report`, internal `get-compile-status`, and `status`; the first build creates the current build profile on demand. `deploy --serial`, `gradle-build --serial`, `restart --serial`, and `devices --serial` can switch devices per request after the daemon starts. Without serial, standalone treats all online devices as deployment or restart targets, and `devices` returns all online devices. `status --serial` returns the specified device status; `report` ignores serial and collects error logcat from all online devices; after establishing a baseline, standalone `gradle-build` proceeds with installation or deployment and fails at deployment when no device is online. `clean-reinstall`, `instrument`, `layout-dump`, `view-locate`, `view-inspect`, `tap`, `activity-stack`, and `wait-logs` remain unregistered standalone capabilities and require IDEA Runtime. When the current configuration enables remote compile, standalone reuses IDEA's remote Gradle client for full builds and fallback; incremental compilation and device operations still run on the standalone host. A local project-info Gradle dry run may still occur before a remote build; "remote" does not mean Gradle never runs locally.

When the project is idle and its lock is immediately available, `status` completes a Git refresh, Runtime-owner recovery, and a consistency snapshot. During a compile/deploy on that Runtime, or while another write transaction holds the project lock, it neither waits for the write lock nor refreshes file state, and instead immediately returns the current true read-only snapshot. Actual deployment state, fallback cause, pending files, baseline, and timestamps are still returned. `isCompiling` reflects only this Runtime's compile/deploy activity, preventing long tasks from blocking CLI waits and heartbeats.

If a process is still alive when the hard 60-second port wait times out, the CLI makes one final complete Runtime discovery pass before failing. This avoids a false timeout when the daemon starts during the last scan. If still undiscovered, it prints the `standalone_startup.log` tail and path, then a Runtime discovery summary if port ping succeeded but the `version` or `list-projects` handshake failed, and finally a per-port probe summary. Only timeouts, HTTP 5xx, or other unexpected exceptions trigger one short retry; a plain connection refusal is not retried in the same scan.

`jugg stop` is a standalone-CLI-only local lifecycle command. It neither scans MCP ports nor calls `resolve_port()`, so stopping cannot accidentally start a Runtime. The CLI synchronously invokes standalone launcher's `--stop-all` control mode; before loading the active Runtime JAR, bootstrap finds all standalone processes matching the Jugg root. On platforms supporting graceful termination, it requests exit, waits five seconds, then forcibly terminates survivors; other platforms terminate immediately. No matching process is an idempotent success. The command stops all projects hosted by those processes, but does not delete run configurations, Compile Context, history, or logs. `--runtime idea` fails explicitly.

A top-level scan failure message aggregates "no port passed MCP probing". It proves only that this scan found no usable endpoint; by itself it cannot establish a particular transport cause, IDE state, or plugin lifecycle. Diagnose using per-port summaries as the underlying classification. If those summaries were not retained, leave the cause at that layer unknown and inspect the generated implementation and other raw evidence.

| File | Default path | Environment variable |
|------|--------------|----------------------|
| Port cache | `~/.cache/jugg/port` (Linux/macOS) / `%LOCALAPPDATA%/jugg/port` (Windows) | `JUGG_PORT_CACHE` |
| Cache root | `~/.cache/jugg/` | `JUGG_CACHE_DIR` |
| Standalone launch lock | `~/.jugg/locks/standalone.launch.lock` | `JUGG_STANDALONE_LAUNCH_LOCK` |

### 3.3 Output modes

```text
jugg --console=plain <subcommand>
jugg --console=rich <subcommand>
jugg --console=json <subcommand>
```

- `plain`: default when running `python3 jugg.py` directly; no spinner.
- `rich`: injected by default by shell/Windows wrappers; shows a spinner in a human-facing terminal.
- `json`: outputs MCP `structuredContent` JSON for scripts and Agents.

Fast reuse of an existing IDEA or standalone Runtime does not retain discovery/port logs. An interactive `rich` terminal shows discovery progress in a temporary spinner. `plain` or non-interactive output prints `Checking Jugg runtime` only after probing exceeds one second, and prints the selection result on completion. Progress for registering a new project in reused standalone, actually starting standalone, waiting for startup, and error diagnosis always remains visible; `json` prints none of these prompts.

Long-running progress for `compile`, `deploy`, `gradle-build`, and `instrument` does not enter result stdout. `plain` prints one initial progress message (such as `Running Gradle build...`) to stderr before triggering and unprefixed heartbeats while running. `rich` updates the same spinner line. `json` keeps stdout pure JSON and prints no heartbeat by default.

A successful `deploy` with no source compilation this run reports only that no source files changed. It does not infer that no APK or other deployment action happened, or claim changes were previously deployed. Details of the latest deployment with file changes exist only in the current IDEA or standalone Runtime session; with no record, the result uses Runtime-neutral wording.

When the user interrupts a compile-type command with Ctrl-C, the CLI prints brief `Interrupted by user.` and exits with 130, without a Python traceback.

`jugg.py` extracts global arguments before dispatching a subcommand. Examples put them before subcommands for readability.

### 3.4 Concurrent compile policy

```text
jugg [--if-compiling wait|interrupt] <compile|deploy|gradle-build|instrument> [options]
```

- `wait` (default): poll `status.isCompiling=false` every five seconds before triggering; while waiting, print a `waiting for previous compile` heartbeat to stderr every 30 seconds.
- `interrupt`: skip the wait and immediately invoke the target MCP tool; the server keeps its "new task interrupts old task" semantics.
- This is a CLI-only global argument and is not sent to MCP.

### 3.5 Asynchronous compile polling

`compile`, `deploy`, `gradle-build`, and `instrument` call through `jugglib.compile_call()`. If the first response is `data.status=running`, the CLI polls with `get-compile-status` and `waitTimeoutMs=5000` to a terminal state, retaining `logPath` from the first response.

When `get-compile-status` returns running with `data.indicator.text`, `plain` immediately prints the first heartbeat to stderr and throttles subsequent running heartbeats of that kind to every 30 seconds. `rich` uses the text to replace its current spinner wording while retaining the spinner; `json` prints no such heartbeat.

### 3.6 Help output

```text
jugg --help
jugg help <subcommand>
jugg <subcommand> --help
```

Help returns directly from `jugg.py`, reading only `help_registry.py`; it does not connect to MCP, resolve `projectDir`, or trigger compilation or deployment.

### 3.7 CLI / skill versions

`jugg version` obtains `cliVersion` from `CLI_VERSION` in `scripts/py/cmd/cmd_version.py`.

After plugin initialization, `JuggCliAutoUpdater` compares the bundled `docs-skills.zip` with the `version:` in `~/.jugg/skills/jugg-android-dev-loop/SKILL.md`. Only a newer bundle overwrites `~/.jugg/bin` and the installed agent skill. The comparison uses the `SKILL.md` version, not `CLI_VERSION`.

After modifying `docs/skills/jugg-android-dev-loop/scripts/`, help, or skill references, also:

1. Increment `CLI_VERSION`.
2. Increment `SKILL.md` frontmatter `version` and update `date`.

Changing a script without the `SKILL.md` version leaves users on the old CLI and skill even after a plugin update.

---
## 4. Argument-mapping constraints

CLI argument design follows "mechanical mapping, no new semantics":

| Rule | Correct approach | Prohibited approach |
|------|------------------|---------------------|
| A flag name mechanically converts to an MCP key | `--always-restart-app` -> `alwaysRestartApp` | Invent an alias that cannot map back to an MCP key. |
| kebab-case and camelCase are equivalent | `--source-path` -> `--sourcePath` -> `sourcePath` | Retain `--clazz` or `--instrumentationRunner` as old aliases. |
| Omitted CLI argument means it is not sent to MCP | Omit `--always-restart-app`. | Hard-code a CLI default that overrides MCP's default. |
| CLI-only arguments stay at the global layer | `--if-compiling` affects only the pre-trigger wait. | Put a CLI-only argument into MCP arguments. |
| Global layer injects per-request device arguments | `--serial emulator-5556 deploy` -> `deploy.serial` | Change IDE's selected device or daemon process environment. |
| Local lifecycle commands do not enter MCP | `stop` calls the standalone launcher directly. | Discover a port or auto-start a Runtime before stopping it. |

`jugglib.normalize_args()` only mechanically converts kebab-case to camelCase; it creates no semantic aliases. Each `cmd_*.py`'s `build_params()` is the actual argument pass-through boundary.

---

## 5. Public subcommands

There are currently 18 public CLI subcommands from `jugg.py::COMMANDS`.

| Subcommand | MCP tool | Purpose |
|------------|----------|---------|
| `version` | `version` | Show CLI and plugin versions; no `projectDir` needed. |
| `stop` | CLI local | Stop every standalone Runtime under the same Jugg root; neither connect to nor start a Runtime. |
| `compile` | `compile` | Incrementally compile and automatically poll to a terminal state. |
| `deploy` | `deploy` | Compile and deploy, automatically polling to a terminal state. |
| `gradle-build` | `gradle-build` | Force a Gradle build and follow the install/start flow. |
| `clean-reinstall` | `clean-reinstall` | Clear data and reinstall APKs. |
| `restart` | `restart` | Restart the app. |
| `instrument` | `instrument` | Run a test anchored to an androidTest source file. |
| `status` | `status` | Inspect deployment state, pending-file summary, androidTest baseline, and compile activity. |
| `layout-dump` | `layout-dump` | Export the UI hierarchy as HTML. |
| `view-locate` | `view-locate` | Find element positions, candidate budget, and source location. |
| `view-inspect` | `view-inspect` | Read View properties through reflection. |
| `tap` | `tap` | Touch by coordinate, percentage, or element. |
| `devices` | `devices` | List devices. |
| `activity-stack` | `activity-stack` | Inspect the Activity stack. |
| `ssh-info` | `ssh-info` | Request SSH troubleshooting information. |
| `report` | `report-prepare` + `report-upload` | Create and show a final diagnostic bundle, then upload after user confirmation. |
| `wait-logs` | `wait-logs` | Wait for an app-log marker, crash, or timeout. |

`list-projects` and `get-compile-status` are MCP tools used internally by the CLI, not exposed as CLI subcommands.

---

## 6. Subcommand arguments

### `version`

```text
jugg version
```

No `projectDir` is required. Default output includes the CLI version and the plugin version of the currently initialized project. `--console=json` returns `{"cliVersion": "...", "plugin": <MCP structuredContent>}`.

### `stop`

```text
jugg stop
jugg --project-dir <path> stop
```

This command stops all standalone CLI Runtimes under the same Jugg root; it does not support IDEA Runtime. `--project-dir` does not narrow its scope. It bypasses MCP and does not require the daemon to finish port initialization. Platforms supporting graceful shutdown wait up to five seconds, then force-terminate surviving targets; other platforms force-terminate directly. No matching process returns success, and each project's persistent state remains unchanged.

### `compile`

```text
jugg compile
```

There are no subcommand arguments. Terminal output includes `status`, `message`, `full log`, `detail`, and other fields.

`compile` creates only compile artifacts; it does not deploy. It refreshes unified deployment state to decide between incremental compilation and Gradle fallback. The device-selection layer safely handles multiple devices, so compile does not fail merely because multiple devices are online. Build-file changes requiring a rebuild, a failed prior Gradle build, or another state requiring a full build still trigger automatic Gradle fallback.

With no pending files, the terminal message is `compile executed successfully. No pending file changes.`. This means the run produced no new compile artifact, while the command succeeded without deploying. Direct completion and asynchronous-poll completion have the same output.

### `deploy`

```text
jugg deploy [--always-restart-app <true|false>]
```

| CLI flag | MCP argument | Description |
|----------|--------------|-------------|
| `--always-restart-app` / `--alwaysRestartApp` | `alwaysRestartApp` | `true` forces an app restart after deployment; `false` permits HOT RELOAD. If omitted, MCP's default applies. |

Terminal output includes `isCompileSuccess`, `isDeploySuccess`, and log paths. Check the deploy result as well as compile success to determine whether deployment succeeded.

An explicit standalone-deployment `--serial` takes priority, followed by `ANDROID_SERIAL`; without either, deploy to all online devices. Per-request `--serial` is independent of the daemon's startup environment, so targets can change on successive requests after it starts.

With no files to deploy, the terminal message explicitly says all changes currently detected by Jugg have been deployed. It shows the latest successful deployment with file changes in the current IDE session, including absolute and relative time and project-relative paths, capped at 20 files. If there is no record after an IDE restart, the message explicitly says details are unavailable. Direct completion and asynchronous-poll completion have the same output.

The CLI does not currently expose MCP `waitAppReadyAfterSuccess`. Omitting it uses MCP's default `false`: wait for the compile/deploy task to terminate, without an extra wait for app readiness.

### `gradle-build`

```text
jugg gradle-build
```

There are no subcommand arguments. Both IDEA and standalone Runtime continue through installation/start after the Gradle build. Without serial, standalone deploys to all online devices and fails if none is online. With a remote profile selected, a Gradle full build or fallback uses SSH/iFT remote compilation, with synchronization, artifact retrieval, and fallback semantics aligned with IDEA. Standalone shows no authentication dialog: missing SSH credentials or unauthenticated iFT return a failed terminal state with an explicit message. On failure, it prints `detail` with a Gradle-build-log summary, such as actual error lines after `Compile project failed, please check the error message.`. Long-log preview is capped at 8 KB, with 4 KB from the beginning and 4 KB from the end.

The CLI does not currently expose MCP `waitAppReadyAfterSuccess`. Omitting it uses MCP's default `false`: wait only for the Gradle build task to terminate, without an extra app-readiness wait.

### `clean-reinstall`

```text
jugg clean-reinstall
```

There are no subcommand arguments.

The CLI does not currently expose MCP `waitAppReadyAfterSuccess`. Omitting it uses MCP's default `false`: wait only for the clean-reinstall task to terminate, without an extra app-readiness wait.

### `restart`

```text
jugg restart
```

There are no subcommand arguments.

Without `--serial`, restart all target devices. With an explicit serial, restart only the specified online device.

The CLI does not currently expose MCP `waitAppReadyAfterSuccess`. Omitting it uses MCP's default `false`: wait only until restart command execution finishes, without an extra app-readiness wait.

The launch target falls back from launch Activity to HOME Activity. If no APK has either, only `am force-stop <package>` runs; no other Activity starts. Since the CLI does not wait for app readiness, that case still returns success. See `03_deploy_core.md` §4.4 for the rule details.

### `instrument`

```text
jugg instrument --source-path <src/androidTest/.../FooTest.kt>
                [--class <Fqcn>] [--method <method>]
                [--runner <runnerFqn>] [--extras <k=v;k2=v2>]
```

| CLI flag | MCP argument | Description |
|----------|--------------|-------------|
| `--source-path` / `--sourcePath` | `sourcePath` | Required androidTest source-file path used to resolve module and Test APK. |
| `--class` | `class` | Test class in the file; optional for a single-class file. |
| `--method` | `method` | Test method; requires a uniquely identified class. |
| `--runner` | `runner` | Instrumentation runner override. |
| `--extras` | `extras` | Semicolon-delimited `k=v` entries converted to an MCP object. |

Hard boundaries:

- `--package`, `--testPackage`, `--testsRegex`, and `--regex` are unsupported.
- Old aliases `--clazz`, `--instrumentationRunner`, `-e`, and `--e` are unsupported.
- Without an AndroidTest full-build baseline in the current project, this returns `INVALID_PARAMS`, instructing the user to enable Android Test, run one full build / `gradle-build`, then check `status.data.enabledAndroidTest=true`.

### `status`

```text
jugg status [--refresh-changes <true|false>] [--full-info <true|false>]
```

| CLI flag | MCP argument | Description |
|----------|--------------|-------------|
| `--refresh-changes` / `--refreshChanges` | `refreshChanges` | Refresh Git-tracked changed files before reading state; default true, pass `false` to skip. |
| `--full-info` / `--fullInfo` | `fullInfo` | Return complete state; by default only the first 20 file paths are returned, pass `true` for all. |

Key fields:

- `executionType`: Gradle-fallback execution environment of the current Jugg run configuration, `local` / `remote`. In `remote`, the AI command hook first blocks a raw Gradle command once without requiring a file-write record earlier in this Agent session.
- `enabledAndroidTest`: whether the latest persisted full-build baseline used AndroidTest target, not merely the UI toggle.
- `isCompiling`: whether a compile/deploy task is running now; compile-type CLI commands use it to wait before triggering.

### `layout-dump`

```text
jugg layout-dump [--root-layout <nodeId>] [--include-gone] [--all-windows]
```

| CLI flag | MCP argument | Description |
|----------|--------------|-------------|
| `--root-layout` / `--rootLayout` | `rootLayout` | Find the node across windows and export only its subtree. |
| `--include-gone` / `--includeGone` | `includeGone=true` | Include GONE nodes. |
| `--all-windows` / `--allWindows` | `allWindows=true` | Export all windows. |

Public output is an HTML artifact; internal JSON is consumed only by existing layout verification.
All app-side UI queries and actions use Dragonfly live snapshots. Conventional Android View and Compose nodes retain their existing HTML/JSON field formats. Dragonfly bundles private Kotlin/coroutine runtimes and also works in a pure Java project. It handles incompatible Compose tooling locally, without falling back to the old ViewTree. The 5000-node/60-level snapshot truncation applies equally to dump, selector, tap, inspect, and verify.

### `view-locate`

```text
jugg view-locate (--text <t> | --resource-id <id> | --content-desc <desc> | --class-name <cls>)
                 [--visible-only <true|false>] [--max-results <1..100>]
```

| CLI flag | MCP argument |
|----------|--------------|
| `--text` | `target.text` |
| `--resource-id` / `--resourceId` | `target.resourceId` |
| `--content-desc` / `--contentDesc` | `target.contentDesc` |
| `--class-name` / `--className` | `target.className` |
| `--visible-only` / `--visibleOnly` | `visibleOnly` |
| `--max-results` / `--maxResults` | `maxResults` |

Multiple selectors use AND. `resourceId` matches a full or short ID; `className` exactly matches a full class name or simple name. If the CLI omits `visibleOnly` / `maxResults`, it sends neither, letting MCP use defaults of `true` / `10`. The response includes `matchCount`, `returnedCount`, `truncated`, and `matches[]`; top-level bounds/position/size appear only on a unique match. Source file and line number are also returned when the runtime provides them.

### `view-inspect`

```text
jugg view-inspect (--text <t> | --resource-id <id> | --content-desc <desc>)
                  [--class-name <cls>] <expr1> [<expr2> ...]
```

| CLI flag | MCP argument |
|----------|--------------|
| `--text` | `target.text` |
| `--resource-id` / `--resourceId` | `target.resourceId` |
| `--content-desc` / `--contentDesc` | `target.contentDesc` |
| `--class-name` / `--className` | `target.className` |
| Positional argument | `expressions[]` |

Expressions may be getter/query methods or bare names. A bare name first reads a public field, then resolves a Kotlin property / `getXxx()` / `isXxx()`, for example `getText()`, `layoutParams.leftMargin`, or `getLayoutParams().getMarginStart()`.
Android nodes expose the original View; Compose nodes expose a Dragonfly node object, so a View-only getter may return an item-level error.

### `tap`

```text
jugg tap [--action tap|long-press|swipe]
         (--x <n> --y <n> [--end-x <n> --end-y <n>]
         | --x-percent <n> --y-percent <n> [--end-x-percent <n> --end-y-percent <n>]
         | --text <t> | --resource-id <id> | --content-desc <desc> [--class-name <cls>])
         [--duration <ms>]
```

| CLI flag | MCP argument | Description |
|----------|--------------|-------------|
| `--action` | `action` | `tap`, `long-press`, or `swipe`; default `tap`. |
| `--x` / `--y` | `x` / `y` | Coordinate-mode start. |
| `--end-x` / `--endX` | `endX` | Swipe end x. |
| `--end-y` / `--endY` | `endY` | Swipe end y. |
| `--x-percent` / `--xPercent` | `xPercent` | Percentage-mode start x, in 0–100. |
| `--y-percent` / `--yPercent` | `yPercent` | Percentage-mode start y, in 0–100. |
| `--end-x-percent` / `--endXPercent` | `endXPercent` | Swipe percentage end x. |
| `--end-y-percent` / `--endYPercent` | `endYPercent` | Swipe percentage end y. |
| `--text` | `text` | Element-mode selector. |
| `--resource-id` / `--resourceId` | `resourceId` | Element-mode selector. |
| `--content-desc` / `--contentDesc` | `contentDesc` | Element-mode selector. |
| `--class-name` / `--className` | `className` | Element-mode AND filter. |
| `--duration` | `duration` | Gesture duration in ms. |

Element-mode selectors and dumps use the same Dragonfly node model. A Compose node currently dispatches MotionEvent at its bounds center to its owning root View. This is not equivalent to a Compose Semantics action and cannot reliably detect disabled or stale nodes.

`swipe` requires end coordinates in coordinate mode or end percentages in percentage mode.

### `devices`

```text
jugg devices
```

There are no subcommand arguments.

IDEA and standalone Runtime both support this command. Without `--serial`, standalone returns every online device. With serial, it returns only the exactly matching online device, or `NO_DEVICE` if none matches.

### `activity-stack`

```text
jugg activity-stack
```

There are no subcommand arguments.

### `ssh-info`

```text
jugg ssh-info --reason <reason>
```

| CLI flag | MCP argument |
|----------|--------------|
| `--reason` | `reason` |

### `report`

```text
jugg report
jugg --serial emulator-5554 report
```

The CLI first calls `report-prepare` to create the final ZIP, then displays its local path, total size, fixed upload address, and each manifest entry's path and size. As in IDEA, the list puts Jugg logs first and otherwise retains generation order. The CLI does not additionally show sensitivity levels or redaction states. The confirmation prompt is `[Y/n]`: Enter alone, `y`, or `yes` calls `report-upload`; any other input, EOF, or interruption keeps the local ZIP without uploading. The upload carries the `reportId` and SHA-256 returned by prepare. Before making the HTTPS request, the server verifies that same ZIP again and explicitly fails if its contents changed.

For compatibility, `report` still accepts `--serial`, but does not filter devices with it. With multiple online devices, it collects error logcat from all targets on a best-effort basis. If one device read fails, only that device's logs are omitted; other diagnostics and device logs continue.

`report` currently does not distinguish `--console=json`; it always displays the same file list and confirmation interaction. It offers no `--yes`, custom upload address, or per-item selection arguments.

On upload success, the message matches IDE: `Report uploaded. Jugg Report ID: <reportId>`. The final response retains only `reportId`; entries, temporary `filePath`, and artifact `type/path` no longer appear. Those are visible only in the pre-upload confirmation list.

### `wait-logs`

```text
jugg wait-logs --marker <regex> [--tags <t1,t2,...>] [--timeout-ms <ms>]
```

| CLI flag | MCP argument | Description |
|----------|--------------|-------------|
| `--marker` | `marker` | Required Java Pattern regex. |
| `--tags` | `tags` | Comma-delimited tag allowlist. |
| `--timeout-ms` / `--timeoutMs` | `timeoutMs` | Hard timeout in `[1000, 300000]`, default 30000. |

---

## 7. Troubleshooting entry points

| Symptom | First place to inspect |
|---------|------------------------|
| Whether a subcommand is public and covered by help | `jugg.py::COMMANDS` + `help_registry.py::COMMAND_HELP` |
| Whether a CLI flag maps correctly to an MCP argument | Corresponding `cmd_*.py::build_params()` |
| kebab-case argument has no effect | `jugglib.normalize_args()` |
| CLI cannot find the project | `jugglib.resolve_project_dir()` and `list-projects` response |
| A compile-type command keeps waiting | `status.isCompiling` and `jugglib.wait_for_compile_idle()` |
| Command reports compile success but deployment failure | Terminal `isCompileSuccess` / `isDeploySuccess` and `full log` |
| CLI/skill still has old text or behavior after plugin update | Bundled `SKILL.md` `version` must exceed `~/.jugg/skills/jugg-android-dev-loop/SKILL.md`; see §3.7. |

---

## 8. Related documents

- MCP tool argument list: `08_mcp_tools_list.md`.
- MCP design: `08_mcp_design.md`.
- Code path quick reference: `98_code_map.md`.
- Skill synchronization after CLI/MCP behavior changes: `08_mcp_design.md` §9–§10.
- CLI/skill version increments: §3.7 of this page.
