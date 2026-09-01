---
title: KMP source incremental compilation
description: Explains how Jugg supplies KMP expect/actual source from the Gradle compilation model and isolates stale Kotlin output.
status: active
tags:
  - concept
  - compile
  - kmp
  - kotlin
---

# KMP source incremental compilation

A KMP Android target can depend not only on the currently changed Kotlin file but also on `expect` / `actual` declarations from common, Android platform, and intermediate source sets. Jugg restores these relationships from Gradle synchronization and supplies required source during incremental compilation instead of treating directory structure as the compilation model.

## `expect` / `actual` relationships are not directory pairs

Files or declarations with the same name, and directories such as `commonMain` / `androidMain`, provide clues but do not fully describe Kotlin compilation relationships. A project can use an intermediate source set such as `sharedMain`, and an ordinary Android module can also contain directories with those names.

Jugg therefore does not guess complementary source from file or directory names. A source set enters the corresponding incremental compilation context only when the Gradle model confirms that it participates in the current Android compilation.

## The Gradle compilation model defines the Android target

Gradle synchronization provides the Kotlin compilation for the Android target, common source directories, and source set fragment dependencies. Jugg uses this information to answer three questions:

- Whether the current file belongs to a KMP Android compilation target.
- Which common, platform, or intermediate source sets participate in compilation with it.
- Which target parameters and dependencies the current Kotlin compilation needs.

After adding a target, changing the source set hierarchy, or modifying Kotlin compilation parameters, synchronize Gradle first so that these relationships enter the new compilation baseline.

## How complementary source enters the same compilation round

Each successful project compilation records complementary source relationships that can be confirmed. If a later Run changes only one side, Jugg adds the required source from the other side to the current inputs:

```text
KMP source changed in the current Run
  → locate its Android compilation target
  → add common, platform, or intermediate source from confirmed relationships
  → compile with the same Kotlin compilation parameters
```

If complementary information is missing or ambiguous, Jugg uses Best-effort behavior: it preserves source inputs that can be confirmed and does not force a name-based pairing. This avoids adding unrelated files. If required source truly cannot be restored, the current Run preserves the actual compiler error and asks for Gradle to refresh the baseline.

## Why stale Kotlin output can interfere with compilation

In Kotlin 1.9 scenarios, output related to `expect` / `actual` from the previous Run can reenter incremental analysis. If this output appears with current source, the compiler can identify the same declaration twice, producing duplicate declarations or incorrect symbol resolution.

Jugg uses the source-to-historical-output mapping to isolate old output that may conflict with current compilation inputs. It does not move or delete formal Gradle baseline files; the handling affects only the current Jugg compilation.

## Only successful compilation advances complementary relationships

Complementary relationships come from the latest successful project compilation. Inputs from a failed compilation may be incomplete and cannot become a reliable basis for the next Run. Jugg therefore updates relationship records only after Kotlin compilation succeeds. On failure, it preserves the existing baseline and final exception.

## Boundaries

- After enabling KMP for the first time, changing targets, or reorganizing source sets, run Gradle synchronization and a full Gradle compilation first.
- Directory names in an ordinary Android module do not automatically trigger KMP handling.
- Deleting source may require cleaning historical output and recalculating relationships; use Gradle compilation.
- If unresolved `expect` / `actual`, fragment, or symbol-resolution errors occur, use Gradle compilation to refresh the project model and output baseline.

## Related pages

- [KMP and Compose Multiplatform](/capabilities/compile/kmp-compose-multiplatform)
- [Source incremental compilation](/concepts/incremental-compile/source)
- [Project model](/concepts/project-model)
- [Project information refresh and recovery](/concepts/project-info-refresh)
- [Gradle fallback](/capabilities/compile/gradle-fallback)
