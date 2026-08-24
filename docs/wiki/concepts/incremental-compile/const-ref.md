---
title: Constant reference analysis
description: Explains why compiling only the defining file after a compile-time constant changes can miss inlined users, and how Jugg uses a separate index to recompile them.
status: active
tags:
  - concept
  - compile
  - const-ref
---

# Constant reference analysis

After a developer changes a Java `static final` constant or Kotlin `const val`, the defining file can compile successfully while unchanged users still contain the old literal. Ordinary structural propagation cannot recover these users from DEX method calls or field accesses. Jugg therefore records constant definitions and reference candidates from source and adds matching users to the next compilation round after a constant actually changes.

## Why constant inlining leaves old values in users

Java compile-time constants and Kotlin `const val` values are inlined into user bytecode during compilation. After source code that references `Config.VERSION` is compiled, the user stores the constant value itself instead of reading `Config.VERSION` on every execution.

```text
change Config.VERSION
  -> recompile only Config
  -> unchanged callers still contain the old literal
  -> current compilation succeeds
  -> the app continues to display or use the old value
```

Ordinary recompilation propagation relies on method calls, field accesses, and inheritance relationships. Those runtime references no longer exist in user bytecode after a constant is inlined, so comparing old and new class structures alone cannot find the source files that require recompilation.

## How Jugg recovers users outside bytecode

Jugg maintains a separate source index for compile-time constants and records two kinds of facts:

| Indexed content | Recorded information |
|---|---|
| Constant definition | File, package, class, constant name, type, and value |
| Reference candidate | Source locations whose syntax may reference a constant, plus related class, package, and import information |

A reference candidate does not require the corresponding constant definition to have been scanned already. Scan order therefore does not hide an earlier user, and candidate results already stored on disk remain usable while a background full scan is incomplete.

This index does not perform complete semantic analysis. It matches source syntax conservatively. It may compile additional candidate files, but it does not block every incremental compilation while waiting for semantic analysis of the entire project.

## Only an actual constant change triggers recompilation

Jugg compares constant definitions in the same file before and after analysis. The type and value of a constant name form its signature. A change key is produced only when a definition is added, its signature changes, or the definition disappears. Whitespace, formatting, or other edits that do not change a constant definition do not trigger constant-reference recompilation.

Deleting a constant or changing `const val` to ordinary `val` produces a removed key. Users previously inlined the old value, so Jugg still uses old reference candidates to find and recompile those source files.

## How matching users enter the next compilation round

Before compilation, Jugg prioritizes constant analysis for source changed in the current Run, then queries potentially affected users:

```text
analyze source changed in the current Run
  -> find added, changed, or removed constants
  -> match recorded source reference candidates
  -> merge with source found by ordinary structural propagation
  -> add to the next source compilation round
```

The query excludes changed source already compiled in the current Run and files that were deleted, and deduplicates repeated results. Matching favors avoiding missed compilation, so one constant change can add multiple candidate users.

## Why a failed Run does not lose the same impact

Impact queries read constant changes from the current Run without clearing them immediately. Jugg confirms that a record has been consumed only after recompilation and deployment both succeed.

If later source compilation, impact propagation, or deployment fails, the constant change remains. The next Run can query the same users again, preventing a failed Run from advancing state early and permanently missing recompilation of old literals.

## Analysis scope and Best-effort boundaries

Constant reference analysis has a defined scope:

- Java records only `static final` fields eligible for inlining.
- Kotlin records `const val` declarations at top level, in objects and companions, and in nested classes / objects.
- `private const val` and `private static final` do not enter the index. They can affect only the source file containing the declaration, which already compiles in the first round.
- Similar syntax in comments and string literals is not treated as a reference candidate.

The index reuses stored results by file content, and multiple worktrees of the same repository can share file fingerprints. These caches only reduce repeated parsing; they do not change the source of constant change and reference candidate decisions.

Constant reference analysis joins compilation as Best-effort behavior. Waiting before compilation has a timeout. After timeout, Jugg records a warning and queries from completed cache entries. If a query or cache initialization fails, the current Run returns an empty result and does not fail compilation or trigger Gradle fallback solely because constant reference analysis failed.

Under this degraded behavior, the current Run may not recompile every user and code on the device can retain an old value. If a constant change does not take effect, run a Gradle build to restore the complete compilation result and begin troubleshooting from ConstRef warnings in the compilation log.

## Related pages

- [Recompilation](./recompile-propagation.md)
- [Source incremental compilation](./source.md)
- [Deployment data and impact analysis](../deploy-data-and-impact.md)
- [Recompilation capability](../../capabilities/compile/recompile-propagation.md)
