---
title: Release incremental compilation
description: Explains how experimental release incremental compilation integrates with D8, re-obfuscates DEX by reusing the mapping, and cooperates with recompilation.
status: active
tags:
  - concept
  - compile
  - release
  - minify
---

# Release incremental compilation

In a release APK, or another APK with minify enabled, R8 / ProGuard has rewritten class, method, and field names. Source code in the current Run still first produces classes and DEX that use original names. Jugg then reuses the mapping for the current APK to re-obfuscate incremental DEX so that it can continue to reference existing classes and members in the APK.

> [!WARNING]
> **Experimental capability**
>
> Release incremental compilation has not yet been validated across a large number of real-world projects. Changes may fail to take effect or cause runtime crashes. If you encounter a problem, provide a reproducible demo and submit an issue.

## Release handling runs after D8

Release re-obfuscation is the final stage of source incremental compilation. Kotlin and Java still compile to ordinary classes first, and D8 still produces DEX that uses original names. Only when the current build baseline contains `mapping.txt` are these DEX files written to a temporary unobfuscated directory before release handling:

```text
Kotlin / Java source
  -> generate unobfuscated classes
  -> D8 generates unobfuscated DEX
  -> re-obfuscate DEX according to mapping.txt
  -> generate required _jugg_fix DEX
  -> final DEX enters staging
  -> analyze whether recompilation must continue
```

This does not rerun complete R8. The incremental stage does not repeat whole-package shrinking or optimization, nor does it generate a new mapping. It only replays name conversion from the mapping of the current APK and compensates for some inlining and removed-member results. The mapping and APK on the device must therefore come from the same trusted Gradle baseline.

## When release incremental compilation is triggered

Jugg does not use the variant name to decide whether to run release incremental compilation. It checks whether `mapping.txt` exists under the current application build path:

- When the mapping exists, D8 first writes unobfuscated DEX to a temporary directory, then runs mapping replay, impact analysis, and `_jugg_fix` compensation.
- When the mapping does not exist, D8 writes DEX directly to the final output directory without creating a re-obfuscation task or reading `usage.txt`.

An ordinary debug build usually does not generate a mapping and naturally follows the second path. This decision also covers custom variants: a variant whose name contains `release` but has no mapping does not enter re-obfuscation, while a variant named debug that actually enables minify and produces a mapping does. The variant name describes the intended build use; it is not the trigger for release incremental compilation.

## How unobfuscated DEX is re-obfuscated

During the release stage, Jugg loads `mapping.txt` and applies the same name mapping to class declarations, output paths, method names, field names, type references, and internal call references in DEX. Classes with no matching mapping entry retain their original names instead of receiving newly allocated names for the current incremental Run.

Impact analysis uses an index built from the baseline APK, which stores obfuscated class names. To query inlining and removed-member impact, Jugg temporarily maps the current DEX once and uses the obfuscated names to query the APK index. This temporary DEX is used only for analysis; the final output still comes from the formal re-obfuscation step:

```text
unobfuscated DEX
  -> temporary remapping to query the APK index with obfuscated class names
  -> obtain inline / removed-member impact information
  -> formally remap classes, members, types, and call references
  -> generate final DEX paths from the obfuscated class names
```

## `_jugg_fix` compensates for inlining and removed members

R8 can inline method implementations into callers or remove unused members. Merely remapping names in the current DEX cannot recover transformations already embedded in the baseline APK.

Jugg uses impact analysis to generate `_jugg_fix` DEX for original classes that need compensation:

```text
read the original class for an affected type
  -> use usage.txt to rewrite removed methods as compatible empty implementations
  -> D8 generates DEX
  -> obfuscate according to mapping.txt
  -> rename only the class declaration to obfuscated name + _jugg_fix
  -> redirect current calls to the bridge DEX
```

Method calls and field accesses inside the bridge DEX still target the original obfuscated class in the APK. This reuses the member layout of the current APK instead of creating a separate naming result. If `usage.txt` is missing or cannot be parsed, only compatible rewriting of removed methods is skipped; mapping remapping and other usable artifacts continue. If one `_jugg_fix` DEX cannot be generated, only the affected bridge artifact is discarded and a warning is recorded.

## How recompilation continues through re-obfuscation

At the end of each source compilation round, the DEX entering staging has already been re-obfuscated. Impact analysis compares these DEX files with the reference index from the baseline APK to find callers, subclasses, or other affected source files that still need compilation.

Class names returned by the APK index may already be obfuscated. Jugg uses the same mapping to restore their original names and then locates source code in the project. The next recompilation round starts again from this original source code and passes through language compilation, D8, and re-obfuscation, rather than directly editing obfuscated DEX from the previous round:

```text
final obfuscated DEX from the current round
  -> query the APK reference index
  -> restore affected class names to original names
  -> locate and compile affected source files
  -> D8 generates unobfuscated DEX again
  -> re-obfuscate with the same mapping
  -> new final DEX enters staging
```

Later propagation rounds still run re-obfuscation, but ordinary impact propagation does not repeatedly add the same inline impact, avoiding a loop caused by one release compensation reason.

## When the mapping baseline is missing or mismatched

`mapping.txt` is both the re-obfuscation input and the gate for entering release handling. If the file does not exist, source compilation outputs DEX through the non-minified path without replaying the mapping. That DEX cannot be deployed reliably to an already obfuscated APK. Release incremental compilation can reuse the result only after a full Gradle release build produces a matching mapping and APK baseline.

If the current mapping does not match the installed APK, name conversion itself may succeed while runtime still encounters `NoClassDefFoundError`, `NoSuchMethodError`, `IllegalAccessError`, annotation lookup failures, or similar problems. Preserve the logs, provide a reproducible demo, and submit an issue if this occurs.

## Related pages

- [Incremental compilation overview](./index.md)
- [Source incremental compilation](./source.md)
- [Recompilation](./recompile-propagation.md)
- [Release compilation capability](../../capabilities/compile/release-compile.md)
