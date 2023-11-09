# Jugg

[演示视频](https://www.bilibili.com/video/BV1W3411C7PU/)

Jugg is an Android incremental deploy plugin running on Android Studio and Intellij Idea. Jugg has **super-fast** speed on deploying your changed code and assets, without restart App in most situations.

Jugg 是一个基于 Android Studio 的 Android 增量部署插件，也支持 Intellij Idea。它可以以极快的速度将你的代码和资源更新到正在运行的 App 中。因为使用了 JVMTI（ARTTI）接口，改动甚至不需要重启 App。

Jugg 跳过了 gradle 构建，这意味着 gradle 相关的能力，如注解，插桩 等能力都无法生效。但 Jugg 也因此获得了极快的部署速度（单文件编译 1-5s），且部署速度与你的工程体量不再挂钩。

Jugg 不需要侵入你的工程代码，配置完成后只需要点击 run 即可使用；

Jugg 也不会带来消极的体验。在增量部署策略失败的时候，会有健全的降级 gradle 编译的流程。你可以随时使用和停用 Jugg 的增量部署功能。
