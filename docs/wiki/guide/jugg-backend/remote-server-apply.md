---
title: Jugg backend remote-machine application
description: Integrate the optional remote build-machine application flow and understand the external platform requirements for self-hosting.
status: active
tags:
  - guide
  - backend
  - remote
---

# Jugg backend remote-machine application

A Jugg backend can provide an interactive flow that guides users through applying for a remote build machine in the IDE and returns the connection information to the plugin. This feature primarily supports Remote Gradle and usually depends on an existing team platform for cloud development machines, account authentication, and synchronization tools.

## Integration requirements

Remote-machine application is suitable for an internal platform that already supports automation. Before integration, confirm that:

- The team has an internal cloud development machine or remote build-host platform.
- Remote machines are accessible through SSH.
- A completed application returns the IP, port, account, synchronization method, and remote directory.
- Users need to complete the application inside the IDE instead of copying fixed configuration manually.

Current limitations:

- There is no unified remote-machine platform.
- The remote environment requires complex manual approval that cannot expose a completion state through an interface.
- Accounts, passwords, or tokens cannot pass through the Jugg backend.
- Only a fixed remote-machine configuration is required; configure it directly in Jugg Remote Gradle instead.

## Interaction model

```text
The user starts an application in the IDE
  -> The backend returns a step list and the first step
  -> The user signs in, selects a compilation plan, and initializes the machine
  -> The backend polls the external platform state
  -> The backend returns remoteServerInfo after success
  -> The plugin writes Remote Gradle configuration to the local project
```

`remoteServerInfo` represents the remote connection information needed by the plugin, including the SSH address, port, account, proxy, synchronization mode, and remote synchronization directory.

## Self-hosting prerequisites

- A scriptable or API-driven remote-machine application flow.
- The ability to identify login, initialization, application failure, and timeout states.
- The ability to convert remote connection information into configuration usable by Jugg Remote Gradle.
- Concurrency and isolation for multiple users applying at the same time.
- Cleanup of temporary state after failure or user cancellation.

## Recommendations

- Treat remote-machine application as an optional enhancement, not a prerequisite for local Jugg use.
- Show users only the steps they must perform; do not expose backend command details.
- Do not store plaintext passwords, private keys, or one-time tokens in the backend for long periods.
- For teams using Windows development machines, verify synchronization tools and path rules in advance.

## Related pages

- [Remote Gradle](../remote-gradle.md)
- [Self-hosting checklist](./self-hosting.md)
- [Project configuration distribution](./project-config.md)
