# Tools

## collect_jugg_scene.command

`collect_jugg_scene.command` collects a local Jugg troubleshooting scene into one
folder. It is intended for on-site extraction when a user machine has useful
`build/jugg` state but you do not want to manually pick files one by one.

Double-click the script on macOS, input the Android project directory, then use
the generated `jugg_scene_*` folder beside the script. Finder opens the folder
after collection.

Command-line usage:

```bash
tools/collect_jugg_scene.command /path/to/android/project
tools/collect_jugg_scene.command /path/to/android/project --package-name com.example.app
tools/collect_jugg_scene.command /path/to/android/project --skip-adb --no-open
tools/collect_jugg_scene.command /path/to/android/project --output-root /tmp/jugg-scenes
```

Collected content:

- `build/jugg/log`: compile and logcat logs.
- `build/jugg/database`: project info, compile context, deploy history and other state.
- `build/jugg/build/staging`: staged overlay or dex outputs.
- `build/jugg/build/compiled`: compiled outputs when present.
- `build/jugg/classpath`: classpath inventory and mapping-like text files.
- APK files under `build/jugg/classpath/apk`; copied by default.
- Git metadata and optional `adb devices` / logcat tail snapshot.
- Device APK paths and pulled device APK files when a package name can be
  inferred from Jugg project info or is passed by `--package-name`.
- Device direct overlay dex files from `run-as <package> code_cache/.overlay`.
- APK consistency diagnostics in `device/apk_consistency.txt`, based on exact
  sha256 matches between local APK files and pulled device APK files.
