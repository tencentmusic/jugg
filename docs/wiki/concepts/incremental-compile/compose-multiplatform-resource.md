---
title: Compose Multiplatform resources
description: Explains accessor generation, incremental deployment, runtime paths, and restart boundaries for Compose Multiplatform resources.
status: active
tags:
  - concept
  - compile
  - compose
  - resource
---

# Compose Multiplatform resources

Compose Multiplatform resources include both runtime files and compile-time type-safe accessors. For a resource change to take effect, the app must read the updated resource and Kotlin source code must be able to reference the latest generated declarations.

## Resource files and accessors must update together

Copying only resource files does not support a new resource because the accessor referenced by source code may not exist yet. Generating only the accessor is also insufficient because the runtime may still read the old resource or fail to find the new file.

Jugg therefore treats Compose Multiplatform resources as an independent compilation path:

```text
resource added or modified
  → generate type-safe accessors
  → compile the generated Kotlin source
  → prepare and deploy changed runtime resources
```

This path does not pass through Android `aapt2`, and Jugg does not silently treat an unrecognized Compose resource as Android `res/`.

## Reusing Gradle metadata and the project's official generator

Resource directories, generation tasks, package names, and generator APIs can vary with Compose plugin versions and project configuration. Jugg obtains the corresponding task metadata from Gradle synchronization and invokes the resource generation capability provided by the Compose plugin used by the current project.

This keeps accessor structure, resource naming, and runtime paths aligned with a full Gradle compilation. If the current task shape or generator API cannot be recognized, Jugg fails explicitly and asks for Gradle compilation rather than producing a potentially incompatible substitute.

## Accessor generation requires complete resource context

Accessor content is not determined only by files changed in the current Run. Adding a qualifier with the same name, changing a default value, or changing the resource set can alter the final generated code.

The generation stage therefore reads every known Compose resource directory from the Gradle model so that accessors see the complete resource set. The deployment stage still processes only resources added or modified in the current Run, avoiding repeated delivery of the entire resource set.

## Modern and legacy resources use different runtime paths

Different Compose plugin versions generate different resource access paths:

- The modern resource path organizes runtime files under assets resource paths.
- The legacy resource path preserves its classpath resource paths at the APK root.

Jugg selects the corresponding path from Gradle task metadata and does not mix them. The modern path supports accessors such as `string`, `string-array`, `plurals`, `drawable`, and `font`; the legacy path has a smaller support scope. Resources under `files/` can be deployed but do not generate type-safe accessors.

## Why the app restarts after a resource update

Compose resource reads and caches can remain in the process for a long time. Replacing files on the device alone does not guarantee that the current process reloads them. Whenever the current Run produces an effective Compose Multiplatform resource deployment, Jugg restarts the app so that runtime resource state is rebuilt.

## IDE accessor synchronization is an independent auxiliary result

Jugg uses Best-effort synchronization to expose generated accessors to the IDE for code browsing, completion, and navigation. This step does not participate in deciding whether the device compilation result is valid. Even if IDE synchronization fails, successfully generated, compiled, and deployed results remain effective.

If runtime behavior is correct but the editor temporarily cannot resolve a new accessor, synchronize Gradle again or run a full Gradle compilation to refresh the IDE model.

## Boundaries

- Adding or modifying a supported resource can use the Jugg incremental path. Deleting a resource requires Gradle to recalculate accessors and the resource set.
- A custom resource directory must already be recognized by the Compose Gradle task and present in the synchronized project model.
- After upgrading the Compose plugin, adjusting resource tasks, or switching the generator API, run Gradle synchronization and a full Gradle compilation first.
- If the generator is incompatible, the resource task cannot be recognized, or accessor compilation fails, Jugg preserves the actual error and falls back to Gradle compilation.

## Related pages

- [KMP and Compose Multiplatform](/capabilities/compile/kmp-compose-multiplatform)
- [Resource incremental compilation](/concepts/incremental-compile/resource)
- [Source incremental compilation](/concepts/incremental-compile/source)
- [Project information refresh and recovery](/concepts/project-info-refresh)
- [Restart](/capabilities/deploy/restart)
