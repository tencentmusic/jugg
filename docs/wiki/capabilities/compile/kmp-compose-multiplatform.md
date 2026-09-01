---
title: KMP and Compose Multiplatform
description: Explains Jugg's incremental support scope and fallback boundaries for KMP expect/actual sources and Compose Multiplatform resources.
status: active
tags:
  - capability
  - compile
  - kmp
  - compose
---

# KMP and Compose Multiplatform

Jugg supports incremental compilation of KMP `expect` / `actual` sources for Android targets, as well as adding or modifying Compose Multiplatform resources. Both capabilities depend on the project model provided by Gradle Sync: Jugg reuses existing compilation relationships and resource generation configuration instead of inferring project structure from directory names.

Compose UI source in a regular Android module belongs to [Kotlin Compose](/capabilities/compile/kotlin-compose) and does not require KMP or Compose Multiplatform resource support.

## Supported scope

### KMP sources

| Scenario | Support | Details |
| --- | --- | --- |
| Change `expect` / `actual` sources in common and Android source sets | Supported | Uses the Android target compilation model provided by Gradle to include the required common and platform sources in the same compilation |
| Use intermediate source sets such as `sharedMain` | Supported | Gradle's model must expose the corresponding fragments and their dependency relationships |
| Kotlin 1.9 and K2 projects | Supported | Handles cache and fragment differences according to the project's actual compilation model |
| Complementary-source information is missing or relationships are unclear | Best-effort | Preserves currently confirmed source inputs without inferring `expect` / `actual` relationships from filenames or directory names |
| A regular Android module happens to contain a `commonMain` directory | Not treated as KMP automatically | Gradle's compilation model determines whether it belongs to KMP |
| Delete a KMP source file | Requires Gradle | The incremental flow does not perform complete output cleanup after deletion |

### Compose Multiplatform resources

| Scenario | Support | Details |
| --- | --- | --- |
| Add or modify `string`, `drawable`, or `font` resources | Supported | Generates and compiles type-safe accessors and prepares runtime resources |
| Add or modify `string-array` or `plurals` | Supported by the modern resource flow | The legacy resource flow does not support accessors for these types |
| Add or modify resources under `files/` | Supported for deployment | Does not generate type-safe accessors |
| Use a custom Compose resource directory | Supported | The directory must appear in resource task metadata obtained from Gradle Sync |
| Sync generated accessors to the IDE | Best-effort | A sync failure affects only IDE browsing and indexing and does not mark a completed compilation result as failed |
| Delete a Compose Multiplatform resource | Requires Gradle | Deletion can change the accessor set and resource manifest, so the complete task must recalculate them |
| The current Compose plugin task or generator API cannot be recognized | Fails explicitly | Does not silently treat the files as Android `res/` resources |

## Trigger and result

KMP source changes use the source compilation flow:

```text
Identify the Kotlin compilation model for the Android target
  -> Add the common, platform, and intermediate source set inputs required for the current run
  -> Compile Kotlin output
  -> Convert and deploy incremental DEX
```

Compose Multiplatform resources use a separate resource flow:

```text
Read Gradle resource task metadata
  -> Use the generator provided by the project's Compose plugin to generate accessors
  -> Compile accessors and prepare changed runtime resources
  -> Deploy resources; restart the app when valid resource changes exist
```

Compose Multiplatform resources do not pass through Android `aapt2`. Jugg reads all known resource directories when generating accessors, while limiting deployment to resources added or modified in the current run.

## Boundaries

- After adding or changing a source set, Android target, Compose plugin version, resource directory, or Kotlin compiler arguments, run Gradle Sync and at least one full Gradle compilation first.
- When KMP complementary relationships are missing, Jugg uses only inputs that the current model can confirm. If `expect` / `actual` or symbol resolution errors remain, use Gradle compilation to refresh the baseline.
- IDE synchronization of Compose Multiplatform accessors is an auxiliary result. If compilation and deployment succeed but editor navigation is temporarily unavailable, rerun Gradle Sync instead of treating the current deployment as failed.
- Use Gradle compilation directly after deleting KMP sources or Compose Multiplatform resources to avoid stale output or accessors.

## Related pages

- [Incremental KMP source compilation](/concepts/incremental-compile/kmp-source)
- [Compose Multiplatform resources](/concepts/incremental-compile/compose-multiplatform-resource)
- [Incremental source compilation](/concepts/incremental-compile/source)
- [Project information refresh and recovery](/concepts/project-info-refresh)
- [Gradle compilation fallback](/capabilities/compile/gradle-fallback)
