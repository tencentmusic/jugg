---
title: assets 与 native lib
description: 解释 Gradle 与 Jugg 如何处理 assets 和 native lib，以及两类增量产物为何采用不同的生效方式。
status: active
tags:
  - concept
  - compile
  - assets
  - native
---

# assets 与 native lib

Android APK 不只包含经过 aapt2 编译的资源。`assets/` 会按原有目录结构进入 APK，native lib 则以按 ABI 划分的 `.so` 文件进入 APK。Jugg 复用最近一次 Gradle 构建的 APK；普通 asset 和已有 `.so` 直接组织为增量产物，Dart/C/C++ 变化则先运行范围明确的 Gradle task，再复用同一套部署方式。

## Gradle 如何把文件放进 APK

完整 Android 构建会根据输入类型生成不同的 APK 内容：

| 输入 | 标准构建过程 | APK 中的产物 |
|---|---|---|
| `res/` | aapt2 编译并链接资源 | 编译后资源和 `resources.arsc` |
| `assets/` | AGP 合并 asset 目录并参与 APK 打包 | `assets/**` |
| C/C++ 源码或预编译 `.so` | Gradle/NDK 生成或收集各 ABI 的动态库，并参与 APK 打包 | `lib/<abi>/*.so` |
| Flutter Dart 源码 | Flutter Gradle 插件生成 `flutter_assets`，Profile/Release 还可能生成 `app.so` 或 native assets | `assets/flutter_assets/**` 和 `lib/<abi>/*.so` |

`assets/` 不会像 `res/` 一样生成资源 ID，也不进入 `resources.arsc`。native lib 是已经编译完成的二进制文件，同样不属于 Android 资源表。完整构建仍会收集这些文件，并把它们放到 APK 约定的路径中。

## Jugg 如何组织本轮增量产物

Jugg 以 Gradle APK 为基线，检测变化文件后保留它们在 APK 中的相对路径：

```text
assets 变化文件
  -> 保留 assets 下的相对路径
  -> 生成归属于目标 APK 的 asset 增量产物

已经生成的 .so 变化
  -> 保留 ABI 和 lib 下的相对路径
  -> 生成归属于目标 APK 的 native lib 增量产物

Dart 变化
  -> 每次执行当前变体的 Flutter native 输出 task；该 task 同时依赖 Flutter compile task
  -> flutter_assets 转为 asset；该 task 自己声明的 native 输出（归档或目录）中的 native lib 转为 native lib

C/C++ 变化
  -> 执行当前变体的 native build/merge task
  -> 新 .so 转为 native lib 增量产物
```

这个过程不执行 aapt2，也不会生成 `resources.arsc`。Dart 变化始终执行 Flutter 编译和 native 输出 task，不增加 Jugg 侧 Flutter 缓存；Flutter assets 从当前输出目录读取，native lib 只接受该任务自身声明的 native 输出中结构明确的 ABI 条目，不会递归扫描 Flutter 中间目录。C/C++ 到 `.so` 的转换仍由 Gradle、CMake 和 NDK 完成。Jugg 只选择当前变体所需的外部 task，并收集它们的新输出，因此 Android Java/Kotlin 和资源部分仍走原有增量编译。

外部构建的触发范围以工具链自己的输入模型为准，而不是按目录或扩展名猜测：Flutter 使用当前变体 compile task 声明的输入，因此 `pubspec.yaml`、已参与构建的工程外 local path package Dart、以及被该构建读取的 Flutter assets 都会触发；Native 使用 CMake File API 与 AGP 生成的 native metadata 得到目标源码，因此工程外共享源码、汇编和项目内 include root 也会触发。Flutter SDK、全局 pub cache、`.dart_tool`、`.cxx`、`.externalNativeBuild` 和构建输出目录始终不监听。取不到这些工具链输入时，Jugg 退回只按源码根和源码扩展名识别的旧行为，不会把相邻目录或缓存纳入监听。

产物 CRC 只决定新输出是否需要再次部署。它不会跳过 Flutter 或 C/C++ 编译，避免源码已经变化但中间产物尚未刷新的情况被误判为无变化。

多 APK 工程中，每份产物还必须保留自己的目标 APK 归属。Jugg 不会把同一份 asset 或 native lib 默认复制到所有 APK。

## 加载方式决定生效方式

生成增量产物后，Jugg 还要根据 Android 运行时读取文件的方式选择部署路径：

```text
asset 增量产物
  -> 作为目标 APK 的 overlay 下发
  -> 运行时通过 AssetManager 读取新文件

native lib 增量产物
  -> 写回目标 APK 的 lib/<abi> 目录
  -> 重新签名并安装更新后的 APK
```

asset overlay 会保持 `assets/**` 路径，供新的资源加载路径读取。普通 asset 或资源 overlay 不会成为 APK 的 native library 搜索目录，因此当前 `.so` 更新路径会修改目标 APK，而不是把 `.so` 当作 asset overlay 下发。

## 需要回到 Gradle 的情况

- 已识别的外部输入被删除时，Jugg 直接回退完整 Gradle 构建。现有 APK 与 overlay 链没有删除设备端文件的原语，删除 asset 文件或 native 源码都可能让产物集合缩小，无法在增量路径上安全表达。
- 外部构建成功后，Jugg 会比较本轮与上一轮的 native/asset 产物。上一轮已收集的产物本轮不再产生时，本轮编译失败并提示需要完整 Gradle 构建，不会残留旧 `.so` 或 asset 后报告成功。
- 同一轮里有多个外部输入时，Jugg 要求全部输入都能解析。任一输入缺少 metadata、task 或产物契约（例如多 module 工程中只有一个 module 配置了外部构建）时整轮回退完整 Gradle，不会只构建可识别的部分。
- 已识别 Flutter/C++ 源码根但缺少 task、输出目录或 Flutter native 输出元数据时，Jugg 会回退完整 Gradle 构建；外部 task 执行失败、native 输出无法读取或既没有 assets 也没有有效产物时，本轮编译失败，不复用旧中间产物。Debug 等本身不产出 native lib 的构建模式只要 assets 有效就算成功。
- 远程编译和无法安全派生外部 task 的自定义命令会回到完整 Gradle 构建。
- `pubspec.yaml`、`pubspec.lock`、`CMakeLists.txt`、项目内 `*.cmake`、`Android.mk` 和 `Application.mk` 是外部构建的配置输入。修改它们会执行既有外部 task，并在 task 结束后刷新项目模型，不需要单独触发完整构建；只有 NDK、ABI、native source set、packaging 规则等无法由该 task 覆盖的配置变化，才需要完整 Gradle 构建刷新 APK 基线。
- 修改 asset source set、variant 或影响 APK 路径与归属的构建配置后，需要刷新 Gradle 基线。
- native lib 更新依赖可用的 APK 签名配置；无法完成重签名时，不能继续使用这条增量更新路径。

## 相关页面

- [增量编译总览](./index.md)
- [资源增量编译](./resource.md)
- [资源编译能力](../../capabilities/compile/resource-compile.md)
- [so 更新能力](../../capabilities/compile/so-update.md)
- [多 APK 部署](../../capabilities/deploy/multi-apk.md)
- [部署策略](../deploy-strategy.md)
