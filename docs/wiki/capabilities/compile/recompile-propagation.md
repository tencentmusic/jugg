---
title: Recompilation
description: Explains how class-structure and inline-constant changes cause Jugg to compile unchanged consumers, including the corresponding support and fallback boundaries.
status: active
tags:
  - capability
  - compile
  - recompile
  - const
---

# Recompilation

After directly changed sources compile successfully, Jugg can find unchanged sources that must adapt to the current changes and add them to the next compilation round. Impact can come from class-structure changes such as methods, fields, and generics, as well as Java/Kotlin inline constant changes.

This page explains which changes trigger additional source compilation and what users observe. For class-structure comparison and round-by-round propagation, see [Recompilation internals](../../concepts/incremental-compile/recompile-propagation.md). For why constants require separate analysis, see [Constant reference analysis](../../concepts/incremental-compile/const-ref.md).

## Supported scope

| Impact source | Current support | User-visible result |
|---|---|---|
| Method deletion or JVM signature change | Supported | Direct callers may be added for compilation; instance methods also check subclasses for old calls through virtual dispatch |
| Method access modifier changes that affect invocation | Supports changes confirmed from class structure | Consumers still compiled against the old invocation form may be added for compilation |
| Field deletion or type change | Supported | Sources accessing the old field may be added for compilation |
| Add an abstract method to an abstract parent class or interface | Supported | Subclasses or implementations that do not implement the new method may be added for compilation |
| Class-level generic signature change | Supports some deterministic scenarios | Subclass declaration chains and callers with direct method or field references may be added for compilation |
| Change a Java inlineable `static final` constant | Supported | Consumers that may contain the old literal value are added for compilation |
| Change a Kotlin `const val` | Supported | Consumers of common top-level, object, companion, and nested class/object forms are added for compilation |
| Delete a constant or change `const -> val` | Supported | Consumers of the old value may still be added for compilation so they do not retain the old literal |
| Change the JVM signature of a Kotlin top-level or extension declaration | Supported for matched file-facade scenarios | Previously compiled consumers may enter another compilation round when necessary to avoid retaining the old signature |

> [!NOTE]
> Constant reference analysis uses syntax-only reference candidates for conservative matching, so it may add multiple candidate consumers for compilation. Matching does not require a complete scan of the target constant.

## Changes outside regular source propagation

| Scenario | Handling |
|---|---|
| Change only a method body | The current file compiles and deploys normally, but regular callers are not added solely for this reason |
| Change an `extends` / `implements` relationship | The changed class enters deployment decisions, but this structural difference is not used directly to find affected sources; use Gradle compilation when unchanged sources must adapt |
| Release inline or members removed by R8/ProGuard | [Release compilation](./release-compile.md) performs bytecode compensation; this is not source recompilation |
| Generated sources such as `R.java` or DataBinding/ViewBinding | The corresponding generation stage passes them directly to [Source compilation](./source-compile.md); they are not impact propagation after compilation succeeds |

## Trigger and result

```text
Directly changed sources compile successfully
  -> Collect class-structure changes
  -> Compare inline constant definitions and match reference candidates
  -> Merge both result sets into affected sources
  -> Add unprocessed affected sources to the next compilation round
  -> Continue when new artifacts produce additional impact
  -> Enter deployment when no new affected sources remain
```

Additional compilation is a normal step after the first round succeeds; it does not mean the first compilation failed. The same file re-enters a later round only when a new impact source appears or a confirmed scenario such as a Kotlin file facade is matched.

## Common user-visible behavior

| Behavior | Meaning |
|---|---|
| The log shows `Detect effected sources` | The current run is compiling sources found by impact analysis |
| The log shows `found effected source files, continue compile` | The current compilation succeeded and found the next set of sources to compile |
| A small change compiles multiple files or multiple rounds | A method, field, abstract method, generic, or inline constant change affected unchanged sources |
| Jugg falls back to Gradle after many files or modules become affected | The additional scope exceeded current incremental compilation limits |

## Boundaries

- Impact analysis depends on the latest trustworthy Gradle build and on class structures, reference relationships, and source mappings recorded by later successful deployments.
- After switching build variants, changing build configuration, or otherwise invalidating the baseline, run Gradle compilation to rebuild the index.
- When affected sources or modules exceed incremental compilation limits, Jugg stops expanding the current work and falls back to Gradle.
- If a class cannot be mapped back to a source file, Jugg cannot automatically compile its consumer in the current run. If an old-caller failure occurs, use Gradle compilation to refresh the baseline.
- Java constant analysis covers only inlineable `static final` fields. Kotlin analysis treats only `const val` as inline constants; regular `val` does not enter this analysis.
- `private const val` and `private static final` do not enter the constant reference index. They affect only declaration files already included in the first compilation round.
- Similar text in comments and strings is not recognized as a constant reference candidate.
- When constant analysis is unavailable, the main flow continues using completed caches or an empty result. This alone does not stop compilation or trigger Gradle fallback, but the current run may not include every consumer.
- Multiple worktrees in the same repository can share file fingerprints and constant analysis caches. Caching reduces repeated parsing without changing impact decisions.

## Related pages

- [Source compilation](./source-compile.md)
- [Release compilation](./release-compile.md)
- [Compilation stages](../../guide/compile.md)
- [Recompilation internals](../../concepts/incremental-compile/recompile-propagation.md)
- [Constant reference analysis](../../concepts/incremental-compile/const-ref.md)
