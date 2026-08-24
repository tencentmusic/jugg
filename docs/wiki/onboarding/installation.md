---
title: Installation
description: Download and install the Jugg Android Studio plugin, then confirm that the IDE creates a Jugg Run Configuration after restart.
status: active
tags:
  - onboarding
  - installation
---

# Installation

Jugg is installed as an Android Studio plugin. You do not need to change project code or Gradle configuration after installation. Once the IDE restarts and Sync completes, the plugin creates a Jugg Run Configuration for each Android App module.

## Download the plugin

Public builds are available from GitHub:

- [Latest stable release](https://github.com/sickworm/jugg/releases/latest)
- [Latest Canary build from `develop`](https://github.com/sickworm/jugg/releases/download/canary-nightly/jugg-canary-nightly.zip): Built automatically when `develop` receives a new commit and may contain changes that have not been fully verified

If your team provides an internal download page, follow the team's release and staged-rollout policy.

After downloading, confirm that the file is an Android Studio plugin package, usually a `.zip` file.

## Install in Android Studio

1. Open Android Studio.
2. Open `Settings`.
3. Select `Plugins`.
4. Click the gear menu in the upper-right corner.
5. Select `Install Plugin from Disk...`.
6. Select the Jugg plugin package you downloaded.
7. Restart the IDE when prompted.

If the project starts Sync after the restart, wait for Sync to finish. Jugg reads the project modules and Gradle artifact information after Sync, then creates the run configurations.

## Confirm the run configuration

Open the run configuration selector. You should see a configuration similar to:

```text
jugg:app
```

Here, `app` is the Android App module name. A project with multiple App modules receives multiple Jugg configurations; select the module you want to run.

If no Jugg configuration appears, check the following:

| Symptom | What to do |
|---|---|
| The IDE has just restarted and the project is still syncing | Wait for Sync to finish |
| The IDE was not restarted after plugin installation | Restart Android Studio |
| The project has no runnable Android App module | Confirm that a native App Run Configuration exists |
| The configuration is still missing | Reopen the project, preserve the logs, and report the issue |

## Optional: Adjust the compile command

Most projects do not require manual configuration. Jugg reads the Gradle command and APK output information already configured in Android Studio.

If the generated command differs from the command you use for everyday development and debugging, open `Edit Configurations...` and adjust these values:

| Parameter | Meaning |
|---|---|
| `Compile command` | The Gradle command that produces the APK; it should match the current App run configuration |
| `Output APK name` | The APK output path or filename; it should match the output of `Compile command` |

After changing the settings, run a Gradle build once to confirm that the baseline artifact is correct.

## Next steps

- [First run](./first-run.md)
- [Remote build machine setup](./agent-setup.md)
