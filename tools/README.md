# Tools

## fetch_github_issue.py

`fetch_github_issue.py` reads a GitHub Issue through the read-only GitHub REST
API. It does not use MCP, modify the Issue, or store credentials.

Basic usage:

```bash
python3 tools/fetch_github_issue.py \
  https://github.com/tencentmusic/jugg/issues/37
```

The default output is Markdown and includes the Issue body, all comments, labels,
state, and detected Jugg Report IDs. Use `--json` for structured output or
`--no-comments` when only the Issue body is needed.

If an unauthenticated GitHub API request is rate-limited with HTTP 403/429, the
script automatically reads GitHub's public Issue page and its embedded
structured data instead.

For public Issues, no token is normally required. For private repositories or
GitHub API rate limits, set a GitHub personal access token in the environment:

```bash
export GITHUB_TOKEN="<your-token>"
python3 tools/fetch_github_issue.py \
  https://github.com/tencentmusic/jugg/issues/37
```

Do not put the token in the command line, repository files, Issue body, or chat
messages. The script only reads `GITHUB_TOKEN` at runtime.

## collect_jugg_scene.command

`collect_jugg_scene.command` collects a local Jugg troubleshooting scene into one
folder. It is intended for on-site extraction when a user machine has useful
`build/jugg` state but you do not want to manually pick files one by one.

Double-click the script on macOS, input the Android project directory, then use
the generated `jugg_scene_*` folder beside the script. Finder opens the folder
after collection. Git Bash or WSL can run the same script on Windows.

To collect a scene on a user's machine that does not have this repository,
send `tools/collect_jugg_scene_prompt.md` as-is. The user pastes the whole file
into their Android project's coding agent. The agent downloads
`tools/collect_jugg_scene.command` from the public GitHub repository, runs it
with `--output-root` on the desktop and `--zip`, then the user sends back the
revealed `jugg_scene_*.zip`.

Command-line usage:

```bash
tools/collect_jugg_scene.command /path/to/android/project
tools/collect_jugg_scene.command /path/to/android/project --package-name com.example.app
tools/collect_jugg_scene.command /path/to/android/project --device-serial emulator-5554
tools/collect_jugg_scene.command /path/to/android/project --skip-adb --no-open
tools/collect_jugg_scene.command /path/to/android/project --output-root /tmp/jugg-scenes
tools/collect_jugg_scene.command /path/to/android/project --output-root ~/Desktop --zip
```

Collected content:

- `build/jugg/log`: compile and logcat logs.
- `build/jugg/database`: project info, compile context, deploy history and other state.
- `build/jugg/build/staging`: staged overlay or dex outputs.
- `build/jugg/build/compiled`: compiled outputs when present.
- `build/jugg/classpath`: classpath inventory, mapping-like text files, and all
  discovered `R.jar` candidates with path, size, mtime, and SHA-256 metadata.
- APK files under `build/jugg/classpath/apk`; copied by default.
- Git metadata and optional `adb devices`, crash buffer, and logcat tail snapshots.
- ADB resolution metadata in `meta/adb_resolution.txt`. The collector checks
  `PATH`, Android SDK environment variables, project `local.properties`, and
  standard SDK directories on macOS, Linux, and Windows so double-click or Git
  Bash launches can still find ADB.
- ADB device selection metadata in `meta/adb_targets.txt`; when multiple online
  devices are connected, each device is collected under `device/devices/<serial>`.
  Pass `--device-serial` or set `ANDROID_SERIAL` to collect only one device.
- Device APK paths and pulled device APK files when a package name can be
  inferred from Jugg project info or is passed by `--package-name`.
- Device direct overlay dex files from `run-as <package> code_cache/.overlay`.
- Device overlay inventory plus every `resource.ap_`, `resources.arsc`,
  `.jugg_compat_deploy_enable`, and overlay `id` file found under
  `run-as <package> code_cache/.overlay`.
- APK consistency diagnostics in `device/apk_consistency.txt`, based on exact
  sha256 matches between local APK files and pulled device APK files.
