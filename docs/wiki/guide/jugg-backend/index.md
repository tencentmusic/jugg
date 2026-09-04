---
title: Jugg backend
description: Understand the backend features supported by the Jugg plugin and the integration boundaries for a self-hosted backend.
status: active
tags:
  - guide
  - backend
---

# Jugg backend

Jugg can connect to an HTTP backend for centralized plugin upgrades, hot updates, project defaults, usage events, and remote-machine applications. A backend is not required to run Jugg. Local features such as incremental compilation, deployment, CLI, and MCP remain available without one. User-submitted issue logs do not go through this backend.

Public builds do not include a predefined `servers.json`, so they do not connect to a Jugg backend automatically. Internal teams can use `buildPluginInternal` to package local configuration into the plugin. A Custom Server explicitly configured by the user still takes effect when there is no built-in configuration.

These pages are for teams that need to self-host a backend. They explain which backend features the plugin currently supports, which interfaces form a minimal implementation, and which features are optional enhancements.

## Feature overview

| Feature | Purpose | Required when self-hosting |
|---|---|---|
| Update check | Tell the plugin whether a new full plugin package is available and optionally display a notification | Recommended |
| Project configuration distribution | Distribute server lists, compilation rules, module configuration, and custom compilers by project | As needed |
| Event reporting | Record results of compilation, deployment, update checks, and other actions | Optional |
| Hot update | Download JAR-level plugin updates and request restart or reinstallation when necessary | Optional |
| Custom compiler distribution | Distribute a team's custom compiler JAR to specified projects | As needed |
| Remote-machine application | Guide users through applying for a remote build machine in the IDE and fill in the configuration | Optional; usually depends on an internal platform |

## Plugin-side call model

```text
Android Studio starts or the user checks for updates
  -> Jugg asks the backend for version and project configuration
  -> The backend returns a notification, upgrade entry point, and customConfigJson
  -> The plugin applies project defaults

Everyday compilation and deployment
  -> The plugin reports events as needed

Hot-update check
  -> The backend returns the target version, update notes, and a list of JAR files
  -> The plugin downloads missing files and verifies their md5 values
  -> The result tells the user to continue, restart the IDE, or reinstall
```

## Recommended reading order

1. [Self-hosting checklist](./self-hosting.md): Implement the minimum viable backend first.
2. [Project configuration distribution](./project-config.md): Maintain Jugg defaults by project.
3. [Plugin distribution and hot updates](./plugin-delivery.md): Provide full plugin package downloads or hot updates.
4. [Diagnostics reporting](./diagnostics.md): Collect usage events.
5. [Remote-machine application](./remote-server-apply.md): Integrate with an internal cloud development machine application flow.

## Related pages

- [Remote Gradle](../remote-gradle.md)
- [Custom compiler](../custom-compiler.md)
- [Log files](../../reference/log-files.md)
- [Configuration](../../reference/configuration.md)
