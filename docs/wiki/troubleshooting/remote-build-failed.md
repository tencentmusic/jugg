---
title: Remote compilation failed
description: Resolve remote project synchronization, Gradle Wrapper, Windows line ending and encoding, APK transfer, and project information refresh problems.
status: active
tags:
  - troubleshooting
  - remote-gradle
---

# Remote compilation failed

Remote compilation includes local preparation, project synchronization, remote Gradle execution, artifact transfer, and local project information refresh. Start with the action for the visible symptom instead of checking each internal stage.

## Q: What should I do when the remote environment reports `/usr/bin/env: sh\r: No such file or directory`?

This means that Linux read the Unix `gradlew` file with CRLF line endings.

1. Confirm that the remote command uses `gradlew`, not `gradlew.bat`.
2. Convert the `gradlew` file actually synchronized to the remote environment to LF.
3. Synchronize the project again and run the remote build.

When remote compilation runs from a Windows machine, Jugg attempts to convert the `gradlew` file that will actually be used before synchronization. If the error persists, check whether the command points to a different wrapper.

## Q: What should I do when Gradle Wrapper files are missing remotely?

Confirm that the command directory contains `gradle/wrapper/gradle-wrapper.properties`. When this file exists, Jugg can restore missing `gradlew`, `gradlew.bat`, and wrapper JAR files. Without wrapper properties, first add a complete Wrapper from the project, or use the system Gradle installation already available in the remote environment.

## Q: What should I do when local changes are not uploaded to the remote environment?

1. Confirm that the current project is inside the actual transfer root.
2. Check `Exclude patterns`. It uses rsync patterns, not gitignore rules.
3. Remove overly broad directory exclusion rules, or rewrite the rules for directories that must be synchronized.
4. In multi-project mode, confirm that all related projects are within the synchronization scope.

Jugg always excludes `.gradle` and `build`. Do not rely on these directories being synchronized between the local and remote environments.

## Q: The remote build succeeds, but the APK cannot be found locally

1. Confirm that the compilation command in the Run Configuration generates the target variant.
2. Check whether Output APK pattern matches the actual file name and directory.
3. If you use a custom Gradle build directory, make sure the APK transfer configuration points to that directory.
4. Confirm that synchronization exclusion rules do not block the artifact directory.

## Q: The old classpath or generated source is still used after changing the remote compilation command

Run a full remote build so that Jugg rereads the APK, classpath, and generated source using the new command. If the old result is still used after the build, confirm that the command and build target were saved in the current Run Configuration.

## Q: What should I do when Chinese text in remote output is garbled?

Jugg first reads Windows Gradle output as UTF-8 and tries GBK if the output is invalid. If the text is still garbled, check whether it comes from a script or tool outside Gradle, standardize that tool's output encoding, and retry.

## Q: What should I do when an included build module is missing remotely?

Add the included project to the synchronization scope, then run a full remote build to refresh dependencies and project information. Synchronizing only the main project without transferring the included build cannot produce the correct classpath.

## Related pages

- [Remote Gradle](../guide/remote-gradle.md)
- [Project information refresh and recovery](../concepts/project-info-refresh.md)
- [Cloud development machine setup](../onboarding/agent-setup.md)
