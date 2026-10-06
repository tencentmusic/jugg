# Deployment Data and Class-Effect Analysis

> Last verified: 2026-10-07
> Consistency rule: If documentation conflicts with code, code takes precedence.

## 1. Scope

This page follows compiled outputs into `JuggDeployData`: class deployment category, affected source, release/minify compensation, resource completion, and APK-baseline indexing. Device execution and recovery belong to `03_deploy_core.md`; constant-reference indexing belongs to `03_deploy_const_ref.md`.

## 2. Core Source Index

| Owner | Location | Responsibility |
|---|---|---|
| `DeployDataGenerator` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/DeployDataGenerator.kt` | Classifies changed Dex against history, gathers affected sources, and assembles deploy data |
| `DeployDataDatabase` / `DeployDataDatabaseSqLiteHelper` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/` | APK and incremental-deploy reference indexes; callers, subclasses, generic effects, and commit |
| `ClassNodeComparator` / `DexFileNodeCollector` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/` | Old/new class structure and DEX reference/signature facts |
| `EffectedClassNode` / `InlineMethodDetector` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/` | Source versus release bytecode compensation and R8-inlined callers |
| `ApkParserProcessLauncher` / `ApkParserProcess` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/data/` | Builds APK-baseline SQLite in a separate JVM when available |
| `CompileEffectAnalyzer` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/CompileEffectAnalyzer.kt` | Recompile effect query; passes original changed paths to ConstRef only in the first round |

## 3. Deploy Data and Commit Boundary

```text
Compile outputs -> DeployItem -> DeployDataGenerator.buildDeployData()
  -> parse changed Dex and look up old class nodes from deployed history, then APK baseline
  -> ClassNodeComparator: classify each changed class and extract reference-impact signals
  -> DeployDataDatabase: query direct callers and subclass/generic propagation
  -> release/minify: add R8 removal and inline callers requiring bytecode compensation
  -> ConstRefEffectProvider: independently query original changed source paths
  -> assemble classes, affected nodes, overlays, APK-update files, and target APKs
successful downstream deployment -> DeployFileManager.commit()
  -> DeployDataGenerator.commitDeployedData() updates history for the next comparison
```

`buildDeployData()` is a proposal, not committed state. The incremental-deployment index takes precedence over the APK SQLite baseline when comparing a second or later incremental change; otherwise the next run would compare against stale classes and references. Failed deployment must not advance the database or consume pending constant-definition changes.

| `JuggDeployData` output | Meaning |
|---|---|
| `newClasses` | Absent from old APK/deployed history; loaded from a new-class overlay, not JVMTI redefinition |
| `hotReloadModifiedClasses` | Existing class with redefinable structure; sent as `modifiedClasses` to the deployer |
| `hotFixModifiedClasses` | Existing class with changed structure, multi-Dex, or library-Dex ownership; transported with new classes and loaded after restart |
| `effectedSourceAndClassNodes` | Source recompilation or bytecode compensation discovered from reference/mapping analysis |
| `constRefEffectedSourcePaths` | Independent source impact from inlined constant values |
| `overlays` / `isFullRes` | Resource/asset changes; first resource deployment completes resource entries from the baseline |
| `updateApkFiles` | Manifest plus paired `resources.arsc` when present, and native libraries; requires APK rewriting path |

Hot reload and hot fix may share an overlay channel, but their runtime semantics differ. The device deployer can further choose compatible push-only behavior or retry definite JVMTI redefinition failures as hot fix; see `03_deploy_core.md`. A plain overlay is not an APK-update file. `isNeedCheckRecompile=false` omits both structural impact and ConstRef queries, as used when planning deploy data without another recompilation pass.

## 4. Class-Effect Propagation

`ClassNodeComparator` turns old/new declarations into four signals: affected method references, deleted or generic-changed field references, hierarchy/new-abstract effects, and changed class-level generic signature. Member identity remains erased owner/name/descriptor, while old/new generic signatures are compared separately. This preserves DEX-reference matching and still recompiles a direct caller whose generic source constraints changed. A method-body-only change does not create a method-reference effect. `R$...` subclasses skip method/field propagation because generated R repair can remove many fields and would otherwise recompile unrelated users.

```text
DeployDataDatabaseSqLiteHelper.getEffectedClassNodes()
  -> resolve changed owners to historical class IDs
  -> traverse subclasses for non-static changed virtual methods, retaining all methods for direct callers
  -> query method_refs / field_refs for direct callers
  -> recurse subclasses for new abstract methods or changed hierarchy
  -> propagate class-level generic changes through declarations and direct member callers
  -> resolve affected IDs to source paths as EffectedClassNode(SOURCE)
```

The static-method filter applies *only* to the initial subclass traversal. Static methods must still reach direct-reference lookup; a legacy method with `MISS_ACCESS` also remains eligible for traversal. Letting a changed `$r8$lambda$` or other static method seed subclass traversal can cascade recompilation through an entire hierarchy. An overridden virtual method stops inherited-method propagation along that branch. Changed class-level generics cover subclass declarations and direct member callers; a source-only generic constraint with no DEX member reference is outside this index's evidence. Member-level signature changes go to the direct method/field query, not whole-class generic propagation. `DexFileNodeCollector` must retain `dalvik.annotation.Signature` on members for this distinction.

For minified builds, `getEffectedClassNodesForMinify()` marks incremental references to R8/ProGuard-removed classes or members as `MINIFY_MEMBER_REMOVED`; `InlineMethodDetector` marks callers with previously inlined copies as `INLINE_IMPL_CHANGE`. Both lead to `DexMinifyCompiler` bytecode compensation. `SOURCE` wins when an inline result merges with an already affected source, because recompilation is stronger than bytecode-only compensation. Recursive affected-source compilation skips a new inline round and does not resend original paths to ConstRef.

## 5. APK Baseline and Resource Boundaries

The APK index stores class structure and generic signatures, member references, hierarchy, source mapping, and Dex/resource checksums. These facts serve hot reload classification, effect queries, resource completion, and the next APK update. Ordinary APK parsing runs in a child JVM (current isolation threshold: 0 MB) so large parsing heaps do not remain in the IDE. If launch, classpath, or execution fails, it warns and parses in-process. A failed child process therefore does not by itself mean the APK index is missing.

APK `lastModified` is a fast precheck; entry checksums decide what changed. A small Dex change updates only changed entries, while more than three changed Dex entries or at least 20% of existing Dex entries rebuild that application's baseline. The threshold is a performance policy, not a deployment decision. SQLite is scoped by applicationId, so multiple APKs of one app share one helper; obsolete applicationId databases are cleaned only after the new initialization round completes.

`addFullRes()` fills resource entries on warm-up or the first resource overlay deployment. A small changed-resource list can therefore legitimately yield a full resource overlay. The Manifest's paired `resources.arsc` enters `updateApkFiles` only when that output is present.

D8's separate installed-baseline preparation crosses this effect-analysis layer: `ClassPreparation` carries the parsed program classes through transformation, then `DeployDataGenerator.getDesugarInfo()` finds baseline default interfaces and `CompileEffectAnalyzer` completes their external superclass chains by reading headers. Omitting an inherited superclass implementation can make D8 synthesize a bridge to an interface default method and bypass the baseline override. Classes already in the program set are not recopied, boot-classpath types are excluded, and a generated `Hilt_*` superclass found by transformation enters the required classpath. See `02_compile_source.md` for minApi and core-library rewriting; class-effect SQL here does not own desugaring.

## 6. Diagnostic Boundaries

| Observation | What it establishes | Next evidence |
|---|---|---|
| Small edit yields extensive `effectedSource` | The reference index propagated impact; the initiating edge remains unknown | Changed method access, Step 2 subclass seed, `$r8$lambda$`, then direct references |
| Direct caller keeps old generic behavior | An unchanged DEX descriptor does not rule out changed source generics | `DexFileNodeCollector` member Signature, comparator output, Step 3 method/field references |
| Class generic change yields no affected source | The SQL result cannot prove no source generic dependency | Class-level signature signal, subclass chain, direct DEX member references; source-only constraints may be invisible |
| Release change uses old inlined logic or references removed member | Ordinary source propagation may be insufficient | Mapping, `InlineMethodDetector`, minify-removal query, and `EffectedType` |
| `Isolated process parsing failed` | Child JVM failed; in-process fallback may still populate the index | `ApkParserProcessLauncher` classpath/Java home, child output, fallback completion |
| New constant value does not recompile callers | Structural `effectedSource` does not cover inlined constants | `constRefEffectedSourcePaths` and `03_deploy_const_ref.md` |

## 7. Related Documents

- Deployment execution and recovery: `03_deploy_core.md`, `03_deploy_complete.md`
- Constant-reference analysis: `03_deploy_const_ref.md`
- Source/D8 baseline: `02_compile_source.md`
- Verification ownership: `06_testing.md`
