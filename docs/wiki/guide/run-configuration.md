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

Gradle module names can contain dots, such as \`:zxphone5.0\`. When Jugg generates the \`Compile command\`, it uses the Gradle project path reported by Android Studio directly: dots inside a name remain unchanged, nested modules continue to use colons, and composite builds retain the included build name. As a result, \`:zxphone5.0\`, \`:feature:app\`, and \`:SMCommon:app\` each produce the assemble task for the corresponding module without reconstructing the path from the Jugg configuration name.

## Where configurations come from

Jugg discovers configurations from the ordinary Android Run Configurations reported by Android Studio. When the project opens, existing Jugg configurations take effect immediately; when none exist, Jugg creates them from the runnable targets Android Studio reports and never guesses targets from Gradle project information at startup.

Creation is deduplicated by Gradle task:

- A standard \`assembleVariant\` and the same task with extra arguments such as \`--offline\` describe one target and are not created twice.
- Custom tasks such as \`deployVariant\` or \`uploadVariant\` are not the standard \`assembleVariant\`, so the standard configuration and the custom one may coexist.
- Multi-task commands and unsupported formats are compared by the exact command only; Jugg does not infer containment.
- Existing \`Compile command\`, \`Output APK name\`, and remote fields are never overwritten.
- On a name clash Jugg uses a unique name, and the name shown in the IDE matches the name saved in the shared configuration.

Jugg only creates a target that parses exactly as a single \`./gradlew :modulePath:assembleVariant\` whose variant matches what Android Studio reports; unparsable targets are skipped instead of receiving a fabricated identity. Missing App modules in the Gradle project information neither invalidate existing configurations nor block creation of standard targets.

## Follow the Active Build Variant

When the Active Build Variant changes in Android Studio, Jugg looks for the corresponding new build target in the same module.

Jugg switches the selection automatically only when the currently selected configuration is itself a Jugg configuration. If a native App, test, or another configuration is selected, Jugg does not override the user's choice.

\`\`\`text
Change the Android Studio Build Variant
  -> Reload runnable targets
  -> Create Jugg configurations for missing targets
  -> If a Jugg configuration is selected, switch to the new variant in the same module
\`\`\`

When the target variant is already owned by a custom target, such as \`deployDebug\`, Jugg keeps the current selection: even a standard configuration created moments earlier does not take over the user's selection or the shared configuration pointer. Select the target configuration manually when you want to switch.

The first run after switching usually requires a Gradle build because the APK, classpath, mapping, and project information belong to a new baseline.

## Switch between Jugg and native Run

Jugg configurations do not replace or rewrite native App Run Configurations. To stop using the Jugg run flow, select the native App configuration directly in Android Studio. The native configuration remains responsible for compilation, installation, and launch, and Jugg does not take over that Run. Select the corresponding Jugg configuration again when you want to resume incremental compilation.

A native Run may update local build outputs or replace the APK installed on the device. After you switch back, Jugg checks the Gradle baseline and device deployment state again. If they no longer match, the next Jugg Run performs a Gradle build, installation, or state recovery as required by those checks. This only realigns Jugg's incremental starting point; it does not modify the native Run Configuration or project configuration.

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
