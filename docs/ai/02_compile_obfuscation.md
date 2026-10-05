# Compilation System: Obfuscation Mapping

> Last verified: 2026-09-16
> Consistency rule: If documentation conflicts with code, code takes precedence.

---

## 1. Purpose of This Document

This page covers mapping consistency only for release/minified builds: converting unobfuscated class/dex output into obfuscated output consistent with the installed APK, and generating `_jugg_fix` bridge classes.

For source-to-dex order, see `02_compile_source.md`; for incremental Manifest merge, see `02_compile_manifest.md`; for release runtime investigations, see `09_plugin_runtime_debug.md`.

---

## 2. Core Source Index

| Class/interface | File | Role |
|---|---|---|
| `ClassMinifyCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/obfuscation/ClassMinifyCompiler.kt` | Rewrites mapping at class level; copies the original class when no mapping applies |
| `DexMinifyCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/obfuscation/DexMinifyCompiler.kt` | Rewrites mapping at dex level, reads inline-effect information, generates `_jugg_fix` DEX, and rewrites `usage.txt` compatibility stubs |
| `ClassObfuscator` / `DexObfuscator` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/obfuscation/` | Remaps class/dex names, fields, methods, and internal references |
| `R8MappingReader` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/obfuscation/R8MappingReader.kt` | Reads `mapping.txt` and exposes class/method/field mapping queries |
| `R8UsageReader` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/obfuscation/R8UsageReader.kt` | Reads `usage.txt` and records classes, methods, and fields removed by R8 |

---

## 3. Core Data Flow

| Data | Producer | Consumer | Key constraint |
|---|---|---|---|
| `mapping.txt` | Installed APK / incremental-data directory | `ClassMinifyCompiler`, `DexMinifyCompiler` | Required when the variant enables minify; absence fails, never silently skip |
| `usage.txt` | R8/ProGuard output | `DexMinifyCompiler` | Enhances compatibility rewriting only for `_jugg_fix` input classes; if absent or unparseable, continue without pruning deleted methods |
| `MinifyInfo` | Deployment-data/effect-analysis chain | `DexMinifyCompiler` | Identifies classes affected by inline changes and original class inputs to `_jugg_fix` |

Only the selected variant's actual `minifyEnabled` decides whether obfuscation runs. `GradleProjectInfoReader` / `GradleVariantCollector` write `Variant.minifyEnabled` to project info, `ModuleInfo.minifyEnabled` derives it from `buildVariant`, and `ICompileContext.isMinified` checks only whether it is `true`. The presence of `outputs/mapping/<variant>/mapping.txt` does not decide: an old mapping can remain on disk after a user disables minify for a previously minified variant. Using its existence would misclassify unobfuscated output as obfuscated.

---

## 4. Core Call Chain

```text
SourceCompiler.compileDexOutputs()
  -> DexCompiler generates unobfuscated dex; minified cases write to temp/un_minify
  -> DexMinifyCompiler.initIfNeeded() loads mapping.txt and optionally usage.txt
  -> preObfuscateForMinifyInfo() temporarily obfuscates dex so getMinifyInfo() can query DB using obfuscated class names in the installed APK
  -> context.getMinifyInfo() returns classes affected by inline changes and original class files
  -> generateJuggFixClasses() rewrites original classes into usage.txt stubs, runs D8, obfuscates, and calls renameDexClassDeclaration
  -> ordinary incremental dex then runs obfuscateWithInlineRedirect() or obfuscate()
  -> output dex / `_jugg_fix` dex consistent with APK mapping
```

`_jugg_fix` uses a bridge strategy of “fully obfuscate first, then change only the declared class name.” After the declaration gains the `_jugg_fix` suffix, internal calls still target the original obfuscated class, so the bridge does not become an independent implementation outside the APK mapping.

---

## 5. Hidden Constraints / Design Rationale / Known Boundaries

- When a variant enables minify but `mapping.txt` is missing, fail hard: `ClassMinifyCompiler` / `DexMinifyCompiler` emit a user-visible `warn` and fail this incremental run without wrapping the original task result. Without mapping, output names must diverge from the installed APK, and silently continuing would deploy an artifact bound to crash at runtime. Check for this warning first when investigating a release failure.
- When the variant disables minify, skip obfuscation even if old `mapping.txt` remains in its directory from a previous minified build. Jugg neither deletes nor cleans that file; it routes only by actual configuration.
- `usage.txt` participates only in compatibility rewriting of `_jugg_fix` input-class method bodies. Removed methods keep their signatures but become empty implementations/default returns. The reader also records deleted fields, while the current chain primarily consumes removed methods.
- Some R8 versions erase parameter information for Kotlin property accessors in `usage.txt`. If an exact signature does not match, fall back by name only when the method name is unique in both usage and class bytecode. If either side has an overload, retain the original method to avoid pruning the wrong same-name member.
- `preObfuscateForMinifyInfo()` lets DB queries use the APK's obfuscated class names. Skipping it can falsely suggest that a class is absent from DB.

### 5.1 DEX Mapping-Completeness Boundary

`DexObfuscator` uses a dex2jar visitor and cannot automatically cover every type reference as ASM `ClassRemapper` does. For release runtime crashes, locate a possible mapping gap according to its DEX position:

| Failure pattern | Current mapping constraint | Key entry point |
|-----------------|----------------------------|-----------------|
| Annotation/reflection lookup failure | Type descriptors on class, field, and method annotations must all pass through `mapType()` | `visitAnnotation()` |
| `NoClassDefFoundError` | Map `const-class`, field/method owner and proto, invoke-custom arguments, arrays, exception tables, and type statements | `visitCode()` overrides in each `DexCodeVisitor` |
| `IllegalAccessError` / `IncompatibleClassChangeError` | Member access flags must match the R8 `-allowaccessmodification` baseline. A non-constructor, non-static direct invoke on the current class must match the widened virtual form | `widenAccessFlags()`, `visitMethodStmt()` |
| `AbstractMethodError` on a new class or lambda | When the class has no mapping, derive a method name from its interface/superclass first, then fall back to the class itself | `mapMethodForCurrentClass()` |
| `NoSuchMethodError` on a Kotlin facade / kept class | Normalize qualified method names and intermediate parameter types in R8 synthesized entries; identity mappings must not overwrite real renames | `normalizeMethodParams()`, `methodNameMap` construction |

First confirm `mapping.txt` loaded and the log contains `Obfuscated:`, then compare staging DEX with APK DEX using `dexdump -a`. The exception type helps choose where to compare; it does not prove that a particular visitor or mapping entry failed.

---

## 6. Investigation Entry Points

| Symptom | First entry point |
|---|---|
| Class/method name mismatch after release increment | `DexMinifyCompiler.initIfNeeded()` and `DexObfuscator`; first confirm mapping loaded |
| Annotation/type/access/method mapping failure after release increment | §5.1 here; compare staging/APK DEX and mapping before locating a `DexObfuscator` point |
| `_jugg_fix` exists but runtime calls fail | `generateJuggFixClasses()`; check D8 output class name, post-obfuscation path, and `renameDexClassDeclaration()` |
| Abnormal effect analysis for a member removed by minify | `03_deploy_data_generator.md` §5.6; check `effectedType=MINIFY_MEMBER_REMOVED` |

---

## 7. Related Documents

- Source compilation: `02_compile_source.md`
- Incremental Manifest merge: `02_compile_manifest.md`
- Effect analysis and minify types: `03_deploy_data_generator.md`
- Release runtime investigation: `09_plugin_runtime_debug.md`
