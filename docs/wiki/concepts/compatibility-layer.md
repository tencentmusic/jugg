---
title: Android Studio version compatibility
description: Explains which Android Studio APIs Jugg depends on during project sync, device selection, deployment, and debugging, how version differences are isolated, and where compatibility fallbacks stop.
status: active
tags:
  - concept
  - compatibility
---

# Android Studio version compatibility

Jugg reuses Android Studio's project model, Run Configuration, device selection, installation, Apply Changes, and Java debugger attach capabilities. These capabilities come from Android Studio internal APIs rather than stable public interfaces for third-party plugins, so their types and invocation patterns may change when the IDE is upgraded. Jugg confines these differences to a version adaptation boundary so that project information retrieval, compilation, and deployment do not have to change with every Android Studio release.

## Why an Android Studio upgrade can break the plugin before a Run

An Android Studio upgrade may move deployment runtime packages, replace installer or debugger entry points, or remove old types entirely. If the plugin directly references these types in its main flow, the JVM resolves the references when the related class is loaded rather than waiting until the user actually deploys and then checking whether the capability is available.

```text
Android Studio removes or moves an internal type
  -> a plugin class still references the old type
  -> the JVM cannot resolve the type or method
  -> NoClassDefFoundError or NoSuchMethodError occurs during project initialization
  -> related services fail before the Run begins
```

Version compatibility must therefore first prevent a version-specific type from being loaded too early in an incompatible Android Studio version, rather than merely retrying after deployment fails.

## Jugg confines version differences to the invocation boundary

The main compilation and deployment flows use Jugg-owned interfaces and neutral data models. When an Android Studio capability is needed, the version adaptation boundary selects the corresponding implementation:

```text
Jugg compilation or deployment flow
  -> Jugg-owned interfaces and data models
  -> implementation for the current Android Studio version
  -> Android Studio deployment or debugging API
```

Android Studio installation sessions, overlay identifiers, and deployment cache entries are converted only inside a version-specific implementation and do not become data contracts for the main flow. When a new version changes an internal type, the adjustment can remain inside that version implementation.

Version implementations also do not resolve every method signature when the plugin starts. Android Studio types are touched only when the main flow actually invokes the corresponding capability. A missing old type therefore affects only the current invocation and still allows Jugg to try another version implementation.

## Android Studio APIs currently used

The following table lists the APIs used at each stage and their purposes. It includes only the key types called by the current main flow. The same capability may live in a different package or use a different method signature across Android Studio versions.

| Stage | Key APIs | Purpose |
|---|---|---|
| Plugin initialization | `ApplicationInfo` | Reads the current IDE product and version to select the preferred version implementation. |
| Gradle sync completion or project information refresh | `GradleAndroidModel`, `ProjectBuildModel`, `GradleBuildModel`, `AndroidFacet`, `ModuleManager` | Reads module directories, build variants, SDK, Java/Kotlin compilation options, Manifest locations, APK output directories, and Android Test package information. |
| Creating or synchronizing a Jugg Run Configuration | `RunManager`, `AndroidRunConfigurationType`, `AndroidRunConfiguration` | Finds an existing Android Run Configuration and derives the corresponding Jugg compilation command, build variant, and APK output path. |
| Initializing a Jugg Run Configuration | `DeployableToDevice.KEY`, `DeviceAndSnapshotComboBoxAction.DEPLOYS_TO_LOCAL_DEVICE` | Tells different Android Studio versions that the Run Configuration can use the IDE device selector. This marker is set before project services finish initializing. |
| Resolving devices before a Run | `DeployTargetContext`, `DeployTarget`, `AdbService`, ddmlib `IDevice` | Reads the currently selected running device from the IDE and obtains connected devices through ADB. Jugg does not start an emulator merely to read the selection. |
| Installing or updating an APK | `ApkParser`, `AdbInstaller`, `ApkInstaller`, `InstallOptions`, `InstallMode`, `DeploymentPlan` | Parses the APK, creates an installation session, and performs a full or incremental installation during Install or APK update. |
| Apply Changes | `ApplicationDumper`, `DexComparator`, `ClassRedefiner`, `OptimisticApkSwapper`, `OverlayId` | Validates the APK on the device after incremental compilation, organizes class and resource overlays, and performs Code Swap or Full Swap. |
| Run state checks and recovery | `DeploymentApplicationService`, ddmlib `Client` and `IDevice` | Determines device authorization, system version, and whether the target process is debuggable for deployment recovery and Debug attach. |
| After a Debug Run deploys successfully | `AndroidConnectDebugger`, `AndroidJavaDebugger`, ddmlib `Client` | Waits for the target process to enter debugger-waiting state and then lets Android Studio establish the Java Debug session. |

These dependencies are used at different times. Project model APIs are used during synchronization and project information refresh. Deployer APIs are used only after the current Run enters installation or Apply Changes. Debugger APIs are used only after compilation and deployment for a Debug Run both succeed. A version difference in one API category does not cause every implementation for other stages to load early.

## Version selection is limited Best-effort behavior

Jugg first chooses the implementation that exactly matches the current Android Studio version. If there is no exact match:

- If the current IDE is newer than the highest known version, Jugg tries the highest-version implementation first.
- If the current IDE is older than the lowest known version, Jugg falls back to the lowest-version implementation.
- If an invocation encounters an API-shape error such as a missing class, method, or field, Jugg tries other known version implementations.

This selection reduces the scope of failures caused by version differences; it does not mean that an unverified version is necessarily fully compatible. See the [compatibility reference](../reference/compatibility.md) for currently supported Android Studio versions and their verification scope.

The fallback handles only linking or shape differences in Android Studio APIs. Business errors such as installation failures, offline devices, or invalid deployment artifacts are returned to the existing flow and handled by deployment recovery or Gradle fallback. Switching version implementations cannot change those failure conditions and must not hide the real cause.

## Boundaries

This mechanism handles only version differences in Android Studio internal deployment and debugging APIs:

- Whether an Android Studio version has been verified is defined by the version table in the compatibility reference.
- Device system restrictions, unavailable JVMTI, and vendor-specific system behavior are handled by [compatibility deployment](./compat-deploy.md).
- Support for Gradle, AGP, and Kotlin plugin versions belongs to build tool compatibility.
- Java debugger attach also depends on the current Android Studio version. See [Debug](../guide/debug.md) for usage and failure entry points.

## Related pages

- [Compatibility reference](../reference/compatibility.md)
- [Classes and overlays in Apply Changes](./apply-changes.md)
- [Compatibility deployment](./compat-deploy.md)
- [Debug](../guide/debug.md)
