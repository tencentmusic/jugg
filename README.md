<p align="left">
  <strong>English</strong> | <a href="./README.zh-CN.md">简体中文</a>
</p>

# Jugg

> See changes in 3 seconds—even in large Android codebases.

**Life is short, Jugg it!**

Jugg is an open-source incremental build and deployment tool from the Tencent Music engineering team, designed to bring changes in large Android codebases on screen within seconds. Delivered as an Android Studio / IntelliJ IDEA plugin, it reuses trusted outputs from the latest Gradle build, compiles only the current changes and the code affected by them, and quickly deploys code and resources to a device. Small day-to-day changes can typically become visible within 3 seconds.

Jugg only requires an IDE plugin. It does not modify Gradle scripts or require an SDK integration. A Jugg Run Configuration coexists with the native App Run Configuration: select Jugg for incremental compilation and deployment, or switch back to the native App configuration at any time to use the original Android Studio / Gradle build, installation, and launch flow. Jugg does not take over that native Run. Select the Jugg configuration again to resume the incremental flow; when a project change falls outside the incremental path, Jugg itself can also fall back to Gradle and establish a new baseline.

- [Download the latest stable release](https://github.com/tencentmusic/jugg/releases/latest)
- [Jugg Wiki](https://tencentmusic.github.io/jugg/)
- [Watch the demo](https://www.bilibili.com/video/BV1W3411C7PU/)

## Community

Join the Jugg WeChat group to discuss usage and troubleshooting.

<p align="center">
  <img src="./docs/images/wechat-group.jpg" alt="Jugg WeChat group QR code" width="360">
</p>

## How Jugg works

Gradle and AGP produce complete, trusted Android build outputs, but the fixed cost of Gradle startup, configuration, and task orchestration does not shrink with the size of a change. In a large codebase, changing a single line of code or one resource file can still require a long wait before the result becomes visible.

Jugg does not replace Gradle. After a full build, it reuses the generated APKs, classes, dependencies, and generated sources to establish an incremental compilation baseline. Subsequent runs skip Gradle work unrelated to the current change and directly perform change detection, impact analysis, incremental compilation, and device deployment. Changes to build scripts, dependencies, or other baseline inputs cause Jugg to return to the Gradle path when necessary.

Development of Jugg began in 2021, and it was released internally at Tencent Music in 2023. Before becoming open source, it was used in large Android codebases including WeSing, QQ Music, JOOX, Kugou Music, Kugou Live, QQ Browser, and Yangshipin. Every project used the same general-purpose implementation without business-specific customization.

Validation data collected before the open-source release:

- **10+** large Android codebases
- **800,000+** incremental compilations
- **36,000+** hours of build waiting time saved

> Jugg no longer collects project usage statistics after becoming open source.

## What happens during a Jugg Run

1. **Reuse the baseline**: Read the APKs, classes, dependencies, and project information produced by the latest full Gradle build.
2. **Detect changes**: Combine IDE file events with Git status to identify the source, resource, and project files that actually changed.
3. **Compile incrementally**: Invoke Java, Kotlin, D8, and Jugg's customized AAPT2 directly to produce only the required incremental outputs.
4. **Propagate impact**: Analyze calls, inheritance, inlined constants, and method signatures to automatically recompile affected source files.
5. **Deploy changes**: Select hot reload, hot fix, incremental APK update, or reinstallation according to the change type and device state.
6. **Save state**: After a successful deployment, save incremental outputs and change records as the starting point for the next Run.

Jugg is fast because it processes only the necessary inputs. Impact propagation, Gradle fallback, and deployment state recovery keep the result reliable.

## Capabilities

| Area | Supported capabilities |
|---|---|
| Source and resources | Java, Kotlin, mixed Java/Kotlin, Compose, KMP, Compose Multiplatform, `res`, assets, Manifest, native `.so` files |
| Android projects | DataBinding, ViewBinding, supported annotation processors, incremental dependency compilation, incremental builds for release variants, AabResGuard, custom compilers |
| Deployment | Hot reload, hot fix, incremental APKs, multiple APKs, multiple devices, Dynamic Feature, compatibility deployment, and failure recovery |
| Android Test | Application / Library Android Test, Test Results UI, and Logcat attribution |
| Automation | Jugg CLI, MCP, Agent Skills, build and deployment, device and runtime queries, UI automation, and remote diagnostics |

See the [compatibility reference](https://tencentmusic.github.io/jugg/reference/compatibility) for detailed requirements and behavior boundaries.

## Verified compatibility

| Environment | Supported range |
|---|---|
| Android Studio | 2021 (Bumblebee) to present |
| IntelliJ IDEA | 2021.1.3 to 2025.1 Beta |
| Android Gradle Plugin | 3.4 to 9.1 |
| Gradle | 5.4.1 to 9.2.1 |
| Kotlin | 1.3 to 2.2 |
| Android | 8 to 16 |

Versions outside these ranges may still work, but they can contain compatibility differences that have not yet been covered. Please open an [Issue](https://github.com/tencentmusic/jugg/issues) when you encounter a reproducible problem.

## Quick start

1. Download and install the plugin from [Releases](https://github.com/tencentmusic/jugg/releases/latest).
2. Open an existing Android project and create or select a Jugg Run Configuration.
3. The first Run uses Gradle to establish a trusted baseline. After that, modify source code or resources and click Run again to see the incremental result.

See [Getting started](https://tencentmusic.github.io/jugg/onboarding/) for the complete setup guide.

## AI Agent Skill and CLI

Jugg provides the `jugg-android-dev-loop` Agent Skill and the `jugg` CLI so AI coding assistants, terminal users, and scripts can use the same build, deployment, testing, runtime inspection, and UI automation capabilities as the IDE plugin. The Skill guides an agent through the complete **edit -> incremental compile -> deploy -> verify -> iterate** workflow, while the CLI wraps the local Jugg MCP service with project discovery, asynchronous task polling, and stable terminal output.

The installer currently supports Codex, Claude Code, Gemini, CodeBuddy, and Cursor:

1. Open the Android project in Android Studio and initialize Jugg.
2. Open Search Everywhere and run `Install Jugg Skills`, or select **Jugg panel -> More Options -> Tools -> Install Jugg Skills**.
3. Select the Agent Skills, CLI, and optional hooks to install.

Common commands:

```shell
jugg status
jugg compile
jugg deploy
jugg instrument --source-path app/src/androidTest/java/com/example/FooTest.kt
jugg layout-dump
```

The target project must remain open and initialized in the IDE because the CLI calls the local Jugg plugin runtime. See the [CLI guide](https://tencentmusic.github.io/jugg/guide/cli) and [Agent Skills](https://tencentmusic.github.io/jugg/capabilities/tools/agent-skills) for installation details, command modes, and workflow boundaries.

## Network and diagnostic privacy

The standard `buildPlugin` artifact contains no predefined Jugg backend configuration and runs offline by default. Users can still configure a Custom Server.

Issue reports collect only privacy-redacted diagnostic files from an explicit allowlist. Before upload, Jugg shows the exact destination and file list. Reports can also be saved locally without sending a network request.

## Project structure

| Module | Responsibility |
|---|---|
| `idea` | IDE plugin entry points, Run Configurations, task orchestration, and UI |
| `main` | Core incremental compilation, deployment, project model, Gradle, and MCP logic |
| `deploy_compat` | Deployment API compatibility across Android Studio versions |
| `platform_compat` | Platform API compatibility stubs used to compile the core logic |
| `jvmti_agent` | JVMTI Agent and app runtime capabilities |
| `aapt2-inclink` | AAPT2 incremental linking tool resources |
| `custom_compilers` | Custom compiler examples |

## Build the project

```shell
# Build the plugin. Output: idea/build/distributions
./gradlew buildPlugin

# Start an IDE instance for development and debugging
./gradlew runIde
```

## License

Jugg is open source under the [MIT License](LICENSE).
