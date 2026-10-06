# Compilation System: Incremental Manifest Merge

> Last verified: 2026-10-07
> Consistency rule: If documentation conflicts with code, code takes precedence.

## 1. Scope

This page explains how a changed Android manifest becomes an APK-scoped overlay, which edits the incremental patch can represent, and how to interpret an unchanged or failed result. Resource linking is covered in `02_compile_resource.md`.

## 2. Core Source Index

| Owner | Path | Responsibility |
|---|---|---|
| `AndroidManifestCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/manifest/AndroidManifestCompiler.kt` | Chooses the APK's merged baseline, resolves the changed file's old counterpart and placeholders, and persists a successful patch |
| `ManifestDiffer` and `ManifestNodeMatcher` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/manifest/ManifestDiffer.kt` | Compare old/new manifest nodes by relative identity and record additions or changed attributes |
| `AndroidManifestMerger` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/manifest/AndroidManifestMerger.kt` | Applies the diff to the final merged baseline; does not rerun Gradle ManifestMerger2 |
| `ResourceOverlayCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/ResourceOverlayCompiler.kt` | Joins manifest output with resource linking and suppresses an unchanged root manifest overlay |

## 3. Baselines and Output

| Input or state | Meaning |
|---|---|
| Final merged manifest | Prefer Jugg's prior `tempModule/res/AndroidManifest.xml`; otherwise use the application module's last Gradle merged manifest. Patching this APK-level result retains variant, source-set, and dependency contributions. Missing baseline is a compile failure. |
| Old counterpart of a changed manifest | For a Gradle module, `getLastBuildAndroidManifest()` supplies the relative old file, with the module's merged manifest as a warned fallback. For a library held in the temporary module, use `oldManifest`; an unchanged CRC skips it. This counterpart is for calculating the local diff, not the APK baseline. |
| Placeholder context | Application, dynamic-feature, and test manifests use the target APK `applicationId`. A library keeps an explicitly configured `applicationId` placeholder; otherwise it gets the target APK value. A known Gradle namespace supplies the package context for relative `android:name`. |
| Successful changed result | `AndroidManifestMerger` writes the patched XML; `AndroidManifestCompiler` copies it to `tempModule/res/AndroidManifest.xml` for the next increment and emits an APK-scoped `Res` output for resource linking. An empty diff emits no manifest output. |

`ModuleBuildPathInfo.mergedManifest` selects the newest existing candidate among AGP merged-manifest locations. A later increment may use Jugg's copied result instead, so inspecting only Gradle's file can misidentify the active baseline.

## 4. Call Chain and Patch Boundary

```text
ResourceOverlayCompiler.doApkCompile()
  → AndroidManifestCompiler.doApkCompile(): select Jugg/Gradle baseline and old counterparts
      → AndroidManifestMerger.merge(): apply representable changes to the final baseline
          → ManifestDiffer.diff(): expand placeholders and relative names, record additions/updates
    changed result: persist Jugg baseline; return APK-scoped manifest for AAPT2 link
  → ResourceOverlayCompiler.filterResources(): omit an unchanged root manifest from link output
```

The final merged manifest does not retain enough context to replay `tools:replace`, source-set priority, and other full-merge directives. The incremental path therefore adds new declarations and updates supported attributes only. It does not infer ownership of a declaration already present in the APK baseline.

Deleted nodes and attributes produce no patch. `tools:node="remove"` also produces no removal, and other `tools:*` attributes are excluded. These edits alone can return success with no output while the previous APK declaration remains; a full Gradle merge is required for removal semantics. This is a supported boundary, not proof that the changed input was ignored.

The patch also excludes root `package`, root version attributes, `<uses-sdk>`, and updates to application `android:name`. These identities can be contributed or controlled outside the changed source manifest. A requested change to one of them must be checked against the Gradle merged result instead of assuming the incremental overlay applied it.

## 5. Diagnostic Boundaries

| Observation | What it establishes | Next discriminating evidence |
|---|---|---|
| No `AndroidManifest.xml` overlay after a manifest edit | No deployable patch was emitted; it does not by itself mean the file was never examined. | Compare CRC, old/new diff, ignored attributes or directives, and `ResourceOverlayCompiler.filterResources()` only if a root output existed. |
| A removed declaration remains registered | The incremental patch did not delete it; its origin in the merged baseline is unknown from this result. | Run a full Gradle merge and inspect its merged manifest before attributing ownership. |
| `Compile AndroidManifest.xml failed` | The manifest stage failed; the wrapper message does not identify whether baseline selection, XML parsing, diffing, or writing failed. | Inspect the logged exception and selected baseline path; check the raw XML and old counterpart. |
| AAPT2 link fails after manifest compilation | Link rejected combined inputs; a successful manifest patch alone does not prove the new XML is valid for the full resource table. | Inspect AAPT2 diagnostics and the patched manifest emitted by `AndroidManifestCompiler`. |

## 6. Related Documents

- Resource compilation: `02_compile_resource.md`
- Core compilation: `02_compile_core.md`
- Verification policy: `06_testing.md`
