---
title: so 更新
description: 说明 Jugg 如何处理已有 .so、C/C++ 源码和 Flutter native 产物，并通过 APK 重签名让更新生效。
status: active
tags:
  - capability
  - compile
  - native
  - so
---

# so 更新

Jugg 支持更新已产出的 native lib / `.so` 文件。对于 Gradle 管理的 C/C++ 模块，源码变化会先执行当前变体的 native 构建任务；Flutter 混合工程产生的 native library 也会进入同一条 APK 更新流程。Jugg 随后写入目标 APK、重新签名并安装。

## 支持范围

| 场景 | 当前支持情况 | 用户可见结果 |
|---|---|---|
| 更新项目目录中已有、位于 ABI 目录下的 `.so` | 支持 | 更新目标 APK，重新签名并安装 |
| 修改 Gradle 管理的 C/C++ 源码 | 支持 | 执行当前变体的 native 构建任务，再更新生成的 `.so` |
| 同一个 C/C++ source 被多个 Native 模块共享 | 支持 | 一次执行所有匹配模块的去重 native task，并分别更新各模块生成的 `.so` |
| Flutter Profile/Release 生成 `app.so` 或 native assets | 支持 | 执行当前变体的 Flutter native 输出任务，从该任务自己的输出中读取目标 ABI 的 native lib，再更新 APK |
| Flutter Debug 只生成 assets，不生成 native lib | 支持 | 只更新 `flutter_assets`，不要求 native 输出存在；原生目录为空时本轮编译仍然成功；不重打包、不重签名、不安装 APK |
| 同轮更新多个 ABI 的 native lib | 支持按目标 APK 归属处理 | 每个目标 APK 只接收属于自己的 native lib |
| 更新大于 `Int.MAX_VALUE`（约 2 GiB）的已有 `.so` | 有条件支持 | 不把完整文件读入 IDE 堆；APK 更新时流式替换基线中的同路径 entry，SO hot update 可用时直接推送源文件 |
| 开启「SO hot update」且本轮只有 native lib | Android 8.0+、目标 ABI 与 sandbox 可用时支持 | 写入 App `code_cache/.jugg_native/<abi>/`，跳过 APK 重签名和安装，重启 App 后加载 |
| 删除 `.so` | 不生成移除结果 | 已安装 APK 继续包含原有 native lib |
| 修改 `CMakeLists.txt`、项目内 `*.cmake`、`Android.mk`、`Application.mk` | 支持 | 执行当前变体的 native task，并在同一 Gradle invocation 结束前定向更新该模块的外部构建信息；新 `.so` 按既有流程更新 APK |
| 修改 NDK、ABI、native source set 或 packaging 规则 | 不作为源码增量输入 | 通过完整 Gradle 构建刷新项目模型和 APK 基线 |
| 修改 `packaging.jniLibs.keepDebugSymbols` | 支持 | 按 app 打包语义保留这些 `.so` 的调试符号，不执行 app 的 native 构建或 strip 任务 |

## 触发与结果

```text
C/C++ 源码变化
  -> 找到所有共享该源码的 Native 模块
  -> 一次执行当前变体的全部去重 native Gradle task
  -> 读取 APK owner 的 strip 配置，在本轮 invocation 目录内按 app 打包语义产出 stripped .so

Flutter Dart 源码变化
  -> 执行当前变体的 Flutter native 输出 task
  -> 从该 task 自己的 native 输出（归档或目录）中收集 <abi> 下的 .so

项目目录中已有的 .so 发生变化
  -> 根据 ABI 和 APK 归属确定目标路径
  -> 写入目标 APK 的 lib/<abi> 目录
  -> 对 APK 重新签名
  -> 安装更新后的 APK
```

安装完成后，App 使用更新后 APK 中的 native lib。从 C/C++ 源码生成 `.so` 仍由 Gradle、CMake 和 NDK 完成；Jugg 负责在检测到源码变化时启动对应任务，并把新产物接入既有 native lib 部署。

## Debug Dart 代码如何生效

Flutter Debug/JIT 的 Dart 代码不是 native lib，而是 `assets/flutter_assets/kernel_blob.bin` 等 asset。Jugg 把它们作为 asset overlay 下发，不写回 APK，因此 Debug 下修改 Dart 不会触发重打包、重签名或安装。

Flutter Android embedding 会把 `flutter_assets` 解压到应用私有目录 `app_flutter`，并用 `app_flutter/res_timestamp-<versionCode>-<lastUpdateTime>` 判断是否需要重新解压。overlay 更新不改变 APK 的 `lastUpdateTime`，所以 Jugg 在全部 overlay 分片成功后删除该 timestamp，再完整重启 App，让 Flutter 从已生效的 overlay 重新解压。涉及该流程时本轮部署类型为 Hot Fix。

overlay 生效后，Jugg runtime 还会刷新 Flutter 引擎持有的 `AssetManager`，让后续 asset 读取使用当前 overlay；具体机制见 [assets 与 native lib 原理](../../concepts/incremental-compile/assets-native.md)。

Profile/Release 使用 AOT 产物 `libapp.so`，属于 native lib，继续按上面的 APK 更新、重签名和安装路径处理。

## 使用边界

- 直接文件变化入口只识别项目目录中已经存在、父目录为 `armeabi`、`armeabi-v7a`、`arm64-v8a`、`x86` 或 `x86_64` 的 `.so`。
- C/C++ 源码入口要求 Android Gradle 配置提供 CMake 或 ndk-build 文件，并能够找到当前变体的 native task。Jugg 不监听 `.cxx`、`.externalNativeBuild` 或 Gradle `build` 目录中的生成文件。工程可以在 Gradle extra `juggExternalBuildPrerequisites` 声明「哪些文件变化时先跑 codegen」；命中后同一轮先执行该 task，再跑 native merge，声明目录里相对 codegen 执行前发生大小或时间戳变化的 Kotlin/Java 才进入 Jugg 增量编译。没有声明的工程行为不变。
- 每次检测到 C/C++ 源码变化都会执行 native task；产物内容校验只避免重复写入 APK，不跳过 native 编译。
- 部署的是按 app 打包语义 strip 过的 `.so`，而不是 module 中间产物目录里的未 strip 文件。Jugg 在 collector 进程内读取 APK owner（base app 或 dynamic feature）的 `strip<Variant>DebugSymbols` 配置并复现 AGP 的单文件 strip 行为，不执行该 strip task。本轮只执行因 C/C++ 变化被选中的 module merge task，不会额外执行 APK owner 的其他 native merge task。strip 工具缺失或返回非 0 时按 AGP 语义原样打包该文件。
- 只有大于 `Int.MAX_VALUE`（2,147,483,647 bytes）的 NativeLib 使用 file-backed 路径；普通 `.so`、Dex、资源和 Asset 继续使用原有内存路径。file-backed 源文件在写入 APK 或推送设备前会重新校验存在性、大小和时间戳，变化后本轮明确失败。
- APK 更新大型 `.so` 时要求基线 APK 已存在同路径 entry，并继承它的 `STORED` 或 `DEFLATED` 压缩方式；不会把 DEFLATED 大型 `.so` 强制改成 STORED。单 entry 达到经典 ZIP 4 GiB 边界、基线 entry 缺失、磁盘空间不足，或 zipalign、签名、校验、安装工具链拒绝时，本轮失败并保留原 APK。大文件的 CRC、压缩和临时 APK 会增加耗时与磁盘占用。
- 「SO hot update」不会因文件较大而自动开启。现有开关、Android 版本、ABI 和 sandbox 条件全部满足时，大型 `.so` 直接推送源文件，不再创建同体积的本地临时副本；条件不满足或该路径失败时仍回到 APK 更新流程。
- native library module 可以在 APK owner 未被配置的情况下构建，例如工程开启 Gradle Configuration on Demand 时本轮读不到 owner 的 strip task。因此完整 Gradle 构建会把 owner 的 strip 配置和每个 strip 工具副本缓存到 `build/jugg/classpath/native_strip`，collector 优先使用该缓存。缓存按模块根与变体精确匹配，且只有记录的工具仍可执行时才会复用；缓存缺失、损坏或工具不可用时只降级为一次实时读取，owner 未配置且没有可用缓存时本轮失败并提示执行完整 Gradle 构建，不会部署未 strip 的库。工具副本随缓存一起保存，所以基线复制到 NDK 路径不同的另一台 Worker 后仍可使用；复制基线时需要保存整个 `native_strip` 目录。
- 同一个物理 source 匹配多个 Native 模块时，所有匹配 task 必须全部支持并执行成功，各模块输出也必须全部可收集；否则该 source 整体回退或失败，不会把部分成功结果标记为已编译。
- 每次检测到 Dart 源码变化都会执行当前变体的 Flutter native 输出 task。Jugg 只读取该 task 自己声明的 native 输出，并按它是归档还是目录解析出 ABI 下的 `.so`；不从 Flutter 中间目录递归猜测 native 输出，也不按固定路径拼接产物位置。
- 已识别 Flutter 源码根但缺少 compile task、assets 输出目录或 native 输出元数据时，Jugg 会回退完整 Gradle 构建；native 输出无法读取，或归档中出现不安全、重复的 native 条目时，本轮编译失败。Debug 等本身不产出 native lib 的构建模式只要 assets 输出有效就算成功。
- 已识别 C/C++ 源码根但缺少任务或输出目录元数据时，Jugg 会回退完整 Gradle 构建；外部任务失败或约定输出目录缺失、不可读时，本轮编译失败。任务成功且输出目录可访问但没有生成有效 `.so` 时，本轮成功且没有 native 部署产物。
- 项目内 CMake/ndk-build 配置文件（`CMakeLists.txt`、`*.cmake`、`Android.mk`、`Application.mk`）属于当前变体 native build 的配置输入：修改后 Jugg 执行既有 native task，并在同一 Gradle invocation 内只收集、合并该模块的最新外部构建信息，不再异步刷新完整项目模型。新增的工程外共享源码、汇编文件与 include root 会立即进入后续文件监控范围。NDK、ABI、native source set 或 packaging 规则等无法由该 task 覆盖的配置变化，仍需完整 Gradle 构建刷新 APK 基线。
- C/C++ 输入按目录递归触发：配置根、metadata source 的父目录和 include root 会合并成尽量少的监控目录。目录中的非排除文件可能产生少量误触发，较宽的共享目录也可能同时触发多个模块，包括产物较大的模块；Gradle 和 Ninja 会根据 up-to-date 与真实依赖决定是否真正执行 native 工作。`.cxx`、`.externalNativeBuild` 和 build 输出仍不会触发。
- 已识别的 C/C++ 源码或 native 配置文件被删除时，Jugg 回退完整 Gradle 构建；外部构建成功后若上一轮收集到的 native 产物本轮不再产生，本轮仍成功且不生成移除结果，旧 `.so` 保留到完整 Gradle 构建刷新 APK 基线。
- 同一轮内多个外部输入并非全部可解析时（例如多 module 工程中只有一个 module 配置了 native 构建），整轮回退完整 Gradle 构建，不会只构建可识别的部分。
- 删除 `.so` 不会生成 APK 内文件的移除数据，也不会仅因此让增量编译失败。已安装 APK 继续包含原有 native lib，只有需要让删除真正生效时才执行完整 Gradle 构建。
- 多 APK 工程按目标 APK 归属更新，不会把同一份 native lib 默认写入所有 APK。
- 签名配置缺失或无效时，本轮增量 APK 更新失败；需要通过 Gradle 构建恢复可安装的 APK 基线。

## 相关页面

- [编译阶段说明](../../guide/compile.md)
- [assets 与 native lib 原理](../../concepts/incremental-compile/assets-native.md)
- [多 APK 部署](../deploy/multi-apk.md)
