# Compilation System: Release Obfuscation

> Last verified: 2026-10-07
> Consistency rule: If documentation conflicts with code, code takes precedence.

## 1. Purpose

This page explains how incremental DEX output keeps the installed minified APK's names, how inline effects produce `_jugg_fix` bridges, and what release runtime failures establish. General source-to-DEX order is in `02_compile_source.md`; effect analysis is in `03_deploy_data_generator.md`.

## 2. Core Source Index

| Entry | Location | Responsibility |
|---|---|---|
| `SourceCompiler.compileDexOutputs()` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/SourceCompiler.kt` | Sends minified variants through D8 and DEX remapping. |
| `DexMinifyCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/obfuscation/DexMinifyCompiler.kt` | Loads mapping and optional usage data, asks deployment analysis for inline effects, and emits ordinary and bridge DEX. |
| `DexObfuscator` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/obfuscation/DexObfuscator.kt` | Maps DEX definitions and references, redirects eligible inline effects, and renames bridge declarations. |
| `R8MappingReader` / `R8UsageReader` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/obfuscation/` | Read R8 name mappings and removed-member records. |
| `CompileEffectAnalyzer.getMinifyInfo()` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/CompileEffectAnalyzer.kt` | Uses deployment data to identify affected classes and locate original `.class` inputs for bridges. |

`ClassMinifyCompiler` and `ClassObfuscator` provide class-file remapping in the same directory, but no production compilation call site currently constructs `ClassMinifyCompiler`. Follow `SourceCompiler` → `DexMinifyCompiler` for the active path.

## 3. Data and State Boundaries

| Input or state | Origin | Consequence |
|---|---|---|
| `ModuleInfo.minifyEnabled` | Selected `buildVariant` in project info | `ICompileContext.isMinified` is true only for explicit `true`; an old mapping file cannot enable minify. |
| `mapping.txt` | Application module's `build/outputs/mapping/<variant>/` | Required for a minified run. Missing mapping fails the incremental task before DEX remapping. |
| `usage.txt` | Same variant mapping directory | Optional for replacing R8-removed methods in bridge inputs with compatibility stubs. Its absence or read failure does not stop ordinary remapping. |
| `MinifyInfo` | `CompileEffectAnalyzer` and deployment database | Carries affected class names plus original `.class` files. Redirect targets are limited to affected classes with a matching class file. |

At the application Gradle boundary, `GradleApplicationInjector` writes `build/jugg/proguard-rules.pro` and attaches it to build types it judges minified. The rules keep `Application` and `AppComponentFactory` subclasses and suppress R8 warnings for selected bundled Dragonfly Kotlin references and `org.jetbrains.annotations.NotNull`/`Nullable`, which may be absent from a Java-only app classpath. A warning suppressed there does not establish that a later incremental DEX reference was correctly remapped; use the selected variant's mapping and staged/APK DEX for that diagnosis.

## 4. Cross-Stage Flow

```text
Selected variant enables minify
  -> SourceCompiler writes D8 output under temp/un_minify
  -> DexMinifyCompiler.initIfNeeded() requires mapping.txt from that variant
  -> preObfuscateForMinifyInfo(): temporary plain remapping gives CompileEffectAnalyzer APK-compatible class names
  -> deployment analysis returns MinifyInfo and available original class files
  -> generateJuggFixClasses(): rewrite usage.txt-removed methods, run D8, fully remap DEX,
     then renameDexClassDeclaration() changes only the declared class name to <obfuscated-name>_jugg_fix
  -> DexObfuscator.obfuscateWithInlineRedirect() redirects eligible inline effects to those bridge names
  -> source compilation hands both output sets to deployment staging
```

Temporary remapping matters because deployment data describes classes by the installed APK's obfuscated names, while D8 first emits original names. A failed temporary remap falls back to original DEX for that query; a surprising “missing class” result therefore needs the temporary input and database names checked together.

The bridge keeps code-body references to the original obfuscated class while changing its own declaration. `renameDexClassDeclaration()` also strips bridge fields and `<clinit>`; the bridge is not a second independently initialized copy of the class.

## 5. Failure and Diagnostic Boundaries

- When minify is enabled and `mapping.txt` is absent, `DexMinifyCompiler` warns and fails the incremental task. When minify is disabled, `SourceCompiler` skips this stage even if an old mapping remains. Check variant metadata before treating file presence as evidence.
- `usage.txt` only affects `_jugg_fix` input methods. Exact signatures are preferred; name-only matching is used only when both the usage record and bytecode have one method of that name. The reader records removed fields too, but this bridge rewrite consumes removed methods. An unreadable file produces a warning and disables this rewrite; individual unrecognized lines may yield no matching record.
- Bridge generation catches failures and can return fewer outputs while ordinary DEX processing continues. `MinifyInfo` still drives redirect selection, so a “Generated N _jugg_fix DEX files” message alone does not prove every redirected target exists. Compare `MinifyInfo.classFiles`, generated bridge paths, and staged DEX when a redirect target is missing.
- `Obfuscated:` is a debug summary of successfully processed DEX files. It does not prove that every definition or reference was remapped; DEX with no remapping is copied to output. A runtime exception is a lead for inspecting the relevant DEX position, not proof of a particular visitor defect.

| Observation | Candidate mapping boundary | Next discriminating evidence |
|---|---|---|
| Annotation or reflection lookup fails | Annotation type and nested `DexType` values | Compare the staged annotation descriptor with APK DEX and `mapping.txt`. |
| `NoClassDefFoundError` | Class literals, arrays, field/method prototypes, invoke-custom arguments, exception types, or type instructions | Locate the unresolved reference in staged DEX, then compare its APK counterpart. |
| `IllegalAccessError` or `IncompatibleClassChangeError` | Access widening relative to R8 `-allowaccessmodification`; same-class non-constructor direct invoke must become virtual when widened | Compare access flags and invoke opcode in staged and APK DEX. |
| `AbstractMethodError` on a new class or lambda | A declaration without its own mapping may need the interface/superclass method name | Compare implemented method names against the mapped hierarchy. |
| `NoSuchMethodError` on a Kotlin facade or kept class | Synthesized entries can contain qualified names or intermediate parameter types; identity entries must not replace real renames | Compare the actual method prototype with the normalized mapping entry and APK method. |

For these runtime comparisons, first confirm the selected variant and mapping loaded, then compare staged and APK DEX (for example with `dexdump -a`). Preserve the exact exception and referenced descriptor; the exception category does not establish which mapping step failed.

## 6. Related Documents

- `02_compile_source.md` — source and D8 stages.
- `03_deploy_data_generator.md` — minify effect types and deployment-data analysis.
- `09_plugin_runtime_debug.md` — runtime evidence and investigation scope.
