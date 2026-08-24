---
title: Jugg JVMTI Agent
description: Explains why Jugg uses its own JVMTI Agent alongside Apply Changes, how it detects JVMTI, corrects known runtime problems, and triggers compatibility deployment.
status: active
tags:
  - concept
  - deploy
  - runtime
---

# Jugg JVMTI Agent

Android Studio Apply Changes relies on a JVMTI Agent to replace implementations of structurally unchanged classes in a running app. Jugg currently reuses the Apply Changes hot-reload channel directly: Apply Changes Agent still applies online-replaceable classes to the current process through JVMTI. Jugg's startup agent only adds JVMTI availability detection and corrections for known runtime problems, then passes the result to deployment so it can decide whether to switch to compatibility deployment.

## Why Apply Changes still needs the Jugg Agent

Jugg could merge these functions directly into Apply Changes Agent, but that would require it to take over agent distribution, online class replacement, deployment communication, and adaptation across Android Studio and device versions—in other words, the entire deployment path.

Jugg instead adds an independent startup agent that handles only two supplementary responsibilities: detecting whether the current app process can obtain JVMTI and installing corrective hooks for known runtime problems. This preserves Android Studio's deployment implementation while letting Jugg control compatibility detection and fallback decisions.

## Detecting JVMTI and correcting known problems

After the system loads the Jugg startup agent, it first attempts to obtain JVMTI and JNI and records the result as available or unavailable. The state remains undetermined while the app has not started or no result has been produced.

After obtaining JVMTI, the Agent installs hooks on Framework entry points such as Application, Resources, and ClassLoader, changing behavior only when a known problem is detected:

- If some systems initialize the ClassLoader early and delivered incremental DEX does not enter the load path, the app runtime adds the corresponding DEX during startup.
- If Apply Changes carries the host app resource overlay into a non-host resource environment such as a WebView provider, provider initialization can trigger `IllegalStateException: Already registered a list of actions in this process`. The Agent hook removes the host overlay from those resource environments while preserving the resource update for the host app itself.
- If Android 15 combined with an older Android Studio does not fully refresh resources and the Activity, the Agent hook supplies the resource update notification and required Activity recreation.

These corrections are independent. If a Framework class does not exist on one system version or one hook fails to install, other available corrections still run. See [In-app Jugg Runtime](./jugg-runtime.md) for complete DEX, resource, and Application handling.

## Why the Agent is pushed after deployment and checked after restart

When Apply Changes prepares its own startup agent for the first time, it may remove an existing startup agent from the app. If Jugg pushes its agent earlier, a later deployment action can delete it. Jugg therefore completes Apply Changes or another incremental deployment first, then checks and supplies its own Agent.

JVMTI detection must wait for an app restart. The system loads the startup agent when the process starts; recreating the Activity or waiting in the old process does not trigger this initialization. If the current Run does not restart, detection remains pending until the next app process launch.

## How the result changes the deployment path

After the app restarts and produces a detection result, the Jugg deployment flow proceeds according to actual state:

```text
app starts and loads the Jugg startup agent
  -> detect JVMTI
  -> available: continue using the ordinary online replacement path
  -> unavailable: record the current app/device combination
  -> retry compatibility deployment with the current incremental artifacts
```

Automatic detection records apply to an app/device combination. Another app on the same device does not enter compatibility deployment because of this record. A compatibility mode enabled manually by the user applies to the device instead. The two choices have different scopes.

## Related pages

- [Classes and overlays in Apply Changes](./apply-changes.md)
- [Compatibility deployment](./compat-deploy.md)
- [In-app Jugg Runtime](./jugg-runtime.md)
- [JVMTI Runtime](../capabilities/deploy/jvmti-runtime.md)
- [Hot Reload](../capabilities/deploy/hot-reload.md)
