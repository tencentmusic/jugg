---
title: Jugg Backend
description: Backend capabilities that Jugg can use for updates, configuration, diagnostics, and remote server provisioning.
status: active
tags:
  - guide
  - backend
---

# Jugg Backend

Jugg can connect to an HTTP backend for plugin updates, hot updates, project defaults, diagnostics, and optional remote server provisioning. The backend is not required for local compile, deploy, CLI, or MCP usage.

Public builds do not include a predefined `servers.json` and therefore do not connect to a Jugg backend automatically. Teams can use `buildPluginInternal` to package a local configuration. An explicitly configured Custom Server still works without embedded configuration.

These pages are for teams that want to self-host the backend surface. They describe the user-visible capabilities and integration boundaries, not server internals.

## Capabilities

| Capability | Purpose | Required when self-hosting |
|---|---|---|
| Update check | Tell the plugin whether a full plugin package is available and optionally show a notification | Recommended |
| Project configuration | Return project-specific server rules, compile rules, module settings, and custom compilers | As needed |
| Event reporting | Store compile, deploy, update check, and other action results | Optional |
| Issue log upload | Receive zipped Jugg logs when users submit issues | Recommended |
| Hot update | Deliver jar-level plugin updates with md5 checks | Optional |
| Custom compiler delivery | Host team-specific custom compiler jars | As needed |
| Remote server apply | Guide users through remote build machine provisioning from the IDE | Optional, usually platform-specific |

## Recommended Reading

1. [Self-hosting Checklist](./self-hosting.md)
2. [Project Configuration](./project-config.md)
3. [Plugin Delivery](./plugin-delivery.md)
4. [Diagnostics](./diagnostics.md)
5. [Remote Server Apply](./remote-server-apply.md)

## Related Pages

- [Remote Gradle](../remote-gradle.md)
- [Custom Compiler](../custom-compiler.md)
- [Log Files](../../reference/log-files.md)
- [Configuration](../../reference/configuration.md)
