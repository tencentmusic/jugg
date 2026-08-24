---
title: Resource Compile
description: Explains Jugg's incremental handling for res, assets, R files, and resource overlays.
status: active
tags:
  - capability
  - compile
  - resource
---

# Resource Compile

Jugg can incrementally compile Android resource-related files and pass the result to deployment. The resource pipeline covers `res/`, `assets/`, `resources.arsc`, `R.java`, and the handoff required by ViewBinding/DataBinding layouts.

## Supported Capabilities

| Change type | Current support | Result |
|---|---|---|
| Regular `res/` files | Supported | Compiled by aapt2 into `.flat` files, then linked into a deployable resource overlay |
| `res/values` | Supported | Updates the resource table and produces a new `resources.arsc` |
| `assets/` | Supported | Deployed as an overlay to the target APK |
| `AndroidManifest.xml` | Incremental patch supported | Participates in resource link, then takes effect by updating and re-signing the APK; see [Manifest](./manifest.md) |
| ViewBinding/DataBinding layouts | Resource-stage handoff supported | Handles split XML / generated source before resource and source compile; see [DataBinding and ViewBinding](./databinding-viewbinding.md) |
| `R.java` / `R.dex` | Triggered by resource link | Generates and fixes `R.java` after resource table changes, then compiles dex when needed |

> [!TIP]
> If you change Gradle configuration, source sets, variant selection, or resource generation logic, run the matching Gradle build or Sync first so Jugg can continue incrementally from the new build result.

## How Resource Compile Works

### Resource Compile Pipeline

A resource change usually flows through this pipeline:

```text
detect res / Manifest / assets changes
  -> split compile tasks by target APK
  -> process Manifest diff
  -> hand off ViewBinding / DataBinding layouts
  -> run aapt2 compile to produce .flat files
  -> run aapt2 inclink to produce resources.arsc, compiled res, and R.java
  -> filter extra artifacts that should not be deployed
  -> pass resource / asset overlays to deployment, and Manifest-related outputs to APK update and re-sign
  -> pass generated source to source compile
```

Two details matter:

1. **Resources are not copied directly**. Most `res/` files must go through aapt2 compile and link.
2. **Resource compile can trigger source compile**. `R.java` and ViewBinding/DataBinding generated source become Java/Kotlin compile inputs.
3. **Link continues from the latest usable resource table**. This allows multiple resource incremental rounds to build on each other instead of restarting from the original APK resource table every time.

### APK-Scoped Compile

In multi-APK or dynamic feature projects, resources from one module can affect different APKs. Jugg compiles per APK scope instead of copying one output to every APK.

| Scenario | Handling |
|---|---|
| Single APK | Continue incremental link from the current APK resource table |
| Base APK | Update the base resource table first |
| Dynamic feature | Link with the base APK resource table and current base resource changes |
| Multiple APK ownership | Generate separate overlay outputs for each target APK |

> [!NOTE]
> A resource overlay deployed to the wrong APK usually appears as missing resources, abnormal resource IDs, or feature resource inconsistency at runtime.

### Manifest

Manifest participates in the resource pipeline as an aapt2 link input. Jugg does not rerun the full Gradle Manifest merge. It starts from the latest merged manifest produced by a build and patches the current Manifest change into the deploy artifact.

Manifest is not deployed as a normal resource overlay. Deployment writes the updated `AndroidManifest.xml` into the APK and re-signs it. For Manifest support boundaries, placeholder limits, and the full effect path, see [Manifest](./manifest.md).

### Native Library Update

`.so` update is not resource compile, and it is not "so compile". Jugg can update an APK from existing native library artifacts and re-sign it. See [Native Library Update](./so-update.md).

### DataBinding And ViewBinding

When a layout file changes, the resource stage first checks whether ViewBinding/DataBinding processing is required. When needed, Jugg uses split XML for aapt2 compile and sends generated source to source compile.

For generated source, mapper handling, and the source compile handoff, see [DataBinding and ViewBinding](./databinding-viewbinding.md).

### Resource Obfuscation And R Files

In release or resource obfuscation scenarios, Jugg reuses existing mapping information so incremental artifacts keep resource names consistent with the installed APK.

Resource link generates `R.java`. Jugg fixes and compiles it, and may also generate `R.dex` for `R.styleable` or some `R.*` references that cannot be inlined.

## Related Pages

- [Incremental Compile](../../concepts/incremental-compile/)
- [Manifest](./manifest.md)
- [Native Library Update](./so-update.md)
- [DataBinding and ViewBinding](./databinding-viewbinding.md)
- [Compile Guide](../../guide/compile.md)
- [Compilation Failed](../../troubleshooting/compile-failed.md)
- [Limits](../../reference/limits.md)
