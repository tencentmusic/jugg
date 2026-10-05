# `demo_release` guide

This demo shows the Jugg command-line incremental build flow for **release + minify + AabResGuard**.

## Files

- `1_build_base.sh`: run `bundleReleaseToApk` to produce the release baseline APK and initialize `build/jugg` for later incremental builds.
- `2_modify_project.sh`: change sources and resources in a working copy to verify the release incremental APK.
- `3_build_incremental_apk.sh`: build a release incremental APK from the backed-up `build/jugg` and changed files.
- `4_install_and_launch.sh`: install the generated release APK and launch the app.
- `_common.sh`: shared path and preparation logic for both the source repository and an extracted distribution.

## Prerequisites

- `JAVA_HOME` and `ANDROID_HOME` are configured.
- `adb` and `unzip` are available locally.
- The release project provides a `bundleReleaseToApk` task (this demo uses `android_demo_project/app/aabResGuard.gradle`).

## Run in order

```bash
sh 1_build_base.sh
sh 2_modify_project.sh
sh 3_build_incremental_apk.sh
sh 4_install_and_launch.sh
```

## Key outputs

- Baseline / incremental APK: `outputs/duplicated-app.apk`.
- Jugg baseline backup: `backups/jugg_bak`.
- Release mapping: `demo_project/app/build/outputs/mapping/release/mapping.txt`.
- Release usage: `demo_project/app/build/outputs/mapping/release/usage.txt`.

## Project preparation

- In an extracted distribution, use `demo_project.zip` in the same directory first.
- In the source repository without `demo_project.zip`, copy `android_demo_project` instead.
- Reuse custom compiler JARs from `../demo/custom_compilers` by default.
