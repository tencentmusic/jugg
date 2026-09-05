---
title: In-app Jugg Runtime
description: Explains how the runtime injected into the target app process connects build artifacts, corrects runtime environment differences, and provides in-app services for UI tools.
status: active
tags:
  - concept
  - runtime
  - deploy
---

# In-app Jugg Runtime

Jugg compilation, deployment, and flow decisions happen mainly in Android Studio, Gradle, and ADB, but the actual state of the ClassLoader, Resources, Application lifecycle, and current View tree exists only inside the target app process. Jugg therefore places a set of runtime components into the APK to connect deployment artifacts, correct the runtime environment, and provide in-process services for UI tools while the app starts and runs.

This page collectively calls these components the **in-app Jugg Runtime**. Here, runtime specifically means the part that enters the target app process. It is not the Kotlin compiler environment or the service inside the IDE plugin that receives commands and orchestrates tasks.

## Why some work must happen inside the app process

A full Gradle build packages code, resources, and native libraries into the APK. Android then creates the ClassLoader, Resources, and Application from the install package. During a Jugg incremental Run, generating and delivering only the local artifacts from the current changes does not mean those files have entered these runtime objects.

Outside the app process, Jugg can generate and transfer files but cannot directly determine whether incremental DEX has entered the current ClassLoader, whether a resource overlay has contaminated an AssetManager for another package, or which View tree is currently displayed. These operations must run in the actual app environment.

| In-app responsibility | Why it requires the process | User-visible result |
|---|---|---|
| Correct runtime environment differences | Must read or adjust real objects such as ClassLoader, Resources, and ActivityThread | Prevents delivered code from remaining unloaded, resource failures, or runtime crashes on specific system combinations |
| Connect incremental artifacts | A restarted process must recreate loading paths for code, resources, and native libraries | Structural changes and compatibility deployment artifacts take effect after app startup |
| Provide the ViewHierarchy service | Current View and Compose nodes and their properties exist only in the app process | `layout-dump`, `view-locate`, `view-inspect`, and `tap` can read or operate on the real page |

## How Jugg Runtime enters the APK without application changes

The application project does not need to depend explicitly on a Jugg SDK or modify its Application. When Jugg starts a Gradle build that produces an APK, it attaches an init script to that invocation, adds the runtime to the target application variant, and adjusts the merged Manifest produced by the build. The project's Gradle configuration, source Manifest, and Application implementation remain unchanged.

```text
Jugg starts a Gradle build that produces an APK
  -> register runtime injection for this Gradle invocation
  -> add Jugg Runtime to the target application variant
  -> preserve the original Application and AppComponentFactory from the merged Manifest
  -> replace the startup entry in the current APK with Jugg Runtime
  -> Gradle packages the runtime into the APK
  -> restore the original entry and lifecycle after the app starts
```

While processing the merged Manifest, Jugg records the original Application and AppComponentFactory. After the app starts, the runtime initializes the environment, creates the original objects, and continues the original lifecycle. Release minified builds also preserve these startup entries so they are not removed during packaging.

> [!NOTE]
> “Without application changes” means the application project does not modify source code, its source Manifest, or Gradle configuration. Jugg intentionally adjusts build dependencies and the merged Manifest artifact for the target variant; otherwise, the runtime could not enter the final APK.

## How the runtime environment is established during app startup

Jugg Runtime has two startup points that must be distinguished. The system loads the startup agent when the process starts so it can confirm JVMTI capability and install required Framework hooks. The runtime startup entry works before the original Application to prepare incremental loading paths and restore the application's original startup objects.

```text
app process starts
  -> system loads the startup agent when required
  -> detect JVMTI and install runtime corrections
  -> Jugg Runtime initializes code, resource, and native library loading paths
  -> create and restore the original Application / AppComponentFactory
  -> run the original Application lifecycle
  -> initialize the in-app ViewHierarchy service
```

Restoration does more than call `Application.onCreate()` again. Jugg also replaces Framework references to the temporary startup object with the original Application and routes ActivityLifecycleCallbacks registration to the original Application so that application code continues to interact with its own Application instance.

## Correcting runtime environment differences and crashes

Android Studio, Android versions, and vendor systems do not handle Apply Changes identically. Because Jugg Runtime runs inside the actual app process, it can correct only problems detected from real object state instead of switching every device to one deployment strategy.

### Incremental DEX did not enter the ClassLoader

Some customized systems initialize the ClassLoader early. Even after Apply Changes writes DEX to the app cache directory, the current ClassLoader search path may still omit those files. Jugg Runtime compares incremental DEX with current dex elements and adds the DEX load path only when files are missing. The result is retained in the app cache to avoid repeating the scan on every startup.

### An overlay entered the wrong resource environment

An Apply Changes overlay belongs to the host app resources, but an independent package such as a WebView provider can create its own AssetManager in the host process. If the host overlay enters that resource environment, provider initialization can fail because of resource package ID conflicts.

Jugg Runtime identifies the APK associated with the current Resources. Host resources retain the overlay, while non-host resource environments remove it so that one local resource update does not expand into an initialization failure in another component.

### Android version and Apply Changes behavior do not match

With Android 15 and an older Android Studio, Apply Changes may update resources without triggering a complete resource refresh and Activity recreation. Jugg Runtime supplies an ApplicationInfo update for that combination and recreates the Activity according to current deployment requirements so that the page reads the new resource state.

The runtime also provides overlay-first lookup for classpath resources at the APK root. If no incremental file matches or reading fails, it continues with the original ClassLoader lookup so that auxiliary compatibility logic does not interrupt the app's existing resource loading.

## How incremental artifacts take effect in the app

An ordinary method-body change still prefers online replacement through Apply Changes and JVMTI. Structurally changed classes, compatibility deployment artifacts, and resources with process-level caches require an app restart. During startup of the new process, Jugg Runtime connects the corresponding DEX, resource, or native library paths to the runtime environment.

This is only the in-app part of the mechanism. See [compatibility deployment](./compat-deploy.md) for why artifacts enter that path, how deployment data changes, and when the app restarts. See [classes and overlays in Apply Changes](./apply-changes.md) for the boundary between online replacement and Activity recreation.

## Providing in-app services for UI tools

After the original Application starts, Jugg Runtime initializes the ViewHierarchy LocalSocket service. The IDE/CLI side connects to it through ADB forwarding to read the live View tree, query View properties, or perform touch actions on the app main thread.

```text
layout-dump / view-locate / view-inspect / tap
  -> check whether the target app is online and observable
  -> connect to the in-app ViewHierarchy service
  -> read the current View tree or perform a touch action
  -> return page structure, bounds, property values, or action results
```

This channel reads in-app state directly rather than inferring it from screenshots, and it does not automatically fall back to uiautomator when the socket is unavailable. See [Layout dump and UI evidence](./layout-dump-and-ui-evidence.md) for node scope, Compose support, and tool boundaries.

## Failure containment and boundaries

- In-app hot-fix loading currently requires Android 8.0 / API 26 or later. Lower versions do not initialize the corresponding loading logic.
- When the runtime itself changes, update the APK through a full Gradle build and installation. An ordinary incremental deployment can reuse only the runtime already packaged in the current APK.
- When JVMTI is unavailable, deployment records device state and switches to compatibility deployment instead of repeatedly attempting online replacement that the current device cannot complete.
- If ViewHierarchy initialization fails, Jugg records the cause and omits the in-app UI service without preventing the original Application from starting. The app must still be online and observable in the foreground before UI tools are used.
- If build scripts, dependencies, annotation processor results, or the APK baseline are untrusted, Jugg must return to Gradle. The in-app runtime cannot repair artifacts missing from the build stage.

## Related pages

- [Deployment strategy](./deploy-strategy.md)
- [Classes and overlays in Apply Changes](./apply-changes.md)
- [Compatibility deployment](./compat-deploy.md)
- [Jugg JVMTI Agent](./jugg-jvmti-agent.md)
- [Layout dump and UI evidence](./layout-dump-and-ui-evidence.md)
- [UI inspection](../guide/ui-inspection.md)
- [JVMTI Runtime](../capabilities/deploy/jvmti-runtime.md)
