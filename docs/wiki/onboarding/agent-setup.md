---
title: Remote build machine setup
description: Configure a remote build machine, synchronization mode, account, directories, and proxy for Jugg remote compilation.
status: active
tags:
  - onboarding
  - remote
---

# Remote build machine setup

Configuring a remote build machine is optional. It requires access to remote build capacity or a cloud build environment provided by your team. After configuration, local Android Studio remains the entry point and handles deployment, while Gradle builds can run on the remote machine.

Jugg remembers the parameters from the last successful remote build. A newly opened project inherits this configuration. Later changes made within one project are not automatically synchronized to other projects that are already open.

## Request a remote build machine

If you do not have a remote build machine, first check whether your team provides a standard Jugg cloud-build template. The template usually includes Java, the Android SDK, Git, and other basic tools, with the Gradle home directory placed on a high-capacity disk. A machine created from this template can normally be used for compilation immediately.

For example, you can request a machine with 32 CPU cores, 64 GB of memory, and a 500 GB disk. Disk capacity can usually be expanded later. Expanding CPU or memory may require a restart, and data stored outside `/data` may not be preserved.

After the machine is ready, use the following values in the remote build configuration:

- Enter the machine hostname or IP address in `SSH host`.
- Machines created from a team template usually authenticate with an SSH key, so the password can remain empty.
- If your team uses rotating passwords, Jugg prompts for one at runtime. A successful login keeps the session active until the project closes or the configuration changes.

## Enable remote compilation

Open the remote compilation settings in the Jugg Run Configuration, then select a synchronization mode. The synchronization mode controls how local source files are uploaded to the remote build machine and how remote build artifacts are downloaded to the local machine.

## Select a synchronization mode

| Mode | Best for | Description |
|---|---|---|
| `rsync_simple` | A single project on a local macOS or Linux machine | Uses an SSH channel, synchronizes only the current project, and requires the least configuration. This is the recommended default |
| `rsync` | Multiple projects that must be synchronized together on a local macOS or Linux machine | Uses an SSH channel and supports multiple projects sharing one remote source root |
| `iFT` | A local Windows machine, or a team that requires iFT | Requires iFT on both the local and remote machines and supports multi-project synchronization |

Windows does not support the rsync modes and must use iFT. On macOS or Linux, prefer `rsync_simple`; if your team already uses iFT, you can continue using it.

## Synchronize multiple projects

If the workspace contains multiple dependent projects, enable multi-project development mode. Before each build, Jugg then synchronizes every project under `Local to remote sync path`.

For a single project, prefer `rsync_simple`; multi-project synchronization is unnecessary.

## Configure the account and host

| Parameter | What to enter |
|---|---|
| `SSH user` | The login account, commonly `root`; if your team provides personal accounts, enter your enterprise ID |
| `SSH password/key(optional)` | The login password or SSH key. Leave it empty when an SSH key is already configured so Jugg can search the `.ssh` directory automatically |
| `SSH host` | The remote build machine IP address or hostname |
| `SSH port` | The SSH port; Tencent Cloud servers commonly use `36000` |

If the password is empty and key-based login fails, Jugg prompts for a password at runtime.

## Configure synchronization directories

The required directories vary by synchronization mode.

### rsync_simple

| Parameter | Meaning |
|---|---|
| `Remote root directory (optional)` | The source root on the remote build machine; the default is `$HOME/remote` |

If the remote machine already uses a fixed synchronization directory that differs from the default, enter it here to avoid synchronizing to a duplicate location.

### rsync

| Parameter | Meaning |
|---|---|
| `Local to remote sync path` | The local source root. For multi-project synchronization, use the common parent directory of all projects |
| `Remote root directory (optional)` | The source root on the remote build machine; the default is `$HOME/remote` |
| `Remote to local sync path` | The local root for downloaded remote build artifacts; multiple projects can share it |

### iFT

Open the iFT settings page first, enter the Pin and Token, and review the local and remote synchronization configurations. Then enter the following values:

| Parameter | Meaning |
|---|---|
| `Local To remote IFT config name` | The iFT configuration that synchronizes local source files to the remote build machine |
| `Local to remote sync path` | The local source root |
| `Remote root directory (optional)` | The source root on the remote build machine; the default is `$HOME/remote` |
| `Remote to local IFT config name` | The iFT configuration that synchronizes remote build artifacts back to the local machine |
| `Remote to local sync path` | The local root for downloaded build artifacts |

## Configure a proxy

If iOA proxy access is required, enter:

| Parameter | Value |
|---|---|
| `HTTP proxy host` | `127.0.0.1` |
| `HTTP proxy port` | `12639` |

Leave both values empty when no proxy is required.

## Troubleshooting

| Error or symptom | Check first |
|---|---|
| `Run configuration argument xxxx is empty / is invalid` | A required setting is missing or invalid; review the configuration on this page |
| `Login to remote ssh failed. Please check your login info.` | Account, password, IP address, port, proxy, and internal-network login state |
| `Local to remote IFT sync path must be the parent of project path` | The current project is not under the configured local synchronization root |
| `Sync file from local to remote failed` | Whether iFT is running, the password is correct, and the network is available |
| `Fetch output from remote to local failed` | Whether the iFT client is working and the remote artifact was produced |
| `find apk name with pattern ... failed` | Whether `Output APK name` matches `Remote to local sync path` |
| `set cmd failed: get volume failed: dir-config not found` | Whether the iFT synchronization configuration name is correct |
| `No space left on device` | The remote build machine disk is full and must be cleaned or expanded |

If disk expansion introduces a new disk path, update `Remote root directory` to the new path. Otherwise, Jugg continues synchronizing to the old disk.

## Next steps

- [First run](./first-run.md)
- [Remote Gradle](../guide/remote-gradle.md)
- [Log files](../reference/log-files.md)
