# Jugg CLI commands and MCP mapping

> Last checked: 2026-10-07. Code takes precedence over this page. For the MCP tool catalog and diagnostic contract, see [08_mcp_tools_list.md](08_mcp_tools_list.md); the connected Runtime's `tools/list` owns exact `inputSchema` details.

## 1. Source owners

| Boundary | Source |
| --- | --- |
| Public commands, global options, local help | `docs/skills/jugg-android-dev-loop/scripts/jugg.py`, `py/help_registry.py` |
| Project/Runtime selection, serial injection, polling, output | `docs/skills/jugg-android-dev-loop/scripts/py/jugglib.py` |
| Command-specific parsing | `docs/skills/jugg-android-dev-loop/scripts/py/cmd/cmd_*.py` |
| MCP implementation and host capability | `McpToolActionRegistry.kt`, `StandaloneProjectRegistry.kt` |
| Installed CLI and skill update | `JuggCliAutoUpdater.kt`, `docs/skills/jugg-android-dev-loop/SKILL.md` |

The public CLI is an MCP client, except `stop`, which invokes the standalone launcher locally. `list-projects` and `get-compile-status` are MCP tools used internally, not CLI subcommands. A registered action is not necessarily exposed by every Runtime; check `version` capabilities or `tools/list`.

## 2. Public command index

`jugg.py::COMMANDS` currently exposes 18 commands. The option column lists CLI-specific flags; global options below apply separately. Kebab-case flags also accept their camelCase MCP spelling where the parser defines that spelling. An omitted flag is omitted from the MCP request.

| CLI command | MCP tool | Command options and mapping |
| --- | --- | --- |
| `version` | `version` | No command options or MCP `projectDir`; reports `CLI_VERSION` plus Runtime/plugin version; JSON wraps them as `{cliVersion, plugin: structuredContent}`. |
| `stop` | Local launcher | No command options; stops all standalone processes under this Jugg root without deleting project history or logs. `--project-dir` does not narrow the scope; `--runtime idea` fails. |
| `compile` | `compile` | No command options; compiles without deployment. |
| `deploy` | `deploy` | `--always-restart-app true|false` → `alwaysRestartApp`. |
| `gradle-build` | `gradle-build` | No command options; full build followed by install/start flow. |
| `clean-reinstall` | `clean-reinstall` | No command options; clear data and reinstall. |
| `restart` | `restart` | No command options; restart target app. |
| `instrument` | `instrument` | Required `--source-path` → `sourcePath`; optional `--class`, `--method`, `--runner`; `--extras k=v;k2=v2` becomes MCP `extras` object. |
| `status` | `status` | `--refresh-changes true|false` → `refreshChanges`; `--full-info true|false` → `fullInfo`. |
| `layout-dump` | `layout-dump` | `--root-layout` → `rootLayout`; `--include-gone`/`--all-windows` send `includeGone=true`/`allWindows=true`. |
| `view-locate` | `view-locate` | One or more `--text`, `--resource-id`, `--content-desc`, `--class-name` → fields of `target`; `--visible-only true|false`, `--max-results 1..100` → top-level fields. |
| `view-inspect` | `view-inspect` | At least one selector above → `target`; positional expressions → `expressions[]`. |
| `tap` | `tap` | `--action tap|long-press|swipe`; `--x/--y` or `--x-percent/--y-percent`, optional matching `--end-x/--end-y` or percent endpoints; alternatively `--text/--resource-id/--content-desc` with optional `--class-name`; `--duration` → `duration`. |
| `devices` | `devices` | No command options. |
| `activity-stack` | `activity-stack` | No command options. |
| `ssh-info` | `ssh-info` | Required `--reason` → `reason`. |
| `report` | `report-prepare`, then `report-upload` | No command options; upload needs interactive confirmation. |
| `wait-logs` | `wait-logs` | Required `--marker` → Java Pattern `marker`; `--tags t1,t2` → string array; `--timeout-ms` → `timeoutMs` (1,000–300,000 ms; MCP default 30,000). |

`view-locate` combines multiple selectors with AND; omitted `visibleOnly` and `maxResults` use MCP defaults `true` and `10`. Its top-level bounds appear only for a unique match. `view-inspect` evaluates read-only expressions against an Android View or Dragonfly Compose node; a View-only getter on a Compose node may return an item error. Element-mode `tap` dispatches at the Compose node center through its root View, not as a Compose Semantics action. Swipe requires matching end coordinates or percentages. These UI commands use live Dragonfly snapshots; Dragonfly bundles private Kotlin/coroutine runtimes, works in a pure Java app, and contains incompatible Compose tooling without reverting to the legacy ViewTree. `layout-dump` publishes HTML, while its JSON is internal. The 5,000-node/60-level truncation applies to the shared snapshot.

`instrument` requires an androidTest source-file anchor. It rejects `--package`, `--testPackage`, `--testsRegex`, `--regex`, and old `--clazz`, `--instrumentationRunner`, `-e`/`--e` aliases. Without a persisted AndroidTest full-build baseline, the MCP action returns `INVALID_PARAMS`; enable Android Test, run a full build, then confirm `status.data.enabledAndroidTest=true`. `enabledAndroidTest` describes the persisted baseline, not merely a UI toggle. See [06_android_test.md](06_android_test.md) for APK/test ownership.

## 3. Global request behavior

### 3.1 Dispatch and help

The global options are `--console=plain|rich|json`, `--project-dir PATH`, `--serial SERIAL`, `--runtime idea|standalone`, and `--if-compiling wait|interrupt`. `jugg.py` extracts them before subcommand dispatch. `--projectDir` and `--ifCompiling` are accepted camelCase forms. Direct `python3 jugg.py` defaults to plain output; wrappers choose rich. `json` prints MCP `structuredContent` without progress on stdout. `report` is an exception: it always shows the review list and interactive confirmation, even with `--console=json`. Help (`jugg --help`, `jugg help <command>`, `jugg <command> --help`) reads the local registry without project or MCP discovery.

### 3.2 Project and Runtime selection

Project resolution starts at the nearest upward `settings.gradle(.kts)`. Automatic selection requires ownership of that exact Gradle project, so an independently nested project is not captured by an open parent IDEA project. Explicit `--project-dir` allows longest-prefix matching to an initialized parent project. Matching IDEA Runtime wins over standalone in automatic mode; `--runtime` forces a host type. Otherwise the CLI reuses an existing standalone Runtime and registers the project on the first valid request, starting a daemon only if none exists. The selected port stays fixed for that command. The cache only prioritizes probing and cannot override ownership. `JUGG_PORT_CACHE` and `JUGG_CACHE_DIR` override the port-cache file and cache root respectively. On macOS, matching is case-folded while progress preserves the displayed path casing.

### 3.3 Discovery and startup

`jugglib` probes `12320..12329` in parallel, then checks `version` and `list-projects`. A “no port passed MCP probing” summary means only that this scan found no usable endpoint; inspect per-port results before assigning a transport, IDE, or plugin cause. Only timeout, HTTP 5xx, or unexpected probe exceptions get one short retry; plain connection refusal does not. If no standalone Runtime exists, launch is serialized by `~/.jugg/locks/standalone.launch.lock` (override: `JUGG_STANDALONE_LAUNCH_LOCK`), with a recheck under the lock. Launcher default is `~/.jugg/standalone/bin/jugg-standalone` or Windows `.bat` (override: `JUGG_STANDALONE_LAUNCHER`). Launch and first-project registration each have a 60-second limit; lock acquisition has 75 seconds. A startup process still alive at timeout gets one final discovery pass. Startup details go to `build/jugg/log/standlone_cli/standalone_startup.log`; after 10 seconds, project Runtime log heartbeats use `build/jugg/log/standlone_cli/compile_latest.log`. Missing heartbeat logs do not abort startup. Hook calls with `JUGG_CALLER=hook` may launch/register only after the project's `build/jugg/database/compile_context.db/complete_flag` exists; otherwise they skip successfully.

### 3.4 Device selection

Global `--serial` is injected only into device-target MCP calls: `deploy`, `gradle-build`, `clean-reinstall`, `restart`, `instrument`, `status`, `devices`, `layout-dump`, `view-locate`, `view-inspect`, `tap`, `activity-stack`, `wait-logs`, and `report-prepare`. It requires an exact online serial, overrides selected IDEA device or inherited standalone `ANDROID_SERIAL` for this request only, and does not change later calls. `report` accepts the flag but ignores its value and gathers available device error logs best-effort. Without serial, deploy/instrument can process multiple targets and restart affects all targets; single-device UI/log operations return `MULTIPLE_DEVICE` when ambiguous. `compile`, `version`, `stop`, and `ssh-info` do not consume serial.

### 3.5 Host capabilities

Standalone exposes `compile`, `deploy`, `gradle-build`, `restart`, `devices`, `report`, and `status` among public CLI commands. `clean-reinstall`, `instrument`, UI commands, `activity-stack`, and `wait-logs` need IDEA Runtime. Standalone full builds can use the selected remote SSH/iFT profile, though local project-info Gradle work can still precede remote compilation. No authentication dialog is available in standalone: missing credentials or unauthenticated iFT produce terminal failure. Without serial, standalone deploy/restart targets all online devices; a full build without a device can compile but fail at deployment.

### 3.6 Long-running calls and output

`compile`, `deploy`, `gradle-build`, and `instrument` are asynchronous MCP jobs hidden behind synchronous CLI polling. With default `--if-compiling wait`, the CLI first checks `status.isCompiling` every five seconds and waits for idle; `interrupt` triggers immediately and leaves interruption to the server. This flag is CLI-only. A running response is polled through `get-compile-status(jobId, waitTimeoutMs=5000)`, preserving the initial `logPath`. Plain progress and 30-second throttled heartbeats go to stderr; rich updates its spinner; JSON has no heartbeat. Ctrl-C exits 130 without a traceback.

A successful `compile` produces compile artifacts when needed but does not deploy. For `deploy`, inspect both `isCompileSuccess` and `isDeploySuccess`; a top-level MCP `OK` on a poll response does not override `data.status=failed/canceled/unknown`. When no source file was compiled in a deploy, its message says only that: it does **not** prove no APK or other deployment action happened. The optional last changed-file deployment summary comes from the current Runtime session and can be unavailable after restart. The CLI omits MCP `waitAppReadyAfterSuccess` for deploy, gradle-build, clean-reinstall, and restart, so task completion does not add an app-readiness wait. `restart` can therefore succeed after force-stop when no launch or HOME Activity exists.

When idle, `status` can refresh Git and Runtime-owner state under the project lock. During an active task or other writer, it returns a true read-only snapshot immediately without waiting or refreshing; `isCompiling` reflects only activity in that Runtime. An apparently stale status during a build is not proof that pending files disappeared. Agent command hooks use `hasBeenFullCompiled=false` to skip their guards. After a baseline exists, `executionType=remote` makes the command hook block the first raw Gradle attempt even without a recorded write in this Agent session; local mode also checks session writes and pending files. This `executionType` describes the configured Gradle fallback environment, not where incremental compilation or device operations run.

`report` prepares a local ZIP and shows path, size, upload destination, and manifest entries (Jugg logs first). Enter, `y`, or `yes` approves upload; any other input, EOF, or interruption keeps the ZIP local. Upload uses the returned report ID and SHA-256 and verifies the ZIP again before HTTPS. A device-log read failure omits only that device's logs. No `--yes`, custom destination, or per-entry selection is supported. After upload, the response keeps only the report ID; the ZIP path and entries are available in the pre-upload review.

### 3.7 CLI and skill versions

The CLI supports Python 3.7+. `docs/skills/python_compat.json` is the compatibility contract; `tools/check_python_compat.py --target jugg_cli` checks it, and `--strict-runtime` requires `python3.7` on PATH. Wrapper launchers select a compatible interpreter (Windows tries `python3`, `python`, then `py -3`). The CLI's own version is `CLI_VERSION` in `cmd_version.py`; the installed CLI/skill auto-update compares the bundled `docs-skills.zip` skill `version:` with `~/.jugg/skills/jugg-android-dev-loop/SKILL.md`. A newer bundle replaces `~/.jugg/bin` and the installed skill; changing scripts/help without raising the skill version leaves installed users on old content. When changing CLI scripts, help, or skill references, increment both `CLI_VERSION` and the skill frontmatter `version` and `date`.

## 4. Related documents

- [08_mcp_tools_list.md](08_mcp_tools_list.md): tool catalog and diagnostic contract; use live `tools/list` for exact parameters.
- [08_mcp_design.md](08_mcp_design.md): protocol, host capability, and synchronization rules.
- [06_testing.md](06_testing.md): verification evidence and targeted testing.
