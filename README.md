<p align="left">
  <strong>English</strong> | <a href="./README.zh-CN.md">简体中文</a>
</p>

# Jugg: Instant Hot Reload & Fast Incremental Compilation for Android Studio

<p align="left">
  <a href="https://github.com/tencentmusic/jugg/releases/latest"><img src="https://img.shields.io/github/v/release/tencentmusic/jugg?label=Release&color=blue" alt="Latest Release"></a>
  <a href="https://github.com/tencentmusic/jugg/stargazers"><img src="https://img.shields.io/github/stars/tencentmusic/jugg?style=flat&color=yellow" alt="GitHub Stars"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-green.svg" alt="License: MIT"></a>
  <a href="https://tencentmusic.github.io/jugg/reference/compatibility"><img src="https://img.shields.io/badge/Android%20Studio-2021%2B-orange.svg" alt="Android Studio Support"></a>
  <a href="https://tencentmusic.github.io/jugg/reference/compatibility"><img src="https://img.shields.io/badge/Gradle-5.4.1%20to%209.2.1-blue.svg" alt="Gradle Support"></a>
  <a href="https://tencentmusic.github.io/jugg/reference/compatibility"><img src="https://img.shields.io/badge/Kotlin-1.3%20to%202.2-purple.svg" alt="Kotlin Support"></a>
  <a href="https://tencentmusic.github.io/jugg/"><img src="https://img.shields.io/badge/Documentation-Wiki-brightgreen.svg" alt="Jugg Documentation"></a>
</p>

> See changes in 3 seconds—even in large Android codebases.

**Life is short, Jugg it!**

**Jugg** is an open-source **Android Studio plugin** developed by the Tencent Music engineering team designed for **fast incremental compilation** and **instant hot reload**. By reusing the latest Gradle build baseline and compiling only the current changes and their transitive impact, Jugg deploys updates to devices and emulators in **under 3 seconds**—freeing developers from lengthy Gradle build waits during daily iterations.

Jugg only requires an IDE plugin; it does **not** modify Gradle scripts or require SDK integration. A Jugg Run Configuration coexists seamlessly with your native App Run Configuration: select Jugg for high-speed incremental compilation and deployment, or switch back to the native App configuration at any time for the standard Android Studio build flow. The two run completely independently. When a project change falls outside the incremental path, Jugg automatically falls back to Gradle and establishes a new baseline.

- [Download the latest stable release](https://github.com/tencentmusic/jugg/releases/latest)
- [Technical overview](https://juejin.cn/post/7680996030843125796)
- [Watch the demo](https://www.bilibili.com/video/BV1W3411C7PU/)
- [Jugg Documentation (Wiki)](https://tencentmusic.github.io/jugg/)
- [Troubleshooting & FAQ](https://tencentmusic.github.io/jugg/troubleshooting/compile-failed)

## Key features

- ⚡ **3-Second Incremental Deployment**: Experience "Code Change -> Device Refresh" in seconds for daily changes, without waiting for repetitive Gradle configuration and task execution.
- 🔥 **Apply Changes (JVMTI) Hot Reload**: Zero-restart hot reloading via JVMTI that updates modified code and resources directly in the running process—gracefully falling back to Hot Fix (app restart without reinstallation) when code swap is inapplicable.
- 🔌 **Zero Intrusion & Seamless Coexistence**: No Gradle script modifications and no SDK dependencies required. Jugg Run Configurations coexist with native Android configurations, allowing one-click toggling at any time.
- 🧩 **Broad Tech-Stack Compatibility**: Out-of-the-box support for Kotlin, Java, Jetpack Compose, KMP (Compose Multiplatform), DataBinding, ViewBinding, Res/Assets, AndroidManifest, and native `.so` libraries.
- 🤖 **AI Agent & CLI Integration**: Built-in MCP server, CLI, and Agent Skills (`jugg-android-dev-loop`) to automate full "Edit -> Compile -> Deploy -> Verify" agentic development loops.

## Community

Join the Jugg WeChat group to share usage experience and troubleshooting tips.

<p align="center">
  <img src="./docs/images/wechat-group.jpg" alt="Jugg WeChat group QR code" width="360">
</p>

## Quick start

1. Download the plugin from [Releases](https://github.com/tencentmusic/jugg/releases/latest), then install it in Android Studio (JetBrains Marketplace listing in progress).
2. Open an Android project and wait for the Jugg Run Configuration to be created automatically.
3. Select the Jugg Run Configuration and click Run. The first Run establishes the Gradle baseline; after that, edit source or resources and Run again for second-level bypass incremental compilation.

> You can switch between Jugg and Android Run Configuration at any time—they run completely independently and do not interfere with each other.

See the Jugg Wiki [Getting started guide](https://tencentmusic.github.io/jugg/onboarding/) for step-by-step instructions.

## How Jugg works

Jugg keeps Gradle as the source of trusted build outputs while skipping unrelated Gradle work for day-to-day changes:

1. **Establish a baseline** from the latest full Gradle build.
2. **Detect changes and impact** across source, resources, dependencies, and class relationships ([deep dive into impact analysis](https://tencentmusic.github.io/jugg/concepts/deploy-data-and-impact)).
3. **Compile only what is needed** with Java, Kotlin, D8, and Jugg's incremental resource toolchain ([incremental compilation architecture](https://tencentmusic.github.io/jugg/concepts/incremental-compile/)).
4. **Deploy safely** through hot reload, hot fix, incremental APK update, or reinstallation ([deployment strategies](https://tencentmusic.github.io/jugg/concepts/deploy-strategy)); fall back to Gradle when required ([Gradle fallback details](https://tencentmusic.github.io/jugg/concepts/gradle-fallback-baseline)).

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

See the Jugg Wiki [capabilities overview](https://tencentmusic.github.io/jugg/capabilities/) and [compatibility reference](https://tencentmusic.github.io/jugg/reference/compatibility) for detailed requirements and behavior boundaries. Other versions that have not been fully verified may have minor functional issues—please file an issue if you encounter any.

## AI Agent Skill and CLI

The `jugg-android-dev-loop` Skill guides AI agents through **edit -> compile -> deploy -> verify**, while the `jugg` CLI exposes the same plugin runtime to terminals and scripts.

Installation: open any project, search for `Install Jugg Skills` in Search Everywhere to install.

```shell
jugg status
jugg compile
jugg deploy
```

See the [CLI guide](https://tencentmusic.github.io/jugg/guide/cli) and [Agent Skills](https://tencentmusic.github.io/jugg/capabilities/tools/agent-skills) for supported clients, commands, and workflow boundaries.

## Proven at scale

Developed since 2021 and released internally in 2023, the same general-purpose implementation was used across WeSing (全民 K 歌), QQ Music, JOOX, WeSing, Kugou Music, Kugou Live, QQ Browser, Yangshipin, and other large Android codebases before becoming open source.

- **10+** large Android codebases
- **800,000+** incremental compilations
- **36,000+** hours of build waiting time saved

## Star history

If Jugg saves your compilation time, please give this repository a ⭐️ to support us and help more Android developers discover it!

<p align="center">
  <a href="https://star-history.com/#tencentmusic/jugg&Date">
    <img src="https://api.star-history.com/svg?repos=tencentmusic/jugg&type=Date" alt="Jugg Star History Chart" width="700">
  </a>
</p>

## Privacy

### 1. Data Collection
By default, this plugin does not collect, track, or transmit any personal data, usage metrics, or source code information.

### 2. Optional Features & Data Transmission
- **Custom Backend Services**: Users can optionally configure custom backend services for remote build caching or management (see [Jugg Backend Guide](https://tencentmusic.github.io/jugg/en/guide/jugg-backend/)). Data transmitted during this process is directed solely to user-defined servers and managed according to the user's infrastructure policies.
- **Manual Log Reporting**: If you encounter issues, you may voluntarily report them and manually upload diagnostic logs (see [Report an Issue](https://tencentmusic.github.io/jugg/en/guide/report-issue)).
  - Logs are uploaded **only** when explicitly triggered by the user.
  - Uploaded diagnostic logs are used exclusively for troubleshooting and debugging purposes.
  - All uploaded log data is automatically deleted after **7 days**.

### 3. Contact & Support
If you have any questions or concerns regarding privacy, please submit an issue on our [GitHub repository](https://github.com/tencentmusic/jugg/issues).

## Security

To report a vulnerability in Jugg, see [SECURITY.md](SECURITY.md). Do not open a public issue.

## Development

- `./gradlew buildPlugin` builds the plugin into `idea/build/distributions`.
- `./gradlew runIde` starts an IDE instance for development and debugging.

See [CONTRIBUTING.md](CONTRIBUTING.md) for how to report issues, set up the project, and send pull requests.

## License

Jugg is open source under the [MIT License](LICENSE).
