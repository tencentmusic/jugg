---
title: Classes and overlays in Apply Changes
description: Explains how Android Studio Apply Changes uses JVMTI to replace classes online and update file overlays, and why Jugg usually recreates the Activity after deployment.
status: active
tags:
  - concept
  - deploy
  - apply-changes
---

# Classes and overlays in Apply Changes

Android Studio Apply Changes does not reinstall a complete APK. It starts from the APK already installed on the device, organizes class changes and resource files from the current Run into an incremental update, and then decides whether to recreate the current Activity. Jugg reuses this device-side application mechanism, but supplies classes and overlays from its own incremental compilation.

This path explains two common behaviors: why changing only a method body does not require reinstalling the app, and why the current Activity can still run its lifecycle again when the Run result says Hot Reload.

## Apply Changes uses an APK baseline and local updates

A complete installation replaces DEX, resources, and Manifest from the APK together. Apply Changes preserves the installed APK and sends only content changed relative to current deployment state.

```text
installed APK and deployment cache
  -> current class changes
  -> current resource and assets overlays
  -> generate an overlay update
  -> write it to the device and update the overlay ID
  -> preserve process, recreate Activity, or restart app according to deployment type
```

The deployment cache records the APK snapshot and overlay ID after the last successful installation or Apply Changes. A new local update must be generated from that snapshot. If local records and device state do not match, Jugg performs recovery before accumulating another difference.

## Classes are divided into online modifications and new content

Apply Changes relies on JVMTI for online modification of loaded classes. After the Apply Changes Agent obtains JVMTI, it performs class redefinition for modified classes, so method-body changes can take effect without restarting the app process. Structural changes to fields, method signatures, or inheritance cannot use this online replacement path. They become Hot Fix data and are loaded after the app restarts. Jugg currently reuses this hot-reload channel directly. See [Jugg JVMTI Agent](./jugg-jvmti-agent.md) for JVMTI compatibility checks and runtime corrections.

Before deployment, Jugg compares old and new class structures and sends class changes to two Apply Changes input sets.

| Class change | Apply Changes input | Activation boundary |
|---|---|---|
| Method-body change with unchanged class structure | Modified class | JVMTI replaces the implementation of the loaded class online |
| New class | New class | Added to the overlay as new DEX content and loaded by the current or next process |
| Field, method signature, inheritance, or generic structure change | New class / Hot Fix data | Cannot rely on class redefinition in the current process; loaded after app restart |
| Class in library DEX, multi-dex, or another location that cannot be replaced online reliably | Hot Fix data | Loaded by the runtime after app restart |

A method-body change being eligible for modified class does not mean that Jugg preserves the current Activity. Whether a class can be replaced online and whether the UI must refresh are separate decisions. The first determines how bytecode is applied; the second determines when the user can observe the new code and resources.

## Overlays carry resources, assets, and DEX files

Incremental resource compilation outputs local files such as `resources.arsc`, `res/**`, and `assets/**`. New classes and DEX that must be loaded after restart also enter the device overlay. Apply Changes organizes these files by target APK so that content for base APKs, split APKs, and test APKs is written to the corresponding overlay location.

When a deployment baseline receives a resource overlay for the first time, Jugg supplies the complete resource set. The device has no reusable resource overlay yet, so one changed file alone cannot form the complete new resource view. Later deployments can accumulate the current difference only after a trusted resource state exists.

Manifest and native libraries do not use an ordinary overlay. When they must become install package content, they enter [APK update and installation](./apk-update-and-install.md).

## Code Swap and Full Swap differ in lifecycle behavior

Android Studio incremental deployment can apply only the changes or recreate the Activity after applying them.

| Apply Changes action | Class and overlay handling | Lifecycle result |
|---|---|---|
| Apply Changes | Writes the local update and performs class redefinition when needed | Does not actively recreate the Activity |
| Apply Changes and Restart Activity | Writes the same kind of local update and recreates the Activity afterward | Preserves the app process while the current Activity runs its lifecycle again |

For ordinary non-empty incremental deployments that do not require an app restart, Jugg currently uses Apply Changes and Restart Activity. This reloads resources, layouts, and new code read during the Activity lifecycle on the current screen. Jugg's `HOT_RELOAD` therefore means the current Run still used online incremental deployment; it does not mean the Activity necessarily remained unchanged.

Special calls for warm-up, state probing, or cases that do not need a UI refresh can use Apply Changes without recreating the Activity. Artifacts that require Hot Fix, compatibility deployment, or process-level cache refresh restart the entire app instead of being contained by Activity recreation.

## Content that requires an app restart

The following changes cannot rely only on Activity recreation:

- Class structural changes or other DEX files that require Hot Fix loading.
- Overlays read only after restart during compatibility deployment.
- Classpath resources at the APK root.
- Content with process-level caches, such as Compose resources.
- The user enables “always restart after deployment” or starts from Debug.

These scenarios can still use current incremental compilation artifacts, but the new process must recreate its ClassLoader, Resources, or runtime caches. See [In-app Jugg Runtime](./jugg-runtime.md) and [compatibility deployment](./compat-deploy.md) for how they are loaded.

## Related pages

- [Incremental deployment overview](./deploy-strategy.md)
- [Deployment data and impact analysis](./deploy-data-and-impact.md)
- [APK update and installation](./apk-update-and-install.md)
- [Direct Overlay deployment](./direct-overlay.md)
- [Deployment state and recovery](./deploy-state-recover.md)
- [Jugg JVMTI Agent](./jugg-jvmti-agent.md)
- [Code Swap capability](../capabilities/deploy/code-swap.md)
- [Full Swap capability](../capabilities/deploy/full-swap.md)
