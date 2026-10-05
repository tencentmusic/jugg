# Compilation System: Incremental Manifest Merge

> Last verified: 2026-08-10
> Consistency rule: If documentation conflicts with code, code takes precedence.

---

## 1. Purpose of This Document

This page covers only incremental AndroidManifest merging: from a changed manifest to an APK-scoped `AndroidManifest.xml` overlay.

For resource flat/link details, see `02_compile_resource.md`; for source-to-dex order, see `02_compile_source.md`; for release obfuscation mapping, see `02_compile_obfuscation.md`.

---

## 2. Core Source Index

| Class/interface | File | Role |
|---|---|---|
| `AndroidManifestCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/manifest/AndroidManifestCompiler.kt` | Manifest compilation entry point; reads the baseline merged manifest by APK ownership, fills placeholders, and emits a deployable manifest overlay |
| `AndroidManifestMerger` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/manifest/AndroidManifestMerger.kt` | Applies a changed diff to an already merged manifest; does not rerun standard ManifestMerger2 |
| `ManifestDiffer` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/manifest/ManifestDiffer.kt` | Compares original and changed manifests, producing nodes/attributes to patch into the merged manifest |
| `ManifestNodeMatcher` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/manifest/ManifestDiffer.kt` | Finds relative nodes in the merged-manifest subtree and decides between adding a node and recursive updating |

---

## 3. Core Data Flow

| Data | Producer | Consumer | Key constraint |
|---|---|---|---|
| Baseline merged manifest | Last Gradle build output, or `tempModule/res/AndroidManifest.xml` written by Jugg in the previous run | `AndroidManifestCompiler` | Jugg patches the final merged manifest so raw manifest inputs cannot discard variant-merge results |
| `ChangedManifestFile` | `AndroidManifestCompiler` | `ManifestDiffer` | An application manifest uses the target APK's `applicationId`. For a library, preserve an explicitly configured same-name placeholder; otherwise fill in the target APK value. Add `JUGG_NAMESPACE_IN_GRADLE` when a namespace exists |
| Manifest diff element | `ManifestDiffer` | `AndroidManifestMerger` | Carries only new nodes and new/updated attributes; deleted nodes/attributes and `tools:node="remove"` do not enter the patch |

---

## 4. Core Call Chain

```text
ResourceOverlayCompiler.doApkCompile()
  -> call AndroidManifestCompiler.doApkCompile() for an APK-scoped task
  -> choose baseline manifest: prefer Jugg's previous merged manifest, otherwise the application module's merged manifest
  -> fill applicationId / namespace placeholders for the changed manifest from the target APK and find the previous-build relative manifest for a library
  -> ManifestDiffer.diff() extracts only truly new/changed nodes
  -> AndroidManifestMerger.merge() patches the diff into the baseline merged manifest
  -> on success, write back tempModule/res/AndroidManifest.xml and emit CompileOutput.Type.Res bound to apkPath
```

The essential point is “patch the merged manifest,” not rerun the full Gradle manifest merge. Standard `ManifestMerger2` needs the complete placeholders, variant/flavor manifests, dependency manifests, and merge-feature context. An incremental run cannot guarantee that these inputs match the last Gradle build exactly. Jugg therefore treats the last final merged manifest as a stable baseline and applies only local changes it can recover deterministically.

Manifest patching is deliberately conservative. `ManifestDiffer` traverses only nodes and attributes present in the new manifest. New nodes or changed attributes enter `DiffElement.changedChildren/changedAttributes`, while nodes or attributes present only in the old manifest create no delete operation. `tools:node="remove"` becomes no patch, and other `tools:*` attributes do not enter the final merged manifest. These deletions or full-merge directives alone do not fail incremental compilation or trigger automatic fallback. The installed APK retains its prior merged-manifest content. Use a full Gradle merge to refresh the baseline only when removal must really take effect.

---

## 5. Hidden Constraints / Design Rationale / Known Boundaries

- Empty Manifest output is valid: an unchanged library manifest or an empty diff emits no `AndroidManifest.xml`, avoiding meaningless APK repackaging.
- `AndroidManifestCompiler` copies a successful merged result back to `tempModule/res/AndroidManifest.xml`; later manifest increments prefer this file as baseline, so do not inspect only Gradle's merged manifest.
- `ModuleBuildPathInfo.mergedManifest` chooses the newest `AndroidManifest.xml` among `merged_manifests` / `merged_manifest` candidates so an old path cannot shadow a new AGP output after an upgrade.
- A library manifest is CRC-compared with `oldManifest` first; unchanged input is skipped to avoid repatching dependency-library manifests.
- Manifest merge ignores `tools:*` attributes, manifest `package`, and updates to application `android:name`. This prevents an incremental patch from overwriting critical runtime identity; it is not an accidental omission.
- Deleted nodes, deleted attributes, and `tools:node="remove"` are intentionally ignored. The incremental path applies only certain additions/updates, avoiding accidental deletion of declarations contributed by other source sets or dependencies in the final merged manifest.
- Full context for merge directives such as `tools:replace` is gone in the final merged manifest. They cannot be patched as ordinary attributes; use a Gradle fallback when their complete semantics are needed.
- Preserving old declarations can yield development-time false positives, such as an Activity remaining registered in the baseline manifest after its source is deleted. This is a known cost of conservative incremental work, not something to fix by guessing a declaration's origin in the patch layer.

---

## 6. Investigation Entry Points

| Symptom | First entry point |
|---|---|
| Manifest change does not take effect | `AndroidManifestCompiler.doApkCompile()`; check CRC, empty diff, and `filterResources` filtering |
| Declaration remains after deleting a node or using `tools:remove` | Incremental patch does not handle deletion; run a full Gradle build to refresh the merged-manifest baseline |
| Manifest merge overwrites a field that should remain | `AndroidManifestMerger.merge()`; check ignore rules for `tools:*`, `package`, and `android:name` |
| aapt2 link triggers unnecessary repackaging | `ResourceOverlayCompiler.filterResources(...)`; check whether root `AndroidManifest.xml` is emitted despite no manifest change |

---

## 7. Related Documents

- Resource compilation: `02_compile_resource.md`
- Source compilation: `02_compile_source.md`
- Core compilation scheduling: `02_compile_core.md`
- Obfuscation mapping: `02_compile_obfuscation.md`
