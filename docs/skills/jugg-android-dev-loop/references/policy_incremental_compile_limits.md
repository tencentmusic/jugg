# Policy: Incremental Compile Limitations

Use this policy when deciding whether to stay on incremental compile or switch to Gradle fallback.

## Supported Processors / Plugins

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

## Decision Rule

Switch to `gradle-build` directly when any condition is true:

1. Change adds or modifies unsupported annotations.
2. Changed files depend on Transform/instrumented bytecode behavior.

Then continue the normal loop: `deploy` → runtime verify.

## Symptom Linkage

For signature-based matching and concrete fixes, load `references/error_patterns.md`.
