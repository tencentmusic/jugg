# System-App Deployment and App Sandbox Access

> Last verified: 2026-10-07
> Consistency rule: If documentation conflicts with code, code takes precedence.

## 1. Scope

This page separates initial system-partition placement, APK installation or signing inside a Jugg Run, and incremental deployment to an app whose sandbox rejects Android Studio's `run-as` requirements. Jugg has no built-in system-partition installer or priv-app allowlist writer. A configured project install script can take over install/reinstall; a separate signing script can sign rewritten APKs. For ordinary install and overlay mechanics, see `03_deploy_core.md`.

## 2. Core Source Index

| Entry | Location | Responsibility |
|---|---|---|
| `JuggDeployer.install()` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/applychanges/JuggDeployer.kt` | Selects custom script or Android Studio installer; verifies the custom result before storing the base deployment cache. |
| `CustomApkInstallScriptRunner` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/applychanges/CustomApkInstallScriptRunner.kt` | Runs the local project script, waits for ADB recovery, and checks package presence. |
| `CustomApkSignScriptRunner` / `ApkFileModifier` | `main/src/main/java/com/sickworm/intellij/jugg/apk/` | Signs a rewritten temporary APK through project script or local signing configuration, verifies it, then replaces the original. |
| `AppSandboxExecutor` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/AppSandboxExecutor.kt` | Probes Apply Changes compatibility and fixes one direct sandbox access mode for the run. |
| `DirectAppSandboxDeployTransport` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/hotreload/DirectAppSandboxDeployTransport.kt` | Takes over incremental deployment when Apply Changes cannot use the app sandbox. |
| `RootlessCompatDeployStaging` / `RootlessCompatImportConfirmer` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/hotreload/RootlessCompatDeployStaging.kt` | Stages a compat request outside app-private storage and waits for a matching import result. |
| `RootlessCompatDeployImporter` | `jvmti_agent/src/main/java/com/sickworm/intellij/jugg/hotfix/RootlessCompatDeployImporter.java` | Validates and commits that request during app startup. |

## 3. Package Identity and Verification

| Placement | Expected identity | Privileged permission condition |
|---|---|---|
| `/system/app/<name>/<name>.apk` | `FLAG_SYSTEM`; no `PRIVILEGED` private flag | Signature permission still requires the declaring platform certificate. |
| `/system/priv-app/<name>/<name>.apk` | `FLAG_SYSTEM` and `PRIVILEGED` | A `signature|privileged` permission can use a matching `/system/etc/permissions/privapp-permissions-*.xml` allowlist. |
| `/system/app` with `android:sharedUserId="android.uid.system"` | UID 1000 only if its certificate matches the platform shared user | Matching platform certificate can grant signature permissions without priv-app placement. |

Use `dumpsys package <pkg>` to inspect `codePath`, `flags`, `privateFlags`, permission grants, and UID. `FLAG_SYSTEM` establishes system-partition identity, not privileged permission. A shared-UID request with the wrong certificate may prevent package scanning entirely. A successful same-signature `pm install -r` can leave the `/system` baseline plus an active `/data/app` update marked `UPDATED_SYSTEM_APP`: effective `codePath` changes while system identity remains. Check flags before calling it a third-party conversion.

For initial placement experiments, use a device/image that permits remounting the system partition; a writable-system emulator boot, `adb root`, remount, APK push, applicable allowlist, SELinux context restoration, and reboot must finish before PackageManager status is judged. A remount error before a writable-system boot proves a mount prerequisite is missing, not that Jugg compilation failed. If `disable-verity` requests a reboot, do that before remounting and pushing. Google APIs emulator platform certificates need not match public AOSP test keys; matching certificate subjects are insufficient evidence. AOSP `default` images and Google APIs images must be judged against their own framework certificate. For an AOSP platform test key rejected by Java 17 `jarsigner`, use Android SDK `apksigner` with `platform.pk8` and `platform.x509.pem`. Debug APKs marked `testOnly` may scan but not launch normally from the launcher. Hidden API stubs or `framework.jar` affect compilation only; they do not establish system placement.

## 4. Installation and Signing Boundaries

```text
Initial system placement: external operation or project install script
  -> system APK and optional priv-app allowlist are scanned after restart
  -> Jugg install/reinstall: configured script or Android Studio installer
     -> custom result must be present and match Jugg's APK checksum
     -> Jugg clears old Direct Overlay when sandbox access permits
     -> deployment cache records the installed base
  -> later incremental classes/resources/assets enter overlay transport
  -> Manifest/native or other APK rewrite enters temporary APK signing/verification
     and then install; overlay state is replayed by the deployment flow
```

Without a custom install script, Android Studio/Jugg install uses the normal package installer; it does not push into `/system`. Updating an existing system package requires the new APK's signing identity to match the baseline APK under `/system`; `INSTALL_FAILED_UPDATE_INCOMPATIBLE` is signature evidence, not proof that `/system` is unwritable. `adb uninstall` of a system-app update removes the `/data` layer, not the baseline. To change the baseline itself, remove the update layer, replace the system APK, then rescan by reboot or system restart.

The install script runs locally at the project root, including after remote compilation, through Bash on macOS/Linux or `cmd.exe` on Windows; Bash does not load shell startup files. It receives the inherited build environment with SDK `platform-tools` added to `PATH`, but Jugg injects no APK path, device, or applicationId arguments. It is used per ordinary-app APK install/reinstall, not androidTest APKs. A script that reboots must wait until ADB and PackageManager are ready. Nonzero exit, cancellation, unavailable ADB, missing package, or APK checksum mismatch fails the install flow; a script failure itself is not eligible for deployment retry or Gradle fallback. Later deployment failures can retry or reinstall and therefore rerun the script. The project script owns system path, allowlist, remount, and reboot behavior.

The independent sign script receives the aligned temporary APK's absolute path as its final argument and must overwrite that file in place. `ApkFileModifier.insertAndResign()` runs either this script or local-keystore signing, then `apksigner verify`, and replaces the original APK only on success. Script failure never falls back to the local keystore; the custom path does not require a valid local SigningConfig. It applies to Jugg's local incremental APK rewriting, including Manifest/native updates and the Embedded APK path that packages changed classes and overlays; it does not sign a full Gradle build or the separate CLI incremental APK export. Recovery installs an already signed APK; a multi-device Run can invoke signing repeatedly. Script bodies are hidden in safe logs, while output and cancellation use the Run interaction channel.

## 5. Incremental Access After Installation

`AppSandboxExecutor` tests a rollback-safe `run-as` write using a unique marker, raw UID `10000..19999`, and matching probe/`code_cache` SELinux contexts. Package flags, UID alone, and one `run-as` error string cannot substitute for this capability test. If Apply Changes is incompatible, it probes the actual PackageManager `dataDir` through ordinary shell, root adbd, and noninteractive `su`; successful direct access also requires owner and SELinux repair. The mode is fixed for that run; `03_deploy_core.md` explains the distinct labels needed for overlay writes and executable JVMTI agents.

```text
Apply Changes-compatible sandbox -> Android Studio deploy transport
incompatible, direct sandbox available -> Direct Overlay + Jugg startup agent
  -> new DEX classes can be added live; eligible modified classes use JVMTI redefine
  -> Android 11+ ordinary resources can refresh running host Resources and Activity
  -> structural, unsupported resource, or failed live update restarts the app
incompatible, sandbox unavailable -> compat payload staged in app external files
  -> app imports at next startup -> Host confirms matching requestId
  -> only then store deployment cache and later commit deploy history/file state
```

Direct transport takes over *after* installation; it does not make a package a system app. Manifest/native changes continue through APK update and signing. For live update and resource distinctions, see `03_deploy_core.md` §6 and `03_runtime_jvmti.md`.

The rootless branch is for a compat payload when ordinary shell, root adbd, and `su` cannot enter the sandbox. A non-compat payload requests one compat redeploy; `DeployStateRecover` ignores that signal for an empty recovery dry payload so the real payload can take the retry. An empty compat payload cannot be staged. `RootlessCompatDeployStaging` writes `payload.zip`, metadata, and a `ready` marker last under `/sdcard/Android/data/<package>/files/jugg/rootless-compat/<requestId>/`, where the app can read it through external-files access even when SELinux blocks `/data/local/tmp`. The app-side importer requires an already injected Jugg compat runtime; it validates package, request, digest, and expected overlay ID, then writes the new private overlay ID last. Reobserving that request's committed `nextOverlayId` is idempotent success; another ID is a state mismatch. `JuggDeployOrchestrator` waits up to 30 seconds for that request's `__JUGG_ROOTLESS_IMPORT__ OK` line. A restart, successful `am start`, missing log, timeout, or a `FAILED` line cannot commit Host cache/history. This branch neither prepares nor attaches a JVMTI agent. See `03_runtime_jvmti.md` for startup order.

## 6. Diagnostic Boundaries

| Observation | What it proves | What it does not prove | Next evidence |
|---|---|---|---|
| Default install succeeds | Package installer accepted an APK. | Initial system placement. | `dumpsys package`: baseline path, `SYSTEM`, `PRIVILEGED`. |
| Custom script succeeds | Package exists and installed APK checksum matches Jugg's input. | System/privileged identity. | `dumpsys package` identity and permission grants. |
| `FLAG_SYSTEM`, permission denied | System identity. | Priv-app status or allowlist success. | `privateFlags`, allowlist XML, PackageManager logs, certificate. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Existing package and update have incompatible signatures. | Incremental compiler failure. | Compare installed and output certificate digests. |
| `/data/app` effective path after update | A data update is active. | System identity was lost. | `UPDATED_SYSTEM_APP`, `SYSTEM`, and `PRIVILEGED` flags. |
| Direct sandbox unavailable | Host cannot perform the required private write. | Initial placement failed. | Sandbox probes, package identity, and rootless import result. |

Before concluding that Jugg cannot update a system app, check whether the current run is a first placement or a same-signature update. Before concluding that an app is privileged, check `privateFlags` and the actual grant. Without PackageManager evidence, report only the install result.

## 7. Related Documents

- `03_deploy_core.md` — installation and overlay transports.
- `03_runtime_jvmti.md` — startup agent and rootless import order.
- `03_deploy_complete.md` — state commit after deployment.
