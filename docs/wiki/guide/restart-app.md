---
title: Restart the app
description: Learn when only the target app needs a restart and how to restart it from the IDE, CLI, or deployment settings.
status: active
tags:
  - guide
  - restart
---

# Restart the app

Some changes are already deployed to the device, but state in the old process is not reinitialized automatically. In this case, restart the app without recompiling.

## When to restart

Restart the app when:

- You changed the startup flow, login-state initialization, or route initialization.
- You changed a singleton cache, static member, companion object, or Kotlin top-level declaration.
- The run reports Hot Reload, but the screen still behaves like the old implementation.
- You just delivered resources or an agent and want to verify the new process state.

If the run already used Hot Fix, Clean Reinstall, or Debug, Jugg usually restarts the app automatically.

## How to restart

Use Jugg's Restart action in Android Studio. From a terminal or an agent, run:

```bash
jugg restart
```

Restart affects only the app associated with the current Jugg Run Configuration. It does not recompile or clear app data.

## Restart after every deployment

If your current work repeatedly involves startup-state issues, enable this option in More Options:

```text
Always restart app after deployment
```

This setting restarts the app after every successful deployment. Turn it off after confirming the issue, or you will lose the advantage of Hot Reload preserving the current screen state.

## Related pages

- [Run an app](./run.md)
- [Clear app data](./clean-data.md)
- [Advanced options](./advanced-options.md)
- [Deployment strategies](../concepts/deploy-strategy.md)
- [Restart](../capabilities/deploy/restart.md)
