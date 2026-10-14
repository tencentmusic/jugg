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

## Q: How can I preserve a crash and ask my Agent to investigate?

Before rebuilding or reinstalling, click `Ask AI` in the `Jugg Plugin` group of `Jugg Running Panel`. Choose `Runtime crash`, describe the action that caused the crash and the expected and actual results, select the devices to collect logs from, and click `Save Evidence and Generate Prompt`.

Jugg saves the current logs, logcat for each device, and existing APKs and incremental deployment artifacts. Logcat includes the crash buffer but covers only recent logs still retained by the device. If a device disconnects or collection partly fails, other usable evidence is saved and the evidence manifest records missing items for the Agent to read using the path in the prompt.

After collection, select `Copy prompt` and paste it into an Agent that can access the current Android project. The prompt directs the Agent to read the Jugg knowledge base and source code and report root-cause evidence, recommendations, and the next step if the problem persists. When an Issue is recommended, it provides a draft. The prompt explicitly permits cloning Jugg into a separate investigation directory and fetching when necessary; the investigation reads existing evidence only.

Evidence is saved in a separate local directory, survives later builds and Clear Jugg Build, and is retained for 30 days by default. Collect it after the Jugg task finishes. Local deployment records and artifacts alone do not prove that the device loaded the same content.

The prompt uses Markdown. It is generated in Chinese when the system language is Chinese, and in English otherwise. The Agent uses the same language for its analysis and Issue content. User descriptions and original logs retain their original text; dialog instructions always use English.

When the Agent recommends an Issue, it provides a prefilled bug report link and complete content organized by field. Open the link, review the title, description, and diagnostic information, sign in to GitHub, select the applicable form options, complete the submission confirmation, and submit. If the link is too long or prefilling fails, copy the full content into the corresponding fields. Local evidence files must be provided separately; the link does not attach or upload them automatically.

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

Some Android 11 OEM systems have compatibility problems with resource deployment through Apply Changes.

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
