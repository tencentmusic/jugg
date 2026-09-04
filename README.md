<p align="left">
  <strong>English</strong> | <a href="./README.zh-CN.md">简体中文</a>
</p>

# Jugg

> See changes in 3 seconds—even in large Android codebases.

**Life is short, Jugg it!**

Jugg is an open-source Android Studio plugin from the Tencent Music engineering team. It reuses the latest Gradle build, compiles only the current changes and their impact, and deploys the result to a device—small changes can typically become visible within 3 seconds.

Jugg only requires an IDE plugin; it does not modify Gradle scripts or require SDK integration. A Jugg Run Configuration coexists with the native App Run Configuration: select Jugg for incremental compilation and deployment, or switch back to the native App configuration at any time for the original Android Studio build flow. The two run completely independently. When a project change falls outside the incremental path, Jugg itself falls back to Gradle and establishes a new baseline.

- [Download the latest stable release](https://github.com/tencentmusic/jugg/releases/latest)
- [Jugg Wiki](https://tencentmusic.github.io/jugg/)
- [Watch the demo](https://www.bilibili.com/video/BV1W3411C7PU/)

## Quick start

1. Install the plugin from [Releases](https://github.com/tencentmusic/jugg/releases/latest).
2. Open an Android project and create or select a Jugg Run Configuration.
3. Run once to establish the Gradle baseline, then edit code or resources and Run again.

See [Getting started](https://tencentmusic.github.io/jugg/onboarding/) for the complete guide.

## How Jugg works

Jugg keeps Gradle as the source of trusted build outputs while skipping unrelated Gradle work for day-to-day changes:

1. **Establish a baseline** from the latest full Gradle build.
2. **Detect changes and impact** across source, resources, dependencies, and class relationships.
3. **Compile only what is needed** with Java, Kotlin, D8, and Jugg's incremental resource toolchain.
4. **Deploy safely** through hot reload, hot fix, incremental APK update, or reinstallation; fall back to Gradle when required.

## Capabilities and compatibility

### Supported capabilities

| Area | Supported capabilities |
|---|---|
| Source and resources | Java, Kotlin, mixed Java/Kotlin, Compose, KMP, Compose Multiplatform, `res`, assets, Manifest, native `.so` files |
| Android projects | DataBinding, ViewBinding, supported annotation processors, incremental dependency compilation, incremental builds for release variants, AabResGuard, custom compilers |
| Deployment | Hot reload, hot fix, incremental APKs, multiple APKs, multiple devices, Dynamic Feature, compatibility deployment, and failure recovery |
| Android Test | Application / Library Android Test, Test Results UI, and Logcat attribution |
| Automation | Jugg CLI, MCP, Agent Skills, build and deployment, device and runtime queries, UI automation, and remote diagnostics |

### Verified compatibility

| Environment | Supported range |
|---|---|
| Android Studio | 2021 (Bumblebee) to present |
| IntelliJ IDEA | 2021.1.3 to 2025.1 Beta |
| Android Gradle Plugin | 3.4 to 9.1 |
| Gradle | 5.4.1 to 9.2.1 |
| Kotlin | 1.3 to 2.2 |
| Android | 8 to 16 |

See the [compatibility reference](https://tencentmusic.github.io/jugg/reference/compatibility) for requirements and behavior boundaries. Other versions may also work but have not been fully verified.

## AI Agent Skill and CLI

The `jugg-android-dev-loop` Skill guides AI agents through **edit -> compile -> deploy -> verify**, while the `jugg` CLI exposes the same plugin runtime to terminals and scripts. With the target project open, run `Install Jugg Skills` from Search Everywhere to install them.

```shell
jugg status
jugg compile
jugg deploy
```

See the [CLI guide](https://tencentmusic.github.io/jugg/guide/cli) and [Agent Skills](https://tencentmusic.github.io/jugg/capabilities/tools/agent-skills) for supported clients, commands, and workflow boundaries.

## Proven at scale

Developed since 2021 and released internally in 2023, the same general-purpose implementation was used across WeSing, QQ Music, JOOX, Kugou Music, Kugou Live, QQ Browser, Yangshipin, and other large Android codebases before becoming open source.

- **10+** large Android codebases
- **800,000+** incremental compilations
- **36,000+** hours of build waiting time saved

## Privacy

The standard plugin runs offline by default, and Jugg no longer collects project usage statistics. Issue reports include only explicitly listed, privacy-redacted diagnostic files, show the destination before upload, and can be saved locally instead.

## Development

- `./gradlew buildPlugin` builds the plugin into `idea/build/distributions`.
- `./gradlew runIde` starts an IDE instance for development and debugging.

## License

Jugg is open source under the [MIT License](LICENSE).
