---
title: App crashed after deployment
description: Resolve Java exceptions, native crashes, and incremental result differences after Jugg compilation and deployment succeed.
status: active
tags:
  - troubleshooting
  - crash
  - runtime
---

# App crashed after deployment

This page covers only cases where compilation and deployment complete, then the app crashes. If the app cannot be installed or launched, or deployment has already failed, see [Installation, launch, or Debug failed](./app-cannot-run.md).

## Q: What should I do after a `NoSuchMethodError`, `AbstractMethodError`, or similar exception?

These exceptions usually mean that the current runtime class structure does not match the caller. Similar exceptions include:

- `NoSuchFieldError`
- `IllegalAccessError`
- `IncompatibleClassChangeError`
- `NoClassDefFoundError`

Recovery steps:

1. Run a full Gradle build and install the result.
2. If the Gradle result still crashes, investigate the project's dependencies, obfuscation, or code.
3. If the Gradle result works and only the Jugg incremental result crashes, keep the current changes and [report the issue](../guide/report-issue.md).

Simply restarting the app usually cannot repair an inconsistency in the class structure or DEX references.

## Q: What should I do after an `AssetManager` native crash following resource deployment?

Some device systems have compatibility problems with resource deployment through Apply Changes, especially Oppo and Vivo devices running Android 11.

1. Connect the affected device.
2. Enable compatibility mode for that device in Jugg More Options.
3. Run the current changes again.
4. If the app still crashes, run Clean And Reinstall and verify again.

Compatibility mode uses classic hot-fix deployment and persists the setting for that device.

## Q: What should I do when the app crashes or changes do not take effect after modifying DataBinding/ViewBinding XML?

DataBinding/ViewBinding currently supports incremental generation of binding-related source code. If Gradle works correctly but the problem remains reproducible with Jugg, [report the issue](../guide/report-issue.md).

## Q: How can I tell whether the crash is related to Jugg incremental compilation?

The most direct check is to run a full Gradle build for the same variant and install the result:

- The Gradle result also crashes: Fix the problem in the project first.
- The Gradle result works: Continue using the Gradle result and [report the issue](../guide/report-issue.md).

## Related pages

- [Compatibility deployment for a device](../guide/compat-device.md)
- [Clean Reinstall](../guide/clean-data.md)
- [Release compilation](../capabilities/compile/release-compile.md)
- [JVMTI Runtime](../capabilities/deploy/jvmti-runtime.md)
