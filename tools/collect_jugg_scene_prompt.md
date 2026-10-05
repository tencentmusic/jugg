# Collect a Jugg issue snapshot

Send **this entire file** to an AI assistant in the affected Android project (Cursor, Claude Code, Codex, Trae, etc.) and ask it to follow the instructions.

When collection finishes, the result folder opens automatically. Send the `jugg_scene_*.zip` on the desktop to the maintainer. Do not paste binary contents into the chat.

On Windows, install [Git for Windows](https://git-scm.com/download/win) to run the official script with Bash, or use WSL.

---

## Agent instructions

Your only task is to collect the Jugg issue snapshot. Do not fix the bug, change code, or rebuild.

**Do not:**

- Change source, Gradle files, configuration, resources, or the Manifest.
- Run, build, deploy, install, uninstall, clear app data, clear the Jugg build, or restart the app.
- Use `--skip-adb` or `--no-open`.
- Print a ZIP, APK, or DEX into the chat.
- Rewrite the official script or substitute an inline local copy for the downloaded script.

These actions can overwrite the evidence. Keep the device unlocked and USB debugging enabled; do not interact with the app during collection.

If a sandbox blocks files, the network, adb, or USB devices, request full local access and retry. Do not skip adb or handwrite collection logic.

### 1. Locate the project

1. Use the current workspace root if it contains `build/jugg/`.
2. Otherwise, search the workspace to a maximum depth of four levels for `build/jugg`. If exactly one directory matches, use its parent as the project root.
3. If none matches, stop and ask the user for the project path. Do not invent a path or continue collection.
4. Add `--package-name` if the app package is known. If multiple devices are connected and the user specified a device, add `--device-serial`. Do not guess the package name; let the script detect it if uncertain.

### 2. Download and run the official script

Use only the unmodified script from the open-source repository:

- First choice: https://raw.githubusercontent.com/tencentmusic/jugg/main/tools/collect_jugg_scene.command
- If that returns 404 or download fails: https://raw.githubusercontent.com/tencentmusic/jugg/develop/tools/collect_jugg_scene.command

After saving it, check that it starts with `#!/usr/bin/env bash` and contains `collect_jugg_scene`. If both downloads fail, stop and tell the user that GitHub raw access is needed.

Example download:

```bash
curl.exe -fsSL "https://raw.githubusercontent.com/tencentmusic/jugg/main/tools/collect_jugg_scene.command" -o "$TEMP/collect_jugg_scene.command"
```

On macOS or Linux, replace `curl.exe` with `curl` and use `/tmp/collect_jugg_scene.command` as the destination. When passing a Windows path to Git Bash, convert it to a Unix-style path such as `/c/Users/...`.

Prefer the desktop as the output root:

- macOS / Linux: `$HOME/Desktop`, or `$HOME` if no desktop directory exists.
- Windows: `[Environment]::GetFolderPath('Desktop')`; `$HOME/Desktop` or `$HOME/OneDrive/Desktop` may also work in Git Bash.

Run this command, replacing the project and desktop paths with their actual values:

```bash
bash "<SCRIPT_PATH>" "<ANDROID_PROJECT_ROOT>" --output-root "<DESKTOP_DIR>" --zip
```

If the script reports `unknown argument: --zip`, the remote script is an older version. Retry once without `--zip`, then archive the generated `jugg_scene_*` directory as a ZIP with the same base name.

On Windows, run the same command with Git Bash, for example:

```powershell
& "$env:ProgramFiles\Git\bin\bash.exe" -lc "bash '<SCRIPT_PATH>' '<ANDROID_PROJECT_ROOT>' --output-root '<DESKTOP_DIR>' --zip"
```

If Git Bash is not there, try:

1. `%LOCALAPPDATA%\Programs\Git\bin\bash.exe`
2. `wsl.exe bash "<SCRIPT_PATH>" ... --zip`

If neither Bash nor WSL is available, stop and ask the user to install Git for Windows. Do not rewrite the collection script in PowerShell.

If the file manager does not open after the script finishes, open the result once:

- macOS: `open -R "<ZIP_PATH>"`, or `open "<OUT_DIR>"` if there is no ZIP.
- Windows: `explorer.exe /select,"<NATIVE_ZIP_OR_DIR>"`, or open the directory if selection fails.
- Linux: `xdg-open "<OUT_DIR>"`.

### 3. Report the result to the user

Read `summary.txt`, `manifest.txt`, `meta/adb_resolution.txt`, and `meta/adb_targets.txt` in the output directory. Report only:

- The project path.
- The `jugg_scene_*` directory path.
- The ZIP path, if created.
- Whether adb was found, how many devices were collected, and whether device APKs or overlays were retrieved.
- A request to send the ZIP opened in the file manager (or the entire `jugg_scene_*` directory) to the maintainer.

If local files are missing, say exactly what is missing and still hand over the collected ZIP or directory. Do not rerun Jugg to recreate evidence.
