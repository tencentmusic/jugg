---
title: Remote Gradle
description: Configure remote Gradle compilation, understand its runtime behavior, and find the first checks for common failures.
status: active
tags:
  - guide
  - gradle
  - remote
---

# Remote Gradle

Remote Gradle moves time-consuming Gradle builds to a cloud development machine or remote build host while local Android Studio continues to handle IDE interaction, Jugg state, and device deployment. It is suitable when the local machine is underpowered, the project is large, or the team already has reusable build machines.

## Prerequisites

Remote Gradle is designed to run full Gradle builds on a cloud development machine or remote build host while the local machine handles IDE interaction and device deployment. Before using it, confirm that:

- Local Gradle build time already has a noticeable effect on development.
- The team has a cloud development machine and an rsync or iFT synchronization environment.
- The remote directory maps to the local project and includes source code, Gradle files, included builds, and required configuration.
- The build host has an Android SDK, JDK, Gradle cache, and access to project dependencies.
- The remote build does not require temporary manual input such as interactive permission confirmation or secondary authentication.

## Configuration

Prepare:

1. A build host accessible over SSH.
2. A remote directory corresponding to the local project.
3. A file synchronization method such as rsync or iFT.
4. The JDK, Android SDK, Gradle cache, and private repository access required for the build.
5. The server IP, account, password or key, and other connection information in Jugg.

As described in the user guide, a basic remote compilation setup can be completed in a few minutes, and the configuration can be reused across projects.

## Control synchronization exclusions

`Exclude patterns` in Remote Compile Options shows the currently configurable exclusion list. The default value is:

```text
local.properties; .idea/; *.iml; .git/objects/; .git/modules/; .cxx/
```

The field uses rsync patterns interpreted relative to the actual transfer root. Separate rules with semicolons; newline-separated and legacy comma-separated input can also be read. After editing, the list in the UI replaces the defaults above: deleting an entry causes the corresponding path to be synchronized, and clearing the field applies none of these configurable exclusions.

Jugg always excludes `.gradle` and `build`; they cannot be removed through this field. Required configuration files under `.gradle/jugg` and `build/jugg` are still synchronized.

> [!WARNING]
> Removing `.git/objects/` or `.git/modules/` includes the entire directory, not only selected files inside it. Large directories can significantly increase upload time and remote disk usage.

The legacy `Additional exclude patterns` field added rules instead of replacing them. Existing values are not converted to the new complete list during upgrade. If those rules are still required, enter them again in `Exclude patterns`.

## What happens during a run

```text
Trigger Jugg Run / Gradle fallback locally
  -> Synchronize local changes to the remote host
  -> Run the Gradle build or read project information remotely
  -> Download the APK, classpath, generated source, and logs
  -> Update the local Jugg compilation context
  -> Continue device deployment locally
```

Remote Gradle does not change Jugg's incremental-compilation decisions. When a full Gradle build, dependency diff, AndroidTest baseline, or project information update is required, only the location of Gradle execution changes from local to remote.

## Synchronize multiple projects

When the development directory contains multiple related projects, common strategies are:

| Approach | When to use it |
|---|---|
| Synchronize every file under the iFT directory | The iFT directory contains only a small number of projects |
| Place the projects to synchronize under one subdirectory | The iFT directory contains many projects, but only one group matters for the current work |

Avoid overly simplified synchronization for multiple projects. Otherwise, included builds, dependency source, or cross-project classpaths may be missing.

## Generated code appears unresolved

After a successful remote Gradle build, the local IDE may still lack remotely generated `BuildConfig`, R files, or other generated source. The remote build succeeds, but local code appears unresolved.

Use Jugg's generated-file download entry point to synchronize generated code back to the local machine. Reload the `build/` directory or reopen the project if necessary.

## Relationship to androidTest

The first time AndroidTest is enabled, an additional build is required to generate the test APK. With Remote Gradle, that baseline is generated remotely and the app APK / test APK / classpath is downloaded. Only then can later `src/androidTest` changes enter Jugg's incremental flow.

When a Library Test APK is first missing, a remote Gradle build also generates the corresponding Test APK.

## Common problems

| Symptom | Action |
|---|---|
| Remote Gradle compilation fails | Check the remote build log, then confirm SDK/JDK/private repository permissions |
| Local code appears unresolved but compiles remotely | Download remote generated code or synchronize project information again |
| A change is not synchronized remotely | Check the synchronization directory, exclusion rules, and current project path |
| An included-build module is missing | Confirm that the related project is inside the synchronization scope |
| Jugg runtime is missing after `gradlew clean` | Use the current Jugg version; runtime files are stored under `.gradle/jugg` instead of `build/` |

## Related pages

- [Compilation stages](./compile.md)
- [Gradle fallback](../capabilities/compile/gradle-fallback.md)
- [Project model](../concepts/project-model.md)
- [Android Test](./android-test.md)
- [Log files](../reference/log-files.md)
