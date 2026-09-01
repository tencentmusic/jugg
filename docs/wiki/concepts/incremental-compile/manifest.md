---
title: Android Manifest compilation
description: Explains how Manifest changes are incrementally patched onto a merged manifest, passed into aapt2 link, and turned into a binary artifact that can be written back to the APK.
status: active
tags:
  - concept
  - compile
  - manifest
---

# Android Manifest compilation

Incremental Manifest compilation has two distinct stages. Jugg first applies the changes it can determine in the current Run to the latest merged manifest, then uses the result as the Manifest input for aapt2 link. The first stage produces XML that can continue to be merged; only the second stage produces the binary `AndroidManifest.xml` that can be written back to the APK.

## Where Manifest fits into resource compilation

A complete Android resource build usually compiles files under `res/` into `.flat` files, then uses aapt2 link with Manifest, `android.jar`, and dependency resources to generate the resource table and other APK resource artifacts. Manifest is not compiled into a `.flat` file like a layout or drawable; it participates directly in link through the Manifest argument.

Jugg preserves these input relationships and only replaces the complete resource link with an incremental link based on the current APK resource table:

```text
current Manifest change
  -> generate incrementally merged XML on top of the merged manifest baseline
  -> load the current resource table of the target APK and android.jar
  -> pass them to aapt2 inclink with .flat files for the current resource changes
  -> generate binary AndroidManifest.xml, resources.arsc, and required R.java
  -> filter resource artifacts not needed by the current deployment
  -> write Manifest back to the APK, re-sign, and install the update
```

If only Manifest changed and there are no ordinary resource changes, this path still runs aapt2 link: the `.flat` input can be empty while the incrementally merged XML still enters link through the Manifest argument. Conversely, if only resources changed and Manifest did not, link can still generate resource artifacts, but Jugg filters the root `AndroidManifest.xml` from the final result to avoid unnecessary APK repackaging.

## The merged manifest is the incremental merge baseline

A full Gradle build merges the application and build-variant Manifests, resolves placeholders, and incorporates Manifests from dependency libraries into the final result. The incremental environment does not retain every input required by the standard Manifest merge. Starting again from raw Manifests could lose results provided by the variant, dependency libraries, or build scripts.

Jugg therefore prefers the merged manifest written by the previous incremental Run. If it does not exist, Jugg uses the application merged manifest from the latest Gradle build. It then supplies the `applicationId`, namespace, and Manifest placeholders that can be determined for the current module, compares the new and old Manifests, and applies only added nodes and attribute updates to the baseline:

```text
select the latest merged manifest
  -> supply placeholders that can be determined for the current module
  -> compare the current Manifest with the last build result
  -> apply added nodes and attribute updates
  -> save the new merged manifest for the next Run
```

If library Manifest content did not change, or comparison produces no effective update, the current Run does not output a new Manifest.

## How aapt2 generates a deployable Manifest

The incrementally merged result is still ordinary XML and cannot directly replace the binary Manifest in the APK. Jugg's aapt2 `inclink` first loads the current resource table of the target APK. If `resources.arsc` was incrementally deployed before, it uses the latest resource table and corresponding Manifest as the new loading baseline. A dynamic feature also references the base APK resource result to preserve package IDs and resource references.

After loading, aapt2 link accepts the incrementally merged XML and current `.flat` files, then outputs binary `AndroidManifest.xml`, `resources.arsc`, compiled resource files, and potentially changed `R.java`. Only artifacts actually needed by the current Run enter deployment. A Manifest change triggers APK update and re-signing. If a new `resources.arsc` also exists, both are written back to the APK together.

If aapt2 cannot load the current resource table or link fails, Manifest is not deployed separately by bypassing the resource stage. Resource compilation for the current Run fails as a whole and enters the existing failure containment flow.

## Why deletions do not change the installed Manifest

The incremental stage can apply additions and updates whose source is clear, but it cannot reliably determine whether a declaration should be removed from the final merged manifest. An old declaration may come from another source set or dependency library, and deleting it directly would damage the result already merged by Gradle. The following changes are therefore not fully handled by an incremental patch:

- Deleting a node or attribute.
- Directives that require complete merge context, such as `tools:node="remove"`, `tools:remove`, and `tools:replace`.
- `uses-sdk`, Manifest `package`, `versionCode`, and `versionName`.
- Application `android:name`.

Jugg ignores these deletions and complete-merge directives. It does not generate a deletion patch or fail incremental compilation solely because of them. The device continues to use the original nodes and attributes in the installed APK, and queries through the system or `PackageManager` still see the old content. Run a full Gradle build only when the deletion must take effect, so that Gradle can regenerate the merged manifest and APK baseline.

The absence of a trusted merged manifest is a different condition. In that case, additions and updates also lack a usable baseline, so incremental Manifest compilation fails and asks for a Gradle build to recover.

## Related pages

- [Incremental compilation overview](./index.md)
- [Resource incremental compilation](./resource.md)
- [AndroidManifest compilation capability](../../capabilities/compile/manifest.md)
