# Policy: Incremental Compile Limitations

Use this policy when deciding whether to stay on incremental compile or switch to Gradle fallback.

## Supported Annotation Processors / Plugins

Jugg incremental compile supports only:

- `DataBinding`
- `ViewBinding`
- `Compose`
- `Parcelize`
- `Page` for Kuikly
- `JsonClass` for Moshi

## Unsupported Annotation Behavior

If unsupported processors are involved (for example Dagger/Hilt, Room, Glide):

- Regular source-only changes can still take effect.
- Adding new annotations or changing annotation values will not re-run unsupported processors.
- Generated code becomes stale and changes may silently not take effect.

## Transform / Instrumentation Behavior

Jugg incremental chain is `source -> class -> dex` without Gradle Transform.

- Recompiled files lose previous Transform instrumentation.
- ASM hooks, AOP aspects, or injected routing/init logic can disappear from changed classes.

## State Loss / Recovery

Jugg incremental compile and deploy require both Gradle outputs and app-side deploy state to stay intact.

- Manually deleting part of `build/` can remove dependency artifacts and break incremental compile. Run `gradle-build` once to restore the baseline.
- Clearing app data or reinstalling the APK can erase incremental deploy state. Use `clean-reinstall` instead of manual clear-data/reinstall.

## Deploy Recovery Failures

For `App not launched, please check the app is started and debuggable, and adb is not occupied by other process` or `Recovery failed for app not launched.`:

- Suspect multiple Android Studio instances or competing adb/debugger sessions.
- Check whether `Attach Debugger to Android Process` works first.
- If attach fails, fix adb/debugger ownership before retrying, for example `adb kill-server` or closing other Android Studio instances.

For `Try recover deploy state failed.`:

- Suspect Gradle installed a non-debuggable app. Confirm the Jugg compile command and installed variant are debuggable.
- Also suspect loose USB cables or adb instability. Try `adb kill-server`, reconnect, then retry.

## Device Compatibility

Some devices can hit probabilistic `AssetManager` native crashes or WebView native crashes after resource deploy because of Apply Changes compatibility.

- Ask the user to enable compat deploy for that device: `Jugg Run Configuration -> More Options -> Force use compat deploy for {device_name}`.
- Compat deploy uses the hot-fix path and can avoid these native crashes.

## Decision Rule

Switch to `gradle-build` directly when any condition is true:

1. Change adds or modifies unsupported annotations.
2. Changed files depend on Transform/instrumented bytecode behavior.
3. Part of `build/` was manually deleted or some Gradle maven dependencies are missing.

Then continue the normal loop: `deploy` → runtime verify.

## Symptom Linkage

For signature-based matching and concrete fixes, load `references/error_patterns.md`.
