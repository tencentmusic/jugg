---
title: Deployment data and impact analysis
description: Explains how incremental compilation artifacts are classified as online classes, Hot Fixes, overlays, or APK updates, and how structural changes trigger another compilation round.
status: active
tags:
  - concept
  - deploy
  - impact
---

# Deployment data and impact analysis

After incremental compilation produces DEX, resources, Manifest, and native libraries, Jugg still cannot send the changed files to the device as-is. It first determines whether unchanged callers remain compatible with the new class structure, then classifies the final artifacts by how they take effect on the device.

This step connects incremental compilation and incremental deployment: impact analysis determines whether more source code must be compiled, while deployment data determines whether the current Run uses Apply Changes, Hot Fix, APK update, or compatibility deployment.

## Class structure changes return the flow to compilation

If class A deletes a method or changes a field signature, recompiling only A leaves class B in the old APK calling the old signature. The current compilation can succeed, but B still throws `NoSuchMethodError` or `NoSuchFieldError` when it reaches the stale call.

```text
new class for A is generated
  -> compare it with the old class in the APK baseline
  -> query old callers, field accessors, and subclasses
  -> add matching source files to the next compilation round
  -> generate final deployment data after no new affected source remains
```

See [recompilation](./incremental-compile/recompile-propagation.md) for propagation rules involving methods, fields, inheritance, and generic structures. Compile-time constants have no ordinary runtime reference after inlining, so [constant reference analysis](./incremental-compile/const-ref.md) finds their users separately. This page does not repeat those two compilation mechanisms.

## Final artifacts are classified by how they take effect

After impact propagation finishes, Jugg divides deployable content into several categories:

| Deployment data | Basis for classification | How it takes effect |
|---|---|---|
| Class that can be changed online | Old and new class structures remain compatible | Enters Apply Changes as a modified class |
| New class | Does not exist in the APK or deployment history | Enters Apply Changes as a new class |
| Class requiring Hot Fix | Structural change, library DEX, multi-dex, or another online replacement boundary | Loaded after the app restarts |
| Resource and assets overlay | Result of incremental resource compilation | Apply Changes or Direct Overlay |
| APK update file | Manifest, associated resource table, or an already generated native library | Written back to the corresponding APK, then re-signed and installed |

One source change can produce multiple categories of data. For example, if a resource referenced by the Manifest changes, the Manifest and associated resource table enter the APK update, while ordinary resources and classes can still be delivered as overlays after installation.

## The first resource deployment needs a complete overlay

When a device receives a resource overlay for the first time, the files changed in the current Run are insufficient to form a complete new resource view. Jugg supplements the resource set from the APK baseline and current artifacts, then generates the first full resource overlay.

After a successful deployment establishes resource history, later runs can send changes relative to the previous state. A deployment failure does not commit this history early, so the next Run can still regenerate the complete result instead of assuming the device already has resources that never deployed successfully.

## Multi-APK handling only filters transfer data

Base, split, and test APKs can contain `resources.arsc`, DEX, or overlay paths with the same names. Each deployment item records its actual target APK, and transfer data is filtered by applicationId and APK ownership immediately before each send.

The filtered result is used only for one transfer to the current APK. Deployment history must be committed from the original data for the entire Run after every target completes, not from the partial result for one APK. Otherwise, the next Run may incorrectly assume that changes for other APKs were deployed as well.

## How classification determines the deployment result

```text
final deployment data
  -> APK update files exist: update and install the APK first
  -> classes requiring Hot Fix exist: restart the app
  -> only ordinary classes / overlays: apply changes and recreate the Activity
  -> device requires compatibility deployment: convert to artifacts loaded after restart
  -> device is not ready but state can be verified: transfer with Direct Overlay
```

Classification determines how artifacts should take effect, but it cannot bypass device state validation. If local history, deployment cache, and the device overlay ID do not match, Jugg performs recovery first and uses the current data only after state recovery completes.

## History is committed only after deployment succeeds

Impact analysis and deployment classification produce staged results for the current Run. The new class structures, file history, and overlay ID become the next baseline only after compilation, target APK updates, device transfer, and lifecycle actions all succeed.

If deployment fails partway or the user cancels it, staged results are not presented as effective. The next Run still calculates impact and deployment data from the last successful state.

## Related pages

- [Incremental compilation](./incremental-compile/)
- [Recompilation](./incremental-compile/recompile-propagation.md)
- [Constant reference analysis](./incremental-compile/const-ref.md)
- [Incremental deployment overview](./deploy-strategy.md)
- [Classes and overlays in Apply Changes](./apply-changes.md)
- [APK update and installation](./apk-update-and-install.md)
- [Deployment state and recovery](./deploy-state-recover.md)
