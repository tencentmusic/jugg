---
title: Jugg Backend Remote Server Apply
description: Optional backend-assisted remote build machine provisioning for Remote Gradle.
status: active
tags:
  - guide
  - backend
  - remote
---

# Jugg Backend Remote Server Apply

The backend can provide an interactive IDE flow that provisions a remote build machine and returns connection settings to Jugg. This capability is optional and usually depends on an internal cloud workstation or remote build platform.

## When It Fits

Use it when:

- Your team already has a scriptable remote machine platform.
- Provisioned machines can be reached over SSH.
- The backend can return IP, port, account, sync mode, and remote path.
- You want users to finish provisioning from the IDE instead of copying settings manually.

Avoid it when a fixed Remote Gradle configuration is enough, or when the external platform requires manual approval that cannot be represented as a step-based flow.

## Flow Model

```text
User starts an apply flow in the IDE
  -> Backend returns steps and the first page
  -> User logs in, selects a build plan, and initializes the machine
  -> Backend checks external platform state
  -> Backend returns remoteServerInfo
  -> Plugin writes Remote Gradle settings locally
```

`remoteServerInfo` contains the remote connection and sync settings that Jugg needs for Remote Gradle.

## Related Pages

- [Remote Gradle](../remote-gradle.md)
- [Self-hosting Checklist](./self-hosting.md)
- [Project Configuration](./project-config.md)
