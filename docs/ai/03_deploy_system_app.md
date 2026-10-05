# System-App Deployment Constraints

> Last checked: 2026-09-15
> Consistency rule: when documentation conflicts with code, follow the code.

## 1. Scope

This page answers:

- Whether Jugg's current deployment flow can turn an app into a system or privileged app.
- The sufficient device-side conditions for ordinary system and privileged apps.
- Which observations after an initial placement failure establish an install-path issue rather than a Jugg compile/Apply Changes defect.

For general Jugg install/overlay mechanics, see `03_deploy_core.md`.

Jugg has **no built-in** installer for initially placing an ordinary APK as a system app, pushing to `priv-app`, or installing permission allowlists. A Run Configuration can enable `Enable custom APK install script` so a project's Gradle task or script takes over ordinary-app install/reinstall. That script remains fully responsible for writing system partitions, allowlists, and rebooting. If platform or server signing is needed, independently use `Enable custom APK sign script` to replace Jugg's default local-keystore signing after an incremental APK rewrite. The install and signing scripts have separate responsibilities. For an already installed debuggable app that fails Android Studio Deployer's `run-as`, ordinary-UID, or SELinux-label prerequisites, Jugg Direct transport can incrementally deploy classes, resources, and assets if shell, root adbd, or noninteractive `su` can fully access its data directory.

## 2. Core Source Index

| Class/interface | File | Role |
|---|---|---|
| `JuggDeployer.install()` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/applychanges/JuggDeployer.kt` | Common install entry point. An ordinary app may use a custom script; otherwise it calls the Apply Changes executor. On success, both paths write deployment cache and overlay ID. |
| `CustomApkInstallScriptRunner` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/applychanges/CustomApkInstallScriptRunner.kt` | Runs the user script at the local project root, adds Android SDK platform-tools to PATH, and verifies the installed APK afterward. |
| `CustomApkSignScriptRunner` | `main/src/main/java/com/sickworm/intellij/jugg/apk/CustomApkSignScriptRunner.kt` | Runs the signing script at the local project root, passing the temporary APK's absolute path as its final positional argument for platform/server signing. |
| `IAsDeployerCompat.install()` | `deploy_compat/*/AsDeployerCompat.kt` | Executes the actual AS install session. Failure wording comes from PackageManager and does not establish that installation as a system app was attempted. |
| `AppSandboxExecutor` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/AppSandboxExecutor.kt` | Unified access to private app directories. Uses a unique success marker, UID range, and probe SELinux context to test Apply Changes compatibility. If incompatible, probes ordinary shell, root adbd, and noninteractive `su` in order, then fixes the actual `dataDir` and privilege mode for this run. |
| `DirectAppSandboxDeployTransport` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/hotreload/DirectAppSandboxDeployTransport.kt` | Takes over incremental class/resource/asset deployment for `run-as`-incompatible apps before AS deployer. It writes Direct Overlay, appends new classes as in-memory Dex elements, attempts live redefine for method-body-only changes, and on Android 11+ refreshes running Resources for ordinary resources or mixed changes and recreates the Activity according to deploy mode. Failures request app restart. |
| `DirectOverlayWriter` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/direct/DirectOverlayWriter.kt` | Atomically writes `code_cache/.overlay` through `AppSandboxExecutor`, committing the new overlay ID last. |
| `RootlessCompatDeployStaging` / `RootlessCompatImportConfirmer` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/hotreload/RootlessCompatDeployStaging.kt` | Without sandbox access, stages compat payload in the app's external-files directory and reads its import result by requestId after app restart. |
| `RootlessCompatDeployArchive` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/direct/RootlessCompatDeployArchive.kt` | Rootless pending-archive protocol: payload ZIP, `request.properties` metadata, payload SHA-256, and import-result line parsing. |
| `RootlessCompatDeployImporter` | `jvmti_agent/src/main/java/com/sickworm/intellij/jugg/hotfix/RootlessCompatDeployImporter.java` | Imports a staged request early in app startup: validates protocol/package/digest/expected overlay ID, then atomically commits private staging and writes overlay ID last. |

## 3. Key Models

### 3.1 Three Kinds of System App

| Type | Placement | Device-visible result | Privileged permissions, e.g. `INSTALL_PACKAGES` |
|---|---|---|---|
| Ordinary system app | `/system/app/<Name>/<Name>.apk` | `FLAG_SYSTEM=true`, without `PRIVILEGED` | Denied when certificate does not match the platform. |
| Privileged app | `/system/priv-app/<Name>/<Name>.apk` + `/system/etc/permissions/privapp-permissions-*.xml` | `FLAG_SYSTEM=true` with `PRIVILEGED` in `privateFlags` | `signature\|privileged` can be granted through priv-app plus an allowlist. |
| sharedUserId system app | `/system/app/<Name>/<Name>.apk` with `android:sharedUserId="android.uid.system"` | UID 1000 when certificates match; no `PRIVILEGED` | Granted through **platform signing**, independent of priv-app. |

Verification entry point:

```text
dumpsys package <pkg>
  -> codePath=
  -> flags=[ SYSTEM ... ]
  -> privateFlags=[ ... PRIVILEGED ... ]
  -> android.permission.INSTALL_PACKAGES: granted=true   # present with a priv-app allowlist or matching platform signature
```

`FLAG_SYSTEM` proves only that an APK was scanned from the system partition, not that it has privileged permissions. `sharedUserId="android.uid.system"` merely requests UID 1000; its certificate must match the device's `android` shared user, or scanning rejects the package rather than installing it with another UID. Verify UID 1000 on an AOSP `default` image; AOSP test keys do not match the Google APIs image.

After a same-signature `pm install -r`, `dumpsys package` shows both the `/system/...` baseline and a `/data/app/...` update, marked `UPDATED_SYSTEM_APP`. The effective `codePath` then points to `/data/app`; this is **still a system-app update**, not conversion to a third-party app. `FLAG_SYSTEM` / `PRIVILEGED` should remain.

### 3.2 Permission Protection Levels and Signing

- `signature`: certificate must match the platform certificate declaring that permission.
- `signature|privileged`: requires **either** platform signing **or** a privileged app (`priv-app` plus Android 8+ allowlist).
- Platform signing alone with `pm install` into `/data/app` produces a third-party app, without `FLAG_SYSTEM`.

The XML allowlist applies only to `priv-app`. An ordinary `/system/app` is not granted the same permission merely because that XML names it.

## 4. Core Call Chain

With the default installer, an app must first become a system package before Jugg can update it or apply overlays like an ordinary package. With a custom APK install script, a project script can perform that first system placement at Jugg's install boundary.

```text
first placement as a system/privileged app (external flow or custom script invoked by Jugg)
  -> device must be userdebug/eng, and emulator cold-started with -writable-system
  -> adb root + adb remount make /system writable
  -> push platform-signed APK to /system/app or /system/priv-app
  -> for privileged app, also push privapp-permissions XML to /system/etc/permissions/
  -> restorecon then reboot for PackageManager to scan the system partition
  -> confirm FLAG_SYSTEM / PRIVILEGED / permission grants with dumpsys

Jugg deployment
  -> JuggDeployerHelper.deploy
  -> JuggDeployer.install / codeSwap / fullSwap
  -> when custom install script enabled for ordinary App APK: project script performs system placement or vendor install flow
  -> otherwise AS deployer: pm install into /data/app
  -> incremental stage: when rewriting Manifest/native library into APK, project script signs if custom signing enabled; otherwise use local keystore
  -> incremental stage: Direct Overlay / Apply Changes
  -> determine system-app status from dumpsys codePath / flags / privateFlags
```

Without a custom script, `Jugg deploy` or `adb install` cannot be followed by an expectation that the app becomes a system app. An external flow must push it into a system directory and reboot first. With the script enabled, Jugg invokes and verifies it at install/reinstall; it does not choose the system directory, allowlist, or reboot behavior for the script.

To **rewrite the `/system` baseline every time**, do not use `adb install` or IDE Run. The correct loop is: remove the `/data/app` update layer (`adb uninstall` on a system app only removes its update), then `remount` + `push` over `/system/.../*.apk`, then reboot or `stop`/`start` so PackageManager rescans. If Run was used in between, pushing without uninstalling leaves the `/data/app` version active.

After the system package exists, Android permits `pm install` as an update while preserving `FLAG_SYSTEM`. The **new APK must have the same signature** as the baseline APK under `/system`. Android Studio/Jugg's default debug keystore differs from the platform signature and yields `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, appearing as an inability to update. Use the same platform keys for debug and release as for the initial push; uninstalling and reinstalling a debug-signed APK cannot remove the system-partition baseline.

Jugg's default install uses the same AS installer and is **expected** to update an existing system package after signatures match. If the platform signature differs from the debug keystore, a custom APK signing script can have the project resign Jugg's rewritten Manifest/native-library APK with the platform certificate before install. With a custom install script, Gradle install, APK updates, and recovery reinstall may each rerun it. The script must install the APK supplied by Jugg this run or checksum verification fails. After verification, Jugg clears old `code_cache/.overlay` when the app sandbox is accessible, then records a new base deployment cache so a system-app reinstall retaining app data does not repeatedly cause overlay-state mismatch. Classes, resources, and assets may use Direct app sandbox transport. Manifest/native libraries continue through the existing APK-update, signing, and install flow, then overlays are replayed.

### 4.1 Custom APK Install Script Contract

- With the UI switch disabled, show only the switch and retain default installation. Enabling it shows a single-line-height input panel with a project-script example placeholder.
- Run the script at the local project root. Use Bash on macOS/Linux and `cmd.exe` on Windows. Even after remote compilation outputs are fetched, execute on the local host.
- Jugg injects no device, applicationId, or APK-path variables. The script inherits the IDE/Gradle environment, and Android SDK `platform-tools` is added to `PATH`. Bash does not load the user's shell startup files.
- Run per device and applicationId for ordinary-app APK install/reinstall; androidTest APKs continue with the default installer.
- After verifying script installation, Jugg clears old Direct Overlay if the app sandbox is available. A system-app script must not assume `adb uninstall` deletes app data.
- If the script triggers reboot, it must wait for device startup and PackageManager scanning before exiting. Jugg only reuses the existing short ADB-offline recovery window.
- Fail on nonzero exit, user cancellation, ADB not recovering, absent package, or installed APK checksum mismatch. A script failure itself is ineligible for deploy retry or Gradle fallback. Other deployment failures after script success retain their retry/fallback policies and may rerun the script; business owners handle repeat execution.

### 4.2 Custom APK Signing Script Contract

If platform certificate and Gradle `SigningConfig` differ, default-keystore resigning after Jugg rewrites Manifest/native libraries yields `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Run Configuration can enable `Enable custom APK sign script`, substituting a project script for this signing step; sending the APK to a signing server is a typical use.

- With the UI switch disabled, show only the switch and retain default signing. Enabling it shows a single-line-height input panel with a project-script example placeholder.
- Run the script at the local project root with Bash on macOS/Linux or `cmd.exe` on Windows. Remote compilation outputs are rewritten on the local IDE host, so the script also runs there. Bash does not load user shell startup files.
- Pass the zipaligned temporary APK's **absolute path** as the last positional argument, safely escaped for the host shell. A path containing spaces, parentheses, or Unicode remains one argument. Jugg injects no device, applicationId, or other variables.
- The script must overwrite that temporary APK in place. If a signing service returns another file, the script must replace the supplied path before exiting.
- Exit code `0` means only that the script finished; Jugg still runs `apksigner verify`, replacing the original APK atomically only after verification. On verification failure or nonzero script exit, retain the original APK and do not install this update.
- A valid local `SigningConfig` is not required. Never fall back to local-keystore signing after script failure, which could produce an APK with the wrong signing identity.
- Forward script output to the Run window; cancelling Run terminates the process. Do not automatically retry known failures. Upload, wait, download, and their retry policies belong to the business script.
- Apply only when Jugg rewrites an APK: Manifest, native library, embedded Dex, and the Embedded APK path. Full Gradle-build signing is unchanged; CLI `BuildIncrementalApkCommand` and manual incremental APK export still use default signing.
- Deployment retry and recovery/reinstall install an already signed APK and do not rerun the signing script. A multi-device Run may invoke it repeatedly on the same APK; business scripts own the semantics of repetition.
- Script content is sensitive configuration. Logs and `toSafeString()` print only `(configured)` / `(not_configured)`, never the script text.

### 4.3 Incremental Path for run-as-Incompatible Apps

```text
probe run-as package with a rollback-safe write
  -> unique success marker, UID in 10000..19999, and matching probe/code_cache SELinux contexts: retain Android Studio Apply Changes
  -> absent marker, UID out of range, or SELinux context mismatch: enter Direct app sandbox transport
      -> resolve actual PackageManager dataDir
      -> probe ordinary shell, one adb root + reconnect, then noninteractive su (including direct-command form)
      -> fix and reuse this run's AppSandboxExecutor
      -> install existing Jugg startup agent and copy instrumentation JAR as an app-readable file
      -> write code_cache/.overlay
      -> new class: build in-memory dex elements and append to Application ClassLoader
          -> current process can load new class; committed overlay is reusable on later process starts
      -> method-body-only change: am attach-agent + RedefineClasses
          -> APPLY_CHANGES: update class only after success
          -> APPLY_CHANGES_AND_RESTART_ACTIVITY: schedule Activity.recreate() on main thread after success, retaining process
          -> failure: restart app and let startup agent load overlay
      -> Android 11+ ordinary resource/asset or mixed method-body/resource change: refresh host Resources, then recreate Activity
          -> failure: restart app and let startup agent load committed overlay
      -> structural change, APK-root resource, or Android 8–10 ordinary resource: restart and load overlay
      -> compat deployment continues to load resource APK
```

Capability detection does not depend on system/privileged flags, `sharedUserId`, or a particular `run-as` error string. ADB transport/offline exceptions propagate directly. If Direct privilege is unavailable or deployment cache is missing, fail early rather than entering an Android Studio Deployer that must fail. The Direct JVMTI request performs Activity relaunch independently, without reentering Android Studio `fullSwap/overlaySwap` or using process-killing `am start -S`. This path takes over incremental deployment only after the app has been installed; initial system placement still requires an external flow or custom APK install script. See `03_deploy_core.md` §6.4 for the remaining differences between Direct and official Apply Changes.

### 4.4 Rootless Compat Deployment (Ordinary Shell, Root adbd, and `su` All Unavailable)

A debuggable system app on a production `user` ROM (`ro.debuggable=0`) can fail the `run-as` probe while `adb root` says `adbd cannot run as root in production builds` and no noninteractive `su` is available. The Host then cannot write the app data directory; `AppSandboxExecutor.mode` is fixed to `UNAVAILABLE`.

```text
JuggDeployer.optimisticSwap
  -> DirectAppSandboxDeployTransport.tryDeploy
      -> sandbox.mode == UNAVAILABLE
          -> non-compat payload: throw REDEPLOY_WITH_COMPAT_MESSAGE
              -> DeployRetryHandler reuses existing compat retry
              -> DeployFileManager.appendCompatDeployFiles(original data)
          -> compat payload: RootlessCompatDeployStaging
              -> reuse DirectOverlayWriteRequestBuilder for overlay ID and file set
              -> adb push /sdcard/Android/data/<package>/files/jugg/rootless-compat/<requestId>/
                   payload.zip -> request.properties -> ready (written last)
              -> chmod 755 directories / 644 files, never 777
  -> return pendingRequest; do not write deployment cache this run
  -> JuggDeployerHelper restarts app once
  -> RootlessCompatImportConfirmer waits by requestId for app import result (default maximum 30s)
      -> success: storeEntry + subsequent deployHistory/DeployFileManager.commit
      -> failure or timeout: throw, commit no state, best-effort clear staging directory
```

On the app side, `BootstrapApplication.attachBaseContext()` calls `RootlessCompatDeployImporter.importPending()` after `HotfixLoader.init()` and before `isNeedEnableHotfix()`, so import and load occur during the same process start. Import follows Direct Overlay rules: validate protocol version, package name, requestId, payload SHA-256, and expected overlay ID; extract into private `code_cache/rootless_import/<requestId>/` staging; then commit using Direct Overlay's cleanup, full-resource push, and overlay-ID rules, writing `id` last. Any validation, extraction, or space failure preserves the old overlay and emits one `jugg-agent` log line: `__JUGG_ROOTLESS_IMPORT__ FAILED <requestId> <stage> <reason>`.

Import is idempotent per request. After metadata and payload-digest verification, if `code_cache/.overlay/id` already equals this request's `nextOverlayId` (for example, the prior start crashed after import but before Host read the result), emit `__JUGG_ROOTLESS_IMPORT__ OK <requestId>` again and return without recommitting. If overlay ID is not `nextOverlayId`, fail on state mismatch; an arbitrary committed overlay cannot count as this request's success.

Constraints:

- This path accepts compat payloads only and reports `COMPAT_HOT_FIX`.
- Do not call `prepareStartupAgent()`, `pushAgentToApp()`, `DirectHotReloadWriter`, or `am attach-agent`; do not introduce a second restart to prepare an agent.
- This path requires a Jugg compat runtime already injected into the APK. Without it, waiting for import times out and fails explicitly; the initial version does not automatically rebuild the install state machine.
- Staging is fixed at `/sdcard/Android/data/<package>/files/jugg/rootless-compat`; the app finds it through `Context.getExternalFilesDir(null)`. This avoids request files under `/data/local/tmp`, which SELinux domains such as `system_app` cannot read.
- Manifest and native libraries remain on the full APK-update path.

## 5. Hidden Constraints

- A Play Store image is unsuitable for system-app experiments: typically `adb root` is unavailable and `/system` is read-only. Use a Google APIs or AOSP `userdebug` image.
- Without `-writable-system`, `adb remount` fails on an API 35 Google APIs emulator and may say `Device must be bootloader unlocked`. That proves only that this boot lacks a writable system overlay, not that the image choice itself is wrong.
- After `disable-verity`, overlayfs remount may say `Now reboot your device for settings to take effect`. If `/system` remains read-only before pushing, reboot, then run `adb root && adb remount`; do not push the APK onto a temporary mount that disappears on reboot.
- Android Studio's bundled Google APIs image does **not** use public AOSP test keys for its platform certificate. On API 35 Google APIs (`android-35/google_apis/arm64-v8a`), `framework-res.apk` SHA-256 is `301aa3cb081134501c45f1422abc66c24224fd5ded5fdc8f17e697176fd866aa`; AOSP `platform.x509.pem` is `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`. Both DNs say Android/android.com, so a DN does not prove private keys match.
- With a certificate mismatch, scanning rejects `android:sharedUserId="android.uid.system"`: the package is absent, not merely installed under a UID other than 1000. On Google APIs images, remove sharedUserId first and verify `FLAG_SYSTEM` / `PRIVILEGED` from the path. UID 1000 requires that image's true platform private key, or an AOSP `default` / self-built userdebug image.
- The AOSP platform test key uses MD5withRSA. Java 17 `jarsigner` / Gradle signing may refuse it; use Android SDK `apksigner --key platform.pk8 --cert platform.x509.pem`.
- AGP debug APKs default to `android:testOnly="true"`; after a system scan they may not open from the launcher. Use a non-testOnly release APK for initial system-partition placement (it may remain `debuggable`).
- Hidden APIs / `framework.jar` affect whether compilation can reference `@hide` interfaces. They do not replace installation in a system directory and are not sufficient for `FLAG_PRIVILEGED`.
- Apply Changes compatibility depends only on the unique success marker from a rollback-safe `run-as` write probe, raw UID `10000..19999`, and a probe SELinux context matching existing `code_cache`. UID alone does not prove the app process can read files created by `run-as`. If incompatible, Direct transport must actually verify data-directory write, owner repair, SELinux-label restoration, and cleanup. Ordinary files inherit existing `code_cache`'s dynamic MCS context; JVMTI `.so` uses executable `apk_data_file:s0` for appdomain. Do not substitute root output, app flags, or error strings for capability probes.
- Rootless compat deployment applies only to compat payloads with `AppSandboxExecutor.mode == UNAVAILABLE`. An ordinary payload, including an empty recovery dry payload, throws `REDEPLOY_WITH_COMPAT_MESSAGE` without sandbox access. `DeployStateRecover.tryDryDeploy` ignores that signal and returns success, allowing the real incremental payload to take one compat redeploy instead of looping in recovery that must fail.
- Rootless commits only on app import confirmation. `JuggDeployer.optimisticSwap` does not write deployment cache. `JuggDeployerHelper` calls `storeEntry` and clears the external-files request directory only after matching requestId `OK`. Timeout is not success; starting a process or successful `am start` cannot substitute for an import result.
- Once a system package exists, Android Studio Run, `adb install`, and Jugg install all update it rather than installing it for the first time. The signature must match the APK under `/system`. `adb uninstall` removes only the `/data` update, leaving the system baseline; a later debug-signed install still conflicts.
- On a Google APIs image, `/system/app` / `/system/priv-app` placement can produce `FLAG_SYSTEM` / `PRIVILEGED`, but a platform-certificate mismatch still denies `signature|privileged` permission. On an AOSP `default` / `test-keys` image, a platform-signed `/system/app` can receive `INSTALL_PACKAGES` without priv-app; with matching certificate, `sharedUserId="android.uid.system"` gives process UID 1000.

## 6. Investigation Entry Points

| Observation | Proves | Does not prove | Next distinguishing evidence |
|---|---|---|---|
| Successful `adb install` / default Jugg install | Package entered `/data/app`. | It is a system app. | Does `codePath` from `dumpsys package` begin with `/system/`? |
| Successful custom APK install script | Script exited 0; package exists and its APK checksum matches input. | It is a system or privileged app. | `codePath`, `flags`, `privateFlags`, and permission status from `dumpsys package`. |
| `FLAG_SYSTEM=true` but privileged permission denied | APK is on system partition. | It is privileged. | Does `privateFlags` contain `PRIVILEGED`, and is path `/system/priv-app`? |
| `priv-app` still lacks `INSTALL_PACKAGES` | PackageManager scanned the priv-app APK. | Allowlist took effect. | Is there an XML for this package in `/system/etc/permissions/`? Check logcat `privapp-permissions`. |
| Package disappears or scanning fails after `sharedUserId` | Signature differs from `android.uid.system`, or parsing failed. | Jugg compilation failed. | Compare cert SHA-256 of `framework-res.apk` and the APK; can it scan without sharedUserId? |
| `adb remount` fails / `/system` read-only | This boot has no writable system. | Play image or root approach is wholly unavailable. | Was it cold-started with `-writable-system`? Check `getprop ro.debuggable` and `pm path com.android.vending`. |
| Direct Overlay / app sandbox fails | Overlay transport cannot write this package's data directory. | System placement failed. | `dumpsys package` flags, `debuggable`, `run-as <pkg> id`, `adb shell id -u`. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` / cannot update | A same-name package exists on device with a different signature from this APK. | Compilation failed or `/system` is unwritable. | Compare cert SHA-256 of the APK at `pm path` and local output; check that Gradle `signingConfig` is not debug keystore. |
| Update succeeds but `codePath` becomes `/data/app` | This is a system-app data update (should have `UPDATED_SYSTEM_APP`). | System status was lost. | Does `flags` still contain `SYSTEM`; for privileged app, does `privateFlags` still contain `PRIVILEGED`? |

Counterevidence before concluding:

- If the leading conclusion is “Jugg cannot install system apps,” check whether `codePath` is already under `/system/` and this run only updated an existing system package.
- If the leading conclusion is “this is privileged,” check for missing `PRIVILEGED` in `privateFlags` or privileged permission without `granted=true`.
- Without `dumpsys package`, report only that the install command succeeded; do not claim system-app status.

## 7. Related Documents

- Install/overlay deployment: `03_deploy_core.md`
- Run through deployment completion: `03_deploy_complete.md`
- Runtime investigation entry point: `09_plugin_runtime_debug.md`
