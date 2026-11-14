# Policy: Incremental Compile Limitations

Before the first build command, use the current changes and known build configuration to decide whether incremental compile can preserve the intended behavior. A successful incremental compile alone cannot prove that generated code or instrumentation is current.

## Supported Annotation Processors / Plugins

Supported annotation processing / compiler plugin paths are:

| Processor / plugin | Incremental support boundary |
|--------------------|------------------------------|
| `DataBinding` | Dedicated binding source / mapper generation |
| `ViewBinding` | Binding class generation for layout changes |
| `Compose` | Project Compose compiler plugin |
| `Parcelize` | Project Parcelize compiler plugin |
| Kuikly `@Page` | Append new page registrations to an existing Gradle-generated entry; deleting, renaming, or changing existing routes requires `gradle-build` |
| ARouter | Project APT/KAPT or KSP1/KSP2; current annotated semantic changes trigger aggregate regeneration; final annotation removal/indirect unannotated changes need Gradle |
| ButterKnife | Official project APT/KAPT for current binding inputs and same-module descendants; removed binding outputs or cross-module affected bindings need Gradle |
| Moshi `@JsonClass` | KSP1 with the project's KSP dependencies; KSP2 does not re-run processors and requires `gradle-build` for generated-code changes |

This list is not general APT/KAPT/KSP support. Hilt entry-point transformation below does not make Hilt/Dagger a supported annotation processor.

## Unsupported Annotation Behavior

If unsupported processors are involved (for example Dagger/Hilt, Room, Glide):

- Regular source-only changes can still take effect.
- Adding, removing, or changing unsupported annotations will not reliably regenerate their outputs. Changes to processor inputs, such as Room entity fields, DAO methods, injection constructors, or bindings, also require regenerated code even when annotations stay unchanged.
- Generated code becomes stale and changes may silently not take effect.
- Existing Hilt `@AndroidEntryPoint` / `@HiltAndroidApp` classes are a narrow exception for entry-point bytecode transformation: ordinary method-body changes keep injection when the matching Gradle-generated `Hilt_*` parent is available. Jugg still does not re-run Hilt/Dagger processors.

## Transform / Instrumentation Behavior

Jugg incremental chain is `source -> class -> dex` without the general Gradle Transform pipeline.

- Hilt Android entry points receive the supported pre-D8 parent, `super`, and BroadcastReceiver injection rewrite when existing generated artifacts are available.
- Recompiled classes that depend on other Gradle Transform / ASM instrumentation do not automatically receive that processing again; previously injected behavior can disappear.
- ASM hooks, AOP aspects, or injected routing/init logic can disappear from changed classes.

## State Loss / Recovery

Jugg incremental compile and deploy require both Gradle outputs and app-side deploy state to stay intact.

- Manually deleting part of `build/` can remove dependency artifacts and break incremental compile. Run `gradle-build` once to restore the baseline.
- Clearing app data or reinstalling the APK can erase incremental deploy state. Use `clean-reinstall` instead of manual clear-data/reinstall.

## Deletions / Renames

Deleting or renaming source files, resources, or assets does not produce a removal overlay. A rename compiles the new path, while old installed classes or resources can remain. When correctness depends on removing old content, run `gradle-build` and deploy the resulting APK baseline; a zero-file incremental result does not verify removal.

## Compile Failure Boundary

Ordinary Java/Kotlin source errors normally fail the current Run without automatic Gradle fallback. Read the first actionable diagnostic and fix the source before retrying. Use Gradle directly for a confirmed unsupported generation, instrumentation, or missing-baseline problem; follow the normal flow's bounded recovery for unresolved failures.

## Deploy Recovery Failures

For `App not launched, please check the app is started and debuggable, and adb is not occupied by other process` or `Recovery failed for app not launched.`:

- Suspect multiple Android Studio instances or competing adb/debugger sessions.
- Check whether `Attach Debugger to Android Process` works first.
- If attach fails, fix adb/debugger ownership before retrying, for example `adb kill-server` or closing other Android Studio instances.

For `Try recover deploy state failed.`:

- Suspect Gradle installed a non-debuggable app. Confirm the Jugg compile command and installed variant are debuggable.
- Also suspect loose USB cables or adb instability. Try `adb kill-server`, reconnect, then retry.

## Cross-Process System Resources (Notification / Widget / Shortcut)

Ordinary resource-overlay deployment updates app-side resource loading without replacing the installed APKs. System UI and launcher processes loading resources by package and resource ID use their own Resources backed by the installed base/split APKs, without the app's private overlays.

- New resource IDs used by notification icons/actions, AppWidget or notification `RemoteViews`, or resource-backed launcher shortcuts require `gradle-build` and installation of the resulting APKs.
- Missing resources can cause `Resources$NotFoundException`, failed rendering, or `RemoteServiceException: Bad notification ... Couldn't create icon StatusBarIcon`.
- Existing resource IDs can still resolve in system processes, but those processes may show the old packaged content. To verify updated contents there, also rebuild and install. Bitmap/URI-backed content follows its own delivery rules.
- This boundary concerns overlay deployment; embedded-APK deployment and APK-rewrite/install paths replace installed artifacts.

## Device Compatibility

Some devices can hit probabilistic `AssetManager` native crashes or WebView native crashes after resource deploy because of Apply Changes compatibility.

- Ask the user to enable compat deploy for that device: `Jugg Run Configuration -> More Options -> Force use compat deploy for {device_name}`.
- Compat deploy uses the hot-fix path and can avoid these native crashes.

## Decision Rule

Switch to `gradle-build` directly, without first running or exhausting retries of `compile` / `deploy`, when any condition is true:

1. Change requires an unsupported processor to regenerate code, including annotation removal, model/schema changes, or Hilt injection declarations and bindings.
2. Files to recompile are confirmed to depend on unsupported Transform/instrumented behavior (ASM hooks, AOP or injected routing/init), even when annotations did not change.
3. Part of `build/` was manually deleted or some Gradle maven dependencies are missing.
4. Change introduces resource IDs consumed by cross-process system components (notification icons/actions, widget or notification RemoteViews, resource-backed shortcuts), or requires those components to display updated packaged resource contents.
5. Correctness depends on removing old classes, resources, or assets after deletion or rename.

Explain the exact changed processor input or instrumentation dependency; merely having the plugin installed is insufficient. If the user forbids Gradle, report the conflicting change and stop before building. Otherwise `gradle-build` builds and installs/deploys the new baseline. Then verify the affected runtime behavior when runtime verification is in scope; do not repeat `deploy` unless further changes require it.

## Symptom Linkage

For signature-based matching and concrete fixes, load `references/error_patterns.md`.
