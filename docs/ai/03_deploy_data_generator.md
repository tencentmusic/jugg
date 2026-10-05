# Deployment System: Impact Analysis and Deployment-Data Generation

> Last checked: 2026-09-10
> Consistency rule: when documentation conflicts with code, follow the code.

---

## 1. Scope

This page explains how, after receiving incremental compilation outputs, Jugg decides which classes, resources, and libraries enter this run's `JuggDeployData`, and which source files or bytecode need compensating updates.

For deployment execution, device-state recovery, and constant-reference database details, see `03_deploy_core.md`, `03_deploy_complete.md`, and `03_deploy_const_ref.md`.

---

## 2. Core Source Index

| Class/interface | File | Role |
|---|---|---|
| `DeployDataGenerator` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/DeployDataGenerator.kt` | Builds `JuggDeployData` from `DeployItem` and deployment history; centrally chooses hot-reload, hot-fix, or reinstall inputs. |
| `DeployDataDatabase` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/DeployDataDatabase.kt` | Facade for APK and incremental-deployment indexes, combining reference queries and commits from SQLite helpers. |
| `DeployDataDatabaseSqLiteHelper` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/DeployDataDatabaseSqLiteHelper.kt` | SQLite method/field/subclass/source index queries; primary source of facts for effectedSource propagation. |
| `ApkParserProcessLauncher` / `ApkParserProcess` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/` | Parse APK/Dex in a separate JVM and update SQLite directly, isolating transient heap demand from large-project parsing. |
| `DexFileNodeCollector` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/DexFileNodeCollector.kt` | Collect class structure and method/field/subclass references. The method visitor must keep delegating declaration-annotation parsing so member generic signatures are retained. |
| `ClassNodeComparator` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/ClassNodeComparator.kt` | Compares old and new `ClassNode` objects for structural, abstract, and generic-signature changes. |
| `InlineMethodDetector` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/InlineMethodDetector.kt` | Finds R8-inlined callers in mapping for release/minify builds, adding classes needing bytecode compensation. |
| `EffectedClassNode` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/EffectedClassNode.kt` | Affected-class model distinguishing source recompilation, inline compensation, and minify-removal compensation. |
| `ConstRefEffectProvider` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/ConstRefEffectProvider.kt` | Constant-reference impact entry point; its result goes into `constRefEffectedSourcePaths`, separate from `effectedClassNodes`. |
| `ClassFileParser` / `CompileEffectAnalyzer` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/` | Reuse pre-D8 program-class analysis to collect default interfaces, external superclasses, and Transformer dependencies for a complete incremental D8 classpath. |

---

## 3. Core Data Model

### 3.1 Key Fields of `JuggDeployData`

| Field | Source | Deployment semantics |
|---|---|---|
| `newClasses` | New classes absent from the old DB | No JVMTI redefinition; load lazily from a new-class overlay. |
| `hotReloadModifiedClasses` | `ClassNodeComparator.isCanHotReload = true` | Unchanged structure permits a lighter class update. |
| `hotFixModifiedClasses` | Multiple Dex / library Dex / structurally changed classes | More complex structure or ownership requires the hot-fix path. |
| `effectedSourceAndClassNodes` | Method/field/subclass/generic/minify/inline analysis | Callers requiring source recompilation or bytecode compensation. |
| `overlays` / `isFullRes` | Resource/asset changes + initial overlay history | First resource deployment fills in all res entries so device resources are not missing. |
| `updateApkFiles` | Manifest, `resources.arsc`, native libraries | Outputs requiring APK modification and resign/reinstall. |
| `constRefEffectedSourcePaths` | `ConstRefEffectProvider` | Source paths hit by constant references, separate from class-reference propagation. |

These three class categories are deliberately split by baseline and structural difference before deployment; they are not alternate names for one failure chain:

- `newClasses` have no definition in the old APK or deployment history; after writing the overlay, ClassLoader can load them at first reference.
- `hotReloadModifiedClasses` retain a redefinable structure. `OverlayUpdateBuilder` sends them as `modifiedClasses` to Android Studio deployer/JVMTI.
- `hotFixModifiedClasses` already exist but have structures that violate JVMTI constraints. Along with truly new classes, they use the deployer's `newClasses` transport, and `JuggDeployData.isNeedRestartApp` requires process restart to load their replacement versions from the overlay.

Thus Jugg's hot reload and hot fix share an overlay-data channel but use JVMTI redefine and post-restart ClassLoader replacement, respectively. When device/runtime JVMTI is unsuitable, compat deployment further switches to push-only and adds compatible runtime files. If the preliminary structural check misses a device-specific restriction, `DeployRetryHandler` still converts every modified class to HOT_FIX and retries once for definite signals such as `JVMTI_ERROR_UNMODIFIABLE_CLASS` or redefiner/internal errors.

### 3.2 Mapping `ClassNodeDiffResult` Downstream

| Field | Trigger | Downstream set |
|---|---|---|
| `effectMethods` | Deleted method, changed DEX descriptor or method-level generic signature, switch between `private` and non-private, or another effective access-flag change | `changedMethodRef` |
| `deletedFields` | Deleted field | `changedFieldRef` |
| `modifiedGenericSignatureFields` | Unchanged field DEX descriptor but changed field-level generic signature | `changedFieldRef` |
| `isAddedAbstractMethodForNonAbstractClass` or changed superclass/interface list | New abstract method on an abstract class/interface, or changed class hierarchy | `changedAbstractClasses` |
| `modifiedGenericSignature` | Changed class-level generic signature | `changedGenericSignatureClasses` |

Method and field reference identity still uses erased owner/name/descriptor. Generic signature is not part of `equals` or the reference-index key; only comparison of old and new declarations checks it. This lets member generic changes use existing `method_refs` / `field_refs` to find direct callers without breaking DEX-reference matching. Method-body-only changes do not enter `effectMethods`. `R$xxx` classes skip method/field reference propagation entirely, avoiding mass false recompilation during resource repair.

### 3.3 `EffectedType`

| Type | Detection source | Handling |
|---|---|---|
| `SOURCE` | Method/field/subclass/abstract/generic propagation | Recompile source. |
| `INLINE_IMPL_CHANGE` | R8-mapping inline callers found by `InlineMethodDetector` | `DexMinifyCompiler` bytecode compensation. |
| `MINIFY_MEMBER_REMOVED` | `getEffectedClassNodesForMinify` finds a class or member removed by R8/ProGuard | `DexMinifyCompiler` bytecode compensation. |

---

## 4. Core Call Chain

```text
compilation outputs become DeployItem
  -> DeployDataGenerator.buildDeployData(items)
     parse changed Dex and group resources / assets / native libraries
  -> ClassNodeComparator.compare(oldClassNode, newClassNode)
     compress structural changes into four signal groups: changedMethodRef / changedFieldRef / abstract / generic
  -> DeployDataDatabase.getEffectedSourceAndClass(...)
     find callers, subclasses, and generic-affected classes in the historical reference index; optionally add minify-removal compensation
  -> InlineMethodDetector.findInlineEffectedClasses(...)
     in release/minify, include classes retaining old inlined copies
  -> ConstRefEffectProvider.ensureReadyForRecompile() + getEffectedFiles()
     independent constant-reference query; failure degrades only to completed cache / empty result
  -> JuggDeployData
     pass to deploy/run to decide install, Apply Changes, restart, and commit
```

Do not treat `buildDeployData()` as committed state. `commitDeployedData()` writes deployment history only after downstream deployment succeeds. Staging/deploy data from a failed run must not contaminate the next run.

### 4.1 D8 Desugar Classpath

When recompiling affected sources, `DexCompiler` reads program classes once and uses `ClassFileParser` to build an explicit `ClassPreparation`. `TransformerCompiler` consumes and updates that preparation. `DeployDataGenerator.getDesugarInfo()` uses its batch analysis to identify interfaces with default methods and their interface-inheritance chains. `CompileEffectAnalyzer.getDesugarInfo()` uses its external direct superclasses and recursively completes the superclass hierarchy through header-only reads. Neither the normal path nor compatibility calls implicitly pass this through `CompileFile.extraInfo` or separately fall back to full reparsing of program classes.

The superclass hierarchy cannot be omitted. If a subclass inherits a superclass implementation and implements an interface with a default method, but D8 sees the interface without seeing the superclass, D8 may generate a synthetic bridge in the subclass that calls the interface default and bypasses the real superclass override. Superclasses already present in current program input are not recopied, and Android boot-classpath types are filtered. A generated `Hilt_*` superclass read during Hilt transformation enters the required classpath through the same preparation even without a default interface this run, avoiding duplicate searches during transformation and D8 preparation.

### 4.2 APK Baseline Index and Parsing Boundary

The APK database is more than a cache for class existence. Jugg must persist class structure, including class/method/field generic signatures; method/field references; parent-child class relationships; source mapping; and checksums of Dex/resource entries in the APK. These support HOT_RELOAD/HOT_FIX classification, impact propagation, resource completion, and the next APK-update diff. Keeping them long term in IDE heap would expose Android Studio to parsing peaks and GC for large APKs. The current `ApkParserProcessLauncher` isolation threshold is 0 MB, so ordinary APKs are parsed in a separate JVM. The subprocess updates app-scoped SQLite directly and releases parsing memory when it exits.

Parsing still follows best-effort behavior: if subprocess launch, classpath, or execution fails, warn and fall back to parsing in the IDE process rather than failing post-build context initialization outright. Database updates first use APK `lastModified` as a fast check, then entry checksums to identify new, deleted, and changed Dex/overlay entries. Rebuild that app's database when changed Dex exceeds 3 or reaches 20% of existing Dex count; otherwise parse changed entries only. This threshold is a performance policy, not deployment semantics. Changes must preserve incremental update for small changes and full rebuild for large ones.

On queries, successfully deployed classes/overlays in `IncrementalDeployDataDatabase` take precedence over the APK SQLite baseline. Otherwise, two consecutive incremental changes keep comparing against the initial APK, misclassifying class structures and propagating impact from stale references.

---
## 5. effectedSource Propagation Rules

`DeployDataDatabaseSqLiteHelper.getEffectedClassNodes()` currently converges through six steps to `EffectedClassNode(SOURCE)`:

| Step | Role | Constraint |
|---|---|---|
| Step 1 | Convert changed method/field/abstract/generic classes to DB classId values. | Later SQL depends on classId values already present in the historical APK/deploy DB. |
| Step 2 | For owners of non-static changed methods, query `subclass_refs` and construct virtual method references for subclasses. | Simulate virtual dispatch only. Static methods remain for Step 3 but must not begin subclass traversal. |
| Step 3 | Query `method_refs` / `field_refs` for classes directly calling or accessing changed members. | `changedMethodRefsWithSubclasses` includes static methods so direct static calls still match. |
| Step 4 | Recursively find subclasses of a class/interface with a newly added abstract method or changed hierarchy. | Every direct subclass must recompile; abstract subclasses propagate further. |
| Step 5 | For classes with changed generic signatures and their subclasses, find direct member callers and recurse through subclasses. | Addresses changed source generic constraints despite an unchanged descriptor after DEX erasure. |
| Step 6 | Resolve affected classId values back to class names/sources and build `EffectedClassNode(SOURCE)`. | Only here are source paths consumable by SourceCompiler created. |

The static filter in Step 2 is a high-risk boundary. `changedMethodRefsWithSubclasses` must retain all methods for Step 3's direct-reference query, but `currentSuperClassIds` may come only from owners of methods with `access == MISS_ACCESS || non-static`. Otherwise, static methods such as Kotlin lambdas / `$r8$lambda$` spuriously trigger recompilation cascading through a whole subclass tree.

Generic-signature propagation covers only two established cases: the subclass declaration chain, and direct method/field callers of the changed class or affected subclasses. An indirect scenario based solely on source generic constraints, without a direct member reference in DEX, cannot be assumed to match.

Member-level generic-signature changes do not enter Step 5's whole-class generic propagation. A method change enters Step 3 as an old-method reference; a field change enters Step 3 as an old-field reference. Only source directly calling or accessing that member is recompiled. For example, a Kotlin property getter can retain descriptor `GenericEvent` while its return generic signature changes from `GenericEvent<Boolean>` to `GenericEvent<Unit>`. A caller retaining an old lambda bridge may then fail a runtime cast, so its direct callers must recompile before deployment.

---

## 6. Release/Minify Compensation

When `isNeedCheckRecompileMinifyRemovedClass = true`, `DeployDataGenerator` passes `parsedDex` to DB queries and inline detection:

- `getEffectedClassNodesForMinify()` checks whether R8/ProGuard in the APK removed a class or member referenced by incremental Dex, then marks a hit as `MINIFY_MEMBER_REMOVED`.
- `InlineMethodDetector` reads mapping to find classes into which a changed method was previously inlined, marking hits as `INLINE_IMPL_CHANGE`.
- When `DeployDataGenerator.merge()` combines inline results, a class already marked `SOURCE` must remain `SOURCE`. Source recompilation is stronger than bytecode compensation; the reverse is not true.

With `isCompilingEffectedSourceFiles = true`, inline detection is skipped so recompiling affected source does not generate another round of inline compensation.

---

## 7. Hidden Constraints

- `isNeedCheckRecompile = false` skips both class-reference propagation and ConstRef queries; `effectedSourceAndClassNodes` and `constRefEffectedSourcePaths` should both be empty.
- Failed ConstRef readiness does not interrupt deployment-data generation. It warns and degrades the query; consult `03_deploy_const_ref.md` for cache-preparation state in this runtime case.
- First overlay deployment completes resources through `addFullRes()`. Do not judge device resource completeness solely from the number of resources changed in this run.
- `updateApkFiles` contains only Manifest, its paired `resources.arsc`, and native libraries; an ordinary overlay is not equivalent to APK modification.
- `deletedNormalMethodClasses` filters synthetic methods with `$` in their names so deleting a compiler-generated method is not treated as a user-code deletion signal.
- The APK parser subprocess provides memory isolation only; SQLite files still belong to applicationId. Multiple APKs with one applicationId must share a helper. Clean up databases for obsolete applicationIds only after new-round initialization completes.

---

## 8. Investigation Entry Points

| Symptom | Start with |
|---|---|
| A small edit produces extensive `effectedSource` | Step 2 in `DeployDataDatabaseSqLiteHelper.getEffectedClassNodes()`; check whether a changed method is static / `$r8$lambda$`. |
| A caller is not recompiled and fails at runtime | Output of `ClassNodeComparator.compare()` and whether Step 3 hits `method_refs` / `field_refs`. |
| Changed class-level generic constraints but empty effectedSource | `ClassNodeComparator.modifiedGenericSignature` and Step 5 generic propagation. |
| Changed method/field generics but a direct caller is not recompiled | Check whether `DexFileNodeCollector` retains member `dalvik.annotation.Signature` and whether `modifiedGenericSignatureMethods` / `modifiedGenericSignatureFields` enter Step 3 reference queries. |
| Missing class/member after release increment | `getEffectedClassNodesForMinify()` and `EffectedType.MINIFY_MEMBER_REMOVED`. |
| Release method-body change but a caller runs old logic | `InlineMethodDetector.findInlineEffectedClasses()` and presence of the mapping file. |
| Constant change does not trigger callers | `ConstRefEffectProvider.ensureReadyForRecompile()`, then `03_deploy_const_ref.md`. |
| `Isolated process parsing failed` with `ClassNotFoundException: ApkParserProcess` | Classpath construction in `ApkParserProcessLauncher`; check for URL-encoded paths. |
| IDE memory spikes during APK DB initialization after a full build | Confirm whether a subprocess was used; if parsing fell back in-process, check Java home, plugin classpath, and subprocess output first. |

---

## 9. Related Documents

- Deployment core: `03_deploy_core.md`
- Complete deployment flow: `03_deploy_complete.md`
- Constant-reference impact analysis: `03_deploy_const_ref.md`
- Main compilation flow: `02_compile_core.md`
- Cascading-recompilation case: `docs/task/2026-03/recompile_cascade_bug_analysis.md`
