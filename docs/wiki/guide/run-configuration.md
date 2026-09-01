---
title: Run configurations and build variants
description: Learn how Jugg Run Configurations are created, how Build Variant synchronization works, how CLI/MCP selects a configuration, and the limits of custom commands.
status: active
tags:
  - guide
  - run-configuration
  - variant
---

# Run configurations and build variants

A Jugg Run Configuration determines which app, Gradle command, APK output, build target, and remote environment a run uses. In projects with multiple apps, variants, or custom Gradle arguments, selecting the wrong configuration can compile successfully but deploy the wrong artifact.

## Identical configuration names do not imply identical build targets

One module can have debug, release, flavor, and Android Test targets at the same time. Jugg creates configurations from runnable targets reported by Android Studio and distinguishes those targets by their actual Gradle tasks.

Common names include:

\`\`\`text
jugg:app
jugg:app:debug
jugg:app:paidRelease
\`\`\`

If an older configuration still shows \`Unnamed...\`, Jugg creates a readable replacement after rediscovering the project. Custom Gradle arguments in existing configurations are preserved whenever possible and are not reset merely because the Build Variant changed.

## Follow the Active Build Variant

When the Active Build Variant changes in Android Studio, Jugg looks for the corresponding new build target in the same module.

Jugg switches the selection automatically only when the currently selected configuration is itself a Jugg configuration. If a native App, test, or another configuration is selected, Jugg does not override the user's choice.

\`\`\`text
Change the Android Studio Build Variant
  -> Reload runnable targets
  -> Create Jugg configurations for missing targets
  -> If a Jugg configuration is selected, switch to the new variant in the same module
\`\`\`

The first run after switching usually requires a Gradle build because the APK, classpath, mapping, and project information belong to a new baseline.

## Which configuration CLI and MCP use

CLI/MCP does not store a separate set of build arguments. It selects a configuration in this order:

1. The Jugg configuration currently selected in Android Studio.
2. A configuration matching the most recent full-build command and BuildTarget.
3. A configuration matching the most recent full-build command.
4. The first available Jugg configuration, with a fallback-selection message in the logs.

Therefore, in a multi-app or multi-variant project, select the target Jugg configuration in Android Studio before running \`jugg deploy\`.

## Custom Gradle commands and outputs

When editing a configuration manually, \`Compile command\` and \`Output APK name\` must describe the same build target. Jugg recognizes the Gradle task in the command and allows common additional arguments. If the task or BuildTarget changes, Jugg requires a new full-build baseline.

Custom Gradle build directories are also supported. APK, Kotlin/Java output, Manifest, mapping, Android Test artifacts, and remote synchronization are resolved from the actual build directory and do not have to reside under the module's \`build/\` directory.

## Common mistakes

| Symptom | Check first |
|---|---|
| CLI deploys the wrong app | The Jugg configuration currently selected in Android Studio |
| The old APK is still used after switching variants | Whether you selected the configuration for the new variant and completed one Gradle build |
| Duplicate configurations are generated | Whether the two commands actually point to different Gradle tasks |
| Custom arguments disappeared | Whether the configuration was deleted and recreated instead of being updated by normal variant synchronization |
| APK cannot be found | Whether \`Compile command\` and \`Output APK name\` refer to the same artifact |

## Related pages

- [Run an app](./run.md)
- [Jugg Control Panel](./control-panel.md)
- [Project information refresh and recovery](../concepts/project-info-refresh.md)
- [Run context and no-change results](../capabilities/tools/run-context-and-no-change.md)
