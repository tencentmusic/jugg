---
title: Resource incremental compilation
description: Explains why changing one Android resource still requires complete link context and how Jugg uses inclink with the APK resource table to generate a resource overlay.
status: active
tags:
  - concept
  - compile
  - resource
---

# Resource incremental compilation

When a developer changes only one layout, drawable, or values XML, aapt2 `compile` can process just that file, but standard `link` still reads the complete resource set, assigns resource IDs, and rebuilds the resource table. Jugg restores link context from the latest trusted APK resource table and injects current changes through a customized aapt2 `inclink`, avoiding a complete relink of historical inputs after every resource change.

## Why one changed XML still requires complete link state

aapt2 processes Android `res/` resources in two stages:

```text
aapt2 compile
  -> compile an individual XML, image, or other resource into a flat intermediate artifact

aapt2 link
  -> read all flat files, Manifest, and symbol information
  -> merge resources and assign resource IDs
  -> output resources.arsc, compiled resources, Manifest, and R.java
```

The `compile` input can be local, but `link` decisions depend on the global resource table. A new resource needs a nonconflicting ID, an override must preserve the existing ID, and Manifest, custom attributes, and cross-module references must align with the same resource table. Standard link cost is therefore driven mainly by the complete resource set rather than the few files changed in the current Run.

## How inclink reuses the resource baseline from the APK

Jugg customizes aapt2 with an `inclink` command that separates complete link into one baseline load and multiple incremental injections:

```text
load baseline
  -> read resources.arsc, compiled resources, and Manifest from the APK
  -> restore resource IDs and link context
  -> retain the context in the aapt2 daemon

each incremental Run
  -> compile only current changed resources into flat files
  -> add or override resources in the existing context
  -> output new resources.arsc, resource overlay, and optional R.java
```

This path does not need to preserve and reread every historical flat file. `resources.arsc` in the APK already contains resource IDs determined by the Gradle build. `inclink` uses it as the baseline and processes only current changes and required state updates. If the current Run does not produce a new `R.java`, the source stage does not need to compile R classes solely because resources changed.

## Consecutive incremental Runs must continue from the latest resource table

The resource baseline cannot always come from the original Gradle APK. If a previous Jugg Run deployed a new `resources.arsc` and the next Run still loads the original APK, resources and IDs added by the previous Run would disappear.

Jugg selects the baseline according to current APK state:

| Current state | Link baseline |
|---|---|
| No resource has been deployed incrementally | Latest trusted Gradle APK |
| A new `resources.arsc` has been deployed | A temporary resource APK formed from the deployed resource table and current Manifest |
| Dynamic feature APK | The feature resource table plus the latest resource table from the base APK |

When base resources also change in the current Run, feature link includes flat files produced for the base in that Run so that both use consistent resource IDs.

## Information supplied in addition to resources.arsc

`resources.arsc` is the final resource table, but it does not preserve complete aggregated `R.styleable` declarations. Jugg reads styleable fields from `R.jar`, `R.class`, or the Java classpath for modules related to the target APK and supplies them to `inclink` while loading the baseline. Otherwise, the resource table and generated R declarations can diverge after a custom attribute is added or changed.

A release or AabResGuard project has another input: the Gradle baseline may use obfuscated resource names. Jugg reads the existing `resources-mapping.txt` and converts it into the mapping consumed by `inclink` so that current resources continue using names from the installed APK.

Styleable and resource obfuscation mappings are auxiliary inputs. If generation fails, Jugg records the cause and tries to load without that input. A later change involving a new styleable or an obfuscated resource reference can still fail during compilation or at runtime. If the core resource table cannot load, the current resource compilation ends directly and never fabricates success.

## How changed resources enter deployment and source compilation

The resource stage routes inputs by target APK and then connects Manifest, DataBinding/ViewBinding, flat compilation, and incremental link:

```text
changed resource inputs
  -> split by target APK
  -> process Manifest only when it actually changed
  -> DataBinding / ViewBinding splits layouts and generates source
  -> aapt2 compile generates current flat files
  -> inclink injects them into the latest resource baseline
  -> filter extra artifacts that should not be deployed
  -> output resources.arsc, resource overlay, optional Manifest, and R.java
```

Java/Kotlin source generated from layouts is not deployed as a resource. It is passed to the later source stage for compilation. `R.java` enters the source stage only when `inclink` actually outputs it. If Manifest did not actually change, the resource stage filters the root Manifest emitted by aapt2 to avoid an unnecessary APK update and re-signing.

## How stateful cache failure recovers

Each target APK has an independent link context because APKs can have different resource tables, package IDs, and Manifests. If the aapt2 daemon does not exist, has exited, or has not loaded the APK, Jugg reloads the corresponding resource baseline.

If baseline loading fails, current resource compilation fails directly. If incremental link fails, Jugg releases the current daemon and does not reuse potentially corrupted in-memory state. The next resource compilation creates a new daemon and reloads the baseline from a clean state after changing the failure condition.

## inclink boundaries

- **The resource table only adds or overrides entries; it does not generate deletions.** When a resource file is deleted, its device entry and resource ID remain and can still be read through resource APIs. Run a full Gradle build only when the deletion must take effect and refresh the baseline. `inclink` is therefore a development-time incremental mechanism, not a production resource linker.
- **Each APK must link independently.** Resource artifacts are generated separately for each target APK; the same overlay cannot be copied to base, feature, and other APKs.
- **A dynamic feature depends on base state.** A base resource table change must participate in feature link in the same Run to prevent resource IDs from diverging.
- **Build context changes require a refreshed baseline.** After changing variant, source set, resource generation logic, or resource obfuscation configuration, complete Gradle Sync and run a full Gradle build for the target variant to refresh the APK and resource table baseline.
- **Compose Multiplatform resources do not use aapt2 inclink.** They use independent resource generation and asset / classpath resource overlay paths. Deleting a Compose resource likewise requires a full Gradle build.

## Related pages

- [Incremental compilation overview](./index.md)
- [DataBinding / ViewBinding](./databinding-viewbinding.md)
- [Android Manifest compilation](./manifest.md)
- [Resource compilation capability](../../capabilities/compile/resource-compile.md)
- [AabResGuard](../../capabilities/compile/aab-resguard.md)
- [KMP / Compose Multiplatform](../../capabilities/compile/kmp-compose-multiplatform.md)
- [Compilation failed](../../troubleshooting/compile-failed.md)
- [Changes did not take effect](../../troubleshooting/changes-not-applied.md)
