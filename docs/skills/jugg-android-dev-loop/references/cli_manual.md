# Jugg CLI Manual

CLI entry: `python3 {SKILL_DIR}/scripts/jugg.py <subcommand> [options]`.

### CLI Output Format

Controlled by the global `--console` flag (must appear before the subcommand):

| Value | Spinner | Output style | Typical use |
|-------|---------|--------------|-------------|
| `plain` | off | human-readable key: value (default) | agent / script |
| `rich` | on | human-readable key: value | human terminal (set by shell wrappers) |
| `json` | off | raw JSON `{status, message, data, ...}` | agent needing structured data |

Example:
```
python3 {SKILL_DIR}/scripts/jugg.py --console=json deploy
```

All flags accept both kebab-case (`--resource-id`) and camelCase (`--resourceId`).

---

## Version Command

```
python3 {SKILL_DIR}/scripts/jugg.py version
```

Show CLI version and Jugg plugin version from all initialized IDE projects.

Output when all projects share the same version:
```
cli version: 1.0.1
plugin version: 1.2.3
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

## Build & Deploy Commands

All build commands **block** until completion; no polling needed.

| Command | Purpose | When to Use |
|---------|---------|-------------|
| `compile` | Compile modified sources, no deploy | No device, or user requests compile-only |
| `deploy` | Compile + deploy to device | **Default path** |
| `gradle-build` | Full Gradle compile fallback | After `deploy` retries exhausted; produces artifact only, follow with `deploy` |
| `clean-reinstall` | Clear app data + reinstall APK | **Only** for clean data situation |

```
python3 {SKILL_DIR}/scripts/jugg.py compile
python3 {SKILL_DIR}/scripts/jugg.py deploy
python3 {SKILL_DIR}/scripts/jugg.py gradle-build
python3 {SKILL_DIR}/scripts/jugg.py clean-reinstall
```

---

## Runtime & Observe Commands

| Command | Purpose |
|---------|---------|
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

---

## UI Inspection Commands

| Command | Purpose |
|---------|---------|
| `view-locate` | Find element position & bounds |
| `view-inspect` | Query View properties via reflection |
| `layout-dump` | Dump full UI hierarchy to HTML |

### `view-locate`

```
python3 {SKILL_DIR}/scripts/jugg.py view-locate --text "Submit"
python3 {SKILL_DIR}/scripts/jugg.py view-locate --resource-id btn_confirm
python3 {SKILL_DIR}/scripts/jugg.py view-locate --content-desc "Back"
```

At least one of `--text`/`--resource-id`/`--content-desc` required.
Output: `bounds [left,top,right,bottom]`, `position {x,y}`, `size {width,height}` (all dp).

### `view-inspect`

```
python3 {SKILL_DIR}/scripts/jugg.py view-inspect --text "Submit" text visibility
python3 {SKILL_DIR}/scripts/jugg.py view-inspect --resource-id btn_confirm background.color textSize
python3 {SKILL_DIR}/scripts/jugg.py view-inspect --content-desc "Avatar" width height translationY
```

- Selector: `--text`/`--resource-id`/`--content-desc` (at least one).
- `<expr>`: dot-path to View property. Common: `text`, `visibility`, `width`, `height`, `textSize`, `textColor`, `background.color`, `translationX`, `translationY`, `alpha`.
- Output: expression/value/type pairs + density for px→dp conversion.

### `layout-dump`

```
python3 {SKILL_DIR}/scripts/jugg.py layout-dump
python3 {SKILL_DIR}/scripts/jugg.py layout-dump --root-layout content_frame   # subtree only (View resource name, not R.id.xxx)
python3 {SKILL_DIR}/scripts/jugg.py layout-dump --include-gone                # include GONE views
python3 {SKILL_DIR}/scripts/jugg.py layout-dump --all-windows                 # all windows (dialogs, popups)
```

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
python3 {SKILL_DIR}/scripts/jugg.py status
```

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

---

## Build Fallback Chain

On compile/deploy failure, follow this order:

1. Parse `status`/`message` from JSON output.
2. Retry `deploy` up to 3 times.
3. If still failing → `gradle-build`.
4. If still failing → inspect `${projectDir}/build/jugg/log/compile_latest.log`.
5. Only on install-state corruption → `clean-reinstall`.
6. Still unclear → stop, ask user.
7. `ssh-info` requires explicit user consent.
