# Special Build & Deployment Environments

Load on demand only when the project or failure involves Flutter/C++(NDK), Manifest/Native `.so` updates, minified/release variants, non-debuggable APKs, system apps, or custom install/sign scripts.

## 1. Flutter / Dart & C++ / NDK (External Builds)

- **Local incremental support**: Jugg runs the current variant's external Gradle tasks for `.dart`, Flutter assets, and C++/CMake changes and feeds their outputs into incremental deployment. Do **not** jump to `gradle-build` merely because Dart or C++ files changed.
- **Automatic full-build triggers**: Remote builds (`executionType=remote`) and deleting previously queued external-build inputs fall back to a full Gradle build.
- **Flutter Debug stale Dart code**: If Dart edits deploy cleanly but the app still runs old code, inspect process restart and `app_flutter/res_timestamp-*` invalidation before blaming compilation.

## 2. Manifest & Native `.so` Changes

- `AndroidManifest.xml` changes (and Native `.so` updates when `.so` hot update is unavailable or disabled) go through **local APK rewriting, re-signing, and installation** rather than lightweight overlays.
- Expect full install latency and a process restart; signing errors on this path point to APK signing configuration, not source compilation.

## 3. Release / Minified (`minifyEnabled`) Variants

- Incremental DEX remapping requires the selected variant's `mapping.txt`; missing `mapping.txt` fails the incremental compile task.
- After incremental deploy on a minified variant, runtime linkage or reflection errors (`NoClassDefFoundError`, `NoSuchMethodError`, `IllegalAccessError`, `AbstractMethodError`, or missing annotation/reflection lookup) frequently indicate DEX remapping, R8 removal, or `_jugg_fix` bridge boundaries rather than a source bug. Prefer `gradle-build` over blind source retries.

## 4. System Apps, Custom Install/Sign Scripts & Non-Debuggable APKs

- **System apps**: The default installer never places an APK in `/system`. `INSTALL_FAILED_UPDATE_INCOMPATIBLE` indicates a signing mismatch against the installed package, not a compilation failure.
- **Custom install / sign scripts**: Failures in a configured project install script or APK sign script are terminal for that attempt — Jugg does **not** fall back to the default installer, local keystore, or Gradle. Do not blindly retry with `gradle-build`; diagnose the script or signing environment instead. `androidTest` APKs always use the default installer.
- **Non-debuggable APKs**: Incremental deploy automatically switches to the **Embedded APK** path (rewriting incremental files into the APK and installing), which is slower than overlay deployment and triggers installation.
