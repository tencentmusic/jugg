# Jugg CLI Manual

CLI entry: `jugg <subcommand> [options]`. `projectDir` auto-resolved from `$PWD`.

### CLI Output Format

All commands print JSON to stdout:

```json
{"status": "OK|ERROR", "message": "...", "isFinal": true|false}
```

- `status: OK` + `isFinal: true` → command succeeded, terminal result.
- `status: OK` + `isFinal: false` → intermediate result; re-run same command.
- `status: ERROR` → failed; read `message` for cause.

---

## Build & Deploy Commands

All build commands **block** until completion; no polling needed.

| Command | Purpose | When to Use |
|---------|---------|-------------|
| `compile` | Compile modified sources, no deploy | No device, or user requests compile-only |
| `deploy` | Compile + deploy to device | **Default path** |
| `gradle-build` | Full Gradle compile fallback | After `deploy` retries exhausted; produces artifact only, follow with `deploy` |
| `reinstall` | Clear app data + reinstall APK | **Only** for install-state corruption or signature conflict |

```
jugg compile
jugg deploy
jugg gradle-build
jugg reinstall
```

---

## Runtime & Observe Commands

| Command | Purpose |
|---------|---------|
| `activity-stack` | Show current Activity stack |
| `restart` | Restart app, optional post-launch taps |
| `tap` | Tap/long-press/swipe on device |
| `screenshot` | Capture device screenshot |
| `record-start` | Start screen recording |
| `record-stop` | Stop recording, output mp4 path |

### `restart`

```
jugg restart
jugg restart --tap "tap:text=Login" --tap "tap:id=btn_next"
jugg restart --tap "swipe:50%,80%,50%,20%"
```

`--tap` step format: `<action>:<selector>=<value>` or `<action>:<params>`
- `tap:text=<t>` / `tap:id=<id>` / `tap:desc=<d>` — tap element
- `swipe:<startX%>,<startY%>,<endX%>,<endY%>` — swipe by percentage

Multiple `--tap` flags execute in order.

### `tap`

Selector priority: Element → Coordinate → Percent.

```
jugg tap --text "Login"                          # element (preferred)
jugg tap --id btn_submit                         # element by ID
jugg tap --desc "Close button"                   # element by content-desc
jugg tap --x 540 --y 960                         # coordinate (px)
jugg tap --xp 50 --yp 80                         # percent (last resort)
jugg tap --text "Item" --action long-press        # long-press
jugg tap --xp 50 --yp 80 --action swipe --end-xp 50 --end-yp 20   # swipe
```

- `--action {tap|long-press|swipe}` — default: `tap`.
- `swipe` requires `--end-x/--end-y` or `--end-xp/--end-yp`.

---

## UI Inspection Commands

| Command | Purpose |
|---------|---------|
| `view-locate` | Find element position & bounds |
| `view-inspect` | Query View properties via reflection |
| `layout-dump` | Dump full UI hierarchy to HTML |

### `view-locate`

```
jugg view-locate --text "Submit"
jugg view-locate --id btn_confirm
jugg view-locate --desc "Back"
```

At least one of `--text`/`--id`/`--desc` required.
Output: `bounds [left,top,right,bottom]`, `position {x,y}`, `size {width,height}` (all dp).

### `view-inspect`

```
jugg view-inspect --text "Submit" text visibility
jugg view-inspect --id btn_confirm background.color textSize
jugg view-inspect --desc "Avatar" width height translationY
```

- Selector: `--text`/`--id`/`--desc` (at least one).
- `<expr>`: dot-path to View property. Common: `text`, `visibility`, `width`, `height`, `textSize`, `textColor`, `background.color`, `translationX`, `translationY`, `alpha`.
- Output: expression/value/type pairs + density for px→dp conversion.

### `layout-dump`

```
jugg layout-dump
jugg layout-dump --root content_frame          # subtree only (View resource name, not R.id.xxx)
jugg layout-dump --include-gone                # include GONE views
jugg layout-dump --all-windows                 # all windows (dialogs, popups)
```

Output: HTML file with full UI hierarchy.

---

## Diagnostic Commands

| Command | Purpose |
|---------|---------|
| `devices` | List connected devices |
| `crash-report` | Collect latest crash report |
| `ssh-info` | Remote troubleshooting info (**requires user consent**) |

### `crash-report`

```
jugg crash-report
```

Key output fields: `hasCrash`, `crashLogs`, `isProcessAlive`, `relatedActivity`.
- `hasCrash=true` → read `crashLogs` for stack trace.
- `hasCrash=false` + `isProcessAlive=true` → no crash, skip crash path.
- `hasCrash=false` + `isProcessAlive=false` → process died without crash log; check `adb logcat` for ANR/kill.

### `ssh-info`

```
jugg ssh-info --reason "deploy fails after 3 retries, gradle-build also fails"
```

`--reason` is required. Only use after all other fallback steps exhausted and with user consent.

### Non-CLI: `adb logcat`

`adb logcat` is run directly via shell, not through `jugg`.

```bash
adb logcat -d -s <TAG>                       # filter by tag
adb logcat -d | grep -E "\\[PG\\]"           # filter playground logs
```

---

## Build Fallback Chain

On compile/deploy failure, follow this order:

1. Parse `status`/`message` from JSON output.
2. Retry `deploy` up to 3 times.
3. If still failing → `gradle-build`.
4. If still failing → inspect `${projectDir}/build/jugg/log/compile_latest.log`.
5. Only on install-state corruption → `reinstall`.
6. Still unclear → stop, ask user.
7. `ssh-info` requires explicit user consent.
