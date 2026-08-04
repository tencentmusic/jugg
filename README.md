# Jugg

## Network and diagnostics privacy

The standard `buildPlugin` artifact contains no predefined Jugg backend configuration and keeps local compile, deploy, CLI, and MCP workflows offline by default. A user-configured Custom Server remains supported. Issue reports are created from a redacted whitelist, show the exact destination and file list before upload, and can be saved locally without sending a request.

Life is short, Jugg it! 

人生苦短，Jugg 一下。


[演示视频](https://www.bilibili.com/video/BV1W3411C7PU/)

Jugg is an Android incremental deploy plugin running on Android Studio and Intellij Idea. Jugg has **super-fast** speed on deploying your changed code and assets, without restart App in most situations.

Jugg 是一个基于 Android Studio 的 Android 增量部署插件，也支持 Intellij Idea。它可以以极快的速度将你的代码和资源更新到正在运行的 App 中。因为使用了 JVMTI（ARTTI）接口，改动甚至不需要重启 App。

Jugg 跳过了 gradle 构建，这意味着 gradle 相关的能力，如注解，插桩等能力都无法生效。但 Jugg 也因此获得了极快的部署速度（单文件编译 1-5s），且部署速度与你的工程体量不再挂钩。

Jugg 不需要侵入你的工程代码，配置完成后只需要点击 run 即可使用；

Jugg 也不会带来消极的体验。在增量部署策略失败的时候，会有健全的降级 gradle 编译的流程。你可以随时使用和停用 Jugg 的增量部署功能。

## Download / 下载

- [Latest stable release / 最新稳定版](https://github.com/sickworm/jugg/releases/latest)
- [Latest nightly build / 最新 Nightly 构建](https://github.com/sickworm/jugg/releases/download/nightly/jugg-nightly.zip)（自动从 `main` 构建，可能不稳定）

# Project Structure
## Modules
* **idea**: Plugin layer
* **main**: Logic layer
* **deploy_compat**: Compatibility layer for Android Studio
* **platform_compat**: API Mock for **main** to invoke **idea** API
* **jvmti_agent**: Agent for JVMTI for deploy compatability
* **aapt2-inclink**: AAPT2 incremental link native libraries
* **custom_compilers**: Build custom compilers for customize project

## Core Classes
* **JuggManager**: Core manager of Jugg
* **JuggCompilerHelper**: Compile process
* **JuggDeployHelper**: Deploy process
* **JuggCompiler**: Compile implementation

# How to Run This Project
```
./gradlew buildPlugin // build plugin, output path: ./idea/build/distributions
./gradlew runIde // run/debug in runtime IDEA
```

# Commit Rules

Use [feature] [optimize] [bugfix] [test] [docs] [other] for commit message head.

# License

Jugg is released under the [MIT License](LICENSE).

Jugg 使用 [MIT License](LICENSE) 开源。
