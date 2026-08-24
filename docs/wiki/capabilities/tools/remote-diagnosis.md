---
title: Remote diagnosis
description: Explains when to use SSH to diagnose remote build environments or device-related problems.
status: active
tags:
  - capability
  - tools
  - diagnosis
---

# Remote diagnosis

Remote diagnosis requests SSH troubleshooting information from the user when local logs and Jugg CLI results are insufficient to locate a problem. It is not the default debugging entry point and is used only when remote build environment or device context is needed.

## Applicable scenarios

| Scenario | Current support | Behavior |
|---|---|---|
| A remote Gradle build fails and CLI logs remain insufficient to identify the cause | Supported | Uses `ssh-info` to request connection information |
| Repeated `compile` / `deploy` repair attempts still fail and `gradle-build` also lacks enough information | Supported | Explains the reason, then requests SSH access |
| Regular local compilation error | Not preferred | Read `detail`, `compile_latest.log`, and status fields first |
| Obtain remote information without user consent | Not supported | `ssh-info` requires explicit user consent |

## Command format

```text
jugg ssh-info --reason "deploy fails after retries and gradle-build detail is insufficient"
```

`reason` must explain why remote information is required. The caller should first collect locally visible evidence such as the command's `detail`, log paths, `status`, device state, and failure stage.

## Agent escalation flow

```text
compile / deploy fails
  -> Read detail and the complete logs
  -> Fix determinable problems and retry
  -> Try gradle-build if it still fails
  -> Remote build or environment information remains insufficient
  -> Call ssh-info after obtaining user consent
```

Remote diagnosis supplements environment evidence that cannot be seen locally. It does not bypass existing Jugg compilation, deployment, and log decisions.

## Using the output

Information returned by `ssh-info` is used by a person or Agent to connect to the remote environment and continue troubleshooting. It does not mean the problem has been fixed and does not replace the terminal result of a build command.

## Related pages

- [Build and deployment](./cli-build-deploy.md)
- [Runtime and devices](./cli-runtime-device.md)
- [Jugg CLI](./cli.md)
