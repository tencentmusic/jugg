---
title: Deployment state and recovery
description: Starting from the requirement that incremental deployment depends on the previous successful state, explains how Jugg uses three consistency checkpoints to decide whether a device is trusted and how it recovers the baseline when state does not match.
status: active
tags:
  - concept
  - deploy
  - recover
---

# Deployment state and recovery

Incremental deployment does not write an overlay to the device without context. It assumes that the device still holds the last successful state. If this assumption no longer holds, directly writing a new overlay can leave the device in an intermediate state that matches neither the old nor the new result.

State recovery handles an easily overlooked prerequisite of incremental deployment: the device, local history, and Apply Changes cache must agree on the same successful result. Jugg first uses multiple checkpoints to determine whether the device is trusted. If it is not, Jugg recovers or reinstalls the baseline instead of accumulating another difference.

## Incremental deployment depends on the previous successful state

Incremental deployment sends only the difference from the previous Run. The device content must therefore equal the content after the previous successful deployment for the new difference to produce the correct result. If another path changes the device, such as reinstallation, system cleanup, or deployment from another project, the difference uses the wrong base and its result becomes unpredictable.

Before each incremental deployment, Jugg therefore verifies that device state is trusted. It bases this decision on three independent consistency checkpoints.

## Three consistency checkpoints

| Checkpoint | Recorded content | Purpose |
|---|---|---|
| Jugg-maintained deployment history | Deployment data and overlay ID sent in the previous Run | Local view of the “last successful state” |
| Apply Changes deployment cache | Deployment snapshot recorded by the Apply Changes channel | Consistency evidence for online replacement |
| Device overlay ID | Overlay ID and files currently effective on the device | Actual state from the device perspective |

These checkpoints come from local state, the Apply Changes channel, and the device and are independent sources of fact. Jugg considers the device to remain at the expected state only when all three match. If any checkpoint differs, such as empty local history while an overlay exists on the device or an unexpected device overlay ID, the current Run treats state as untrusted and enters recovery instead of accumulating another difference.

## How untrusted state is recovered

Recovery realigns the device and local baseline. Jugg does not reinstall immediately. It first performs a probe using a deployment that makes no real application change, verifying whether the device can still accept an overlay on the expected baseline.

```text
state may be untrusted
  -> perform a probe
  -> probe succeeds: device remains on the expected baseline; continue current incremental deployment
  -> probe fails / app is not installed / app was externally updated: reinstall the APK
  -> clear local deployed data and rebuild the baseline after reinstallation
```

A successful probe demonstrates that device state is trusted and avoids reinstallation. If the probe fails or cannot run because the app is absent or externally updated, Jugg reinstalls the APK and clears locally recorded deployed data, resource artifacts, and staged state to establish the baseline again.

> [!NOTE]
> A log such as `Deploy state not match, start reinstalling app...` or the keyword `OVERLAY_ID_MISMATCH` means that checkpoints did not match and Jugg chose reinstallation to recover the baseline. This is usually not an error; Jugg deliberately abandoned one untrusted incremental attempt. No special action is needed—wait for reinstallation to finish and continue.

## State commit order: advance together or not at all

Recovery is reliable only if the “last successful state” itself was recorded correctly. Deployment history can be committed only after the entire deployment succeeds, and all three states must advance together.

```text
compilation succeeds
  -> put changed artifacts into staging without changing history
  -> generate current transfer data from staging artifacts and history
  -> deployment succeeds
  -> advance deployment history, file state, and device overlay ID together
```

The order cannot be reversed, and only part of the state cannot be committed. If history updates before deployment succeeds, or history updates without the overlay ID, the next validation sees contradictory checkpoints and can trigger an unnecessary reinstall or incorrect fallback. A partially committed state is more dangerous than no state.

## Direct Overlay recovery branch

[Direct Overlay](../capabilities/deploy/direct-overlay.md) is a bypass that writes an overlay directly when the device is not ready. When enabled and allowed by the caller, recovery can be lighter: it compares the deployment cache with the device overlay ID and skips launching the app for a complete probe when they match.

This path has a strict retreat condition. Once a Direct Overlay write fails, later recovery disables the bypass and uses the ordinary probe that launches the app. The next delivery also avoids Direct Overlay. A failed write may have left the device overlay directory partially committed, so another bypass validation would reason from an untrusted state.

## Recovery costs and constraints

Recovery can restore trusted state, but it has costs and hard constraints. The direct cost is reinstallation: when the probe fails, Jugg must reinstall and rebuild the baseline, sacrificing incremental speed for trusted later state. A partially committed state must be cleared before it reaches the next Run. If earlier slices of a sliced deployment succeeded and a later slice failed, Jugg must clear overlays already written to the device before returning the failure. Otherwise, the next Run would reason from a partial state. For the same reason, after a Direct Overlay write failure, the current Run and later recovery return to the ordinary path and stop using the bypass.

One constraint applies throughout: filtered data must not update global state. Deployment data sent to one APK is temporary data filtered for that target and can be used only for that transfer. Global deployment history can be committed only from the original data for the complete Run after success; otherwise, partial data would corrupt the global baseline.

## Related pages

- [Clean data](../guide/clean-data.md)
- [Clean Reinstall capability](../capabilities/deploy/clean-reinstall.md)
- [Deployment history and cache](../capabilities/deploy/deploy-history-cache.md)
- [Deployment strategy](./deploy-strategy.md)
- [Deployment data and impact analysis](./deploy-data-and-impact.md)
- [Classes and overlays in Apply Changes](./apply-changes.md)
- [Direct Overlay deployment](./direct-overlay.md)
- [Direct Overlay capability](../capabilities/deploy/direct-overlay.md)
- [Recover and Retry capability](../capabilities/deploy/recover-and-retry.md)
