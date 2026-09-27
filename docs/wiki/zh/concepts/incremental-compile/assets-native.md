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

Dart 或 Flutter asset 变化
  -> 每次执行当前变体的 Flutter native 输出 task；该 task 同时依赖 Flutter compile task
  -> flutter_assets 转为 asset；该 task 自己声明的 native 输出（归档或目录）中的 native lib 转为 native lib

C/C++ 变化
  -> 找到共享该物理源码的所有 Native 模块
  -> 在一次 Gradle invocation 中执行当前变体的全部去重 native build/merge task
  -> 各模块的新 .so 分别转为 native lib 增量产物
```

这个过程不执行 aapt2，也不会生成 `resources.arsc`。Dart 或已确认的 Flutter asset 变化始终执行 Flutter 编译和 native 输出 task，不增加 Jugg 侧 Flutter 缓存；Flutter assets 从当前输出目录读取，native lib 只接受该任务自身声明的 native 输出中结构明确的 ABI 条目，不会递归扫描 Flutter 中间目录。C/C++ 到 `.so` 的转换仍由 Gradle、CMake 和 NDK 完成。同一个物理 source 同时参与多个 Native 模块时，Jugg 会执行所有匹配模块的当前变体 task，并分别收集它们的输出；只有全部相关构建和输出收集成功后，该 source 才完成本轮编译。Android Java/Kotlin 和资源部分仍走原有增量编译。

外部构建采用“目录触发、Gradle 判定”的策略，每个被监控目录都带有自己接受的文件类型。Flutter package 根只接受 Dart 源码，不会扩大为任意文件；由 `pubspec.yaml` 的 `flutter.assets` 或 `l10n.yaml` 的 `arb-dir` 声明的资源目录，以及 Flutter task 在 package 根之下暴露的目录，接受任意文件。Native 的 externalNativeBuild 配置根接受 C/C++ 源码和头文件，native build metadata 确认的具体源码目录接受任意非隐藏文件，include root 只接受头文件。父子目录各自保留规则，不再由上层目录吞掉子目录；同一目录的多条规则之间是 OR，因此即使更宽的 Native 目录也覆盖某个头文件，它仍会命中对应的 include root。仍允许少量误触发；较宽的共享目录或 include root 也可能同时触发多个 Native 模块，包括产物较大的模块。Gradle 和 Ninja 的 up-to-date 与真实依赖判断仍决定实际执行哪些 native 工作。Jugg 不再依赖旧 depfile 的精确文件列表，所以已监控资源目录中新建的图片、JSON 或嵌套目录文件不会因为上次尚不存在而漏掉。单文件 asset、font 和 shader 仍依赖 Flutter task inputs，而不依赖 `pubspec.yaml` 条目；已删除的文件直接忽略，把旧 native 代码或 asset 从设备上移除需要一次完整 Run。

已归属 Android 模块的 Kotlin / Java 源码、资源、assets，以及 ABI 目录中的 `.so` 会优先按各自类型处理，即使更宽的 Native 目录也覆盖这些文件；未归属这些类型的文件才按外部构建规则判断。

Flutter SDK、全局 pub cache、`.dart_tool`、`.cxx`、`.externalNativeBuild` 和构建输出目录始终不监听。每次外部 task 执行时，Jugg 会在同一 Gradle invocation 结束前只收集本轮相关 module 和 variant 的最新外部构建信息；它不会为了配置文件变化另起一个完整 project-info 刷新。新信息合入项目模型后，文件监控范围立即更新，因此新加入的本地 package、共享 C/C++ 目录或 include root 从下一次文件变化开始即可触发。若定向信息未完整生成或无法合入，当前增量编译会失败并保留完整 Gradle 回退边界，而不会继续使用已知过期的监控范围。

产物 CRC 只决定新输出是否需要再次部署。它不会跳过 Flutter 或 C/C++ 编译，避免源码已经变化但中间产物尚未刷新的情况被误判为无变化。

多 APK 工程中，每份产物还必须保留自己的目标 APK 归属。Jugg 不会把同一份 asset 或 native lib 默认复制到所有 APK。

## 加载方式决定生效方式

生成增量产物后，Jugg 还要根据 Android 运行时读取文件的方式选择部署路径：

```text
asset 增量产物
  -> 作为目标 APK 的 overlay 下发
  -> 运行时通过 AssetManager 读取新文件

native lib 增量产物
  -> 开关开启且 Android 8.0+：进入目标 APK 的 overlay，重启 App 后加载
  -> 其它情况：写回目标 APK、重新签名并安装
```

asset overlay 保持 `assets/**` 路径。普通大小的 `.so` 在「SO hot update」开启时与 DEX、资源和 Asset 使用同一批 overlay，但按 APK 和 ABI 分目录保存；运行时只从已提交的 overlay 中选择当前进程 ABI 的库，并把目录加入 native library 搜索路径。同轮包含其它增量文件时，`.so` 仍需完整重启 App 才会生效。切换开关会在下一次 Run 清除 App 数据并重装，使之前的补丁失效。

大于 `Int.MAX_VALUE`（2,147,483,647 bytes）的 NativeLib 使用 file-backed 部署数据，不把整个 `.so` 放入 IDE 堆。开关开启且 Android 8.0+ 时，它通过 App sandbox 直接推送源文件，最终也发布到目标 APK 的 overlay；sandbox 不可访问或传输失败时本轮明确失败。开关关闭或 Android 版本较低时，APK 更新流式读取源文件，替换基线中同路径 entry 并继承压缩方式；基线缺少该 entry 或文件达到经典 ZIP 单 entry 4 GiB 边界时明确失败。文件大小不会自动开启 SO hot update。

### Flutter Debug/JIT 的解压缓存

Debug 模式的 Dart 代码放在 `assets/flutter_assets/kernel_blob.bin`，并配套 `vm_snapshot_data`、`isolate_snapshot_data`。Flutter Android embedding 在首次启动时把这些文件解压到应用私有目录 `app_flutter`，之后直接使用解压结果，只在 `app_flutter/res_timestamp-<versionCode>-<lastUpdateTime>` 与实际安装的 APK 不匹配时才重新解压。

overlay 更新不改变 APK 的 `lastUpdateTime`，所以只下发 asset overlay 再加一次普通重启，App 仍会读取 `app_flutter` 中的旧 Dart 代码。Jugg 因此在本轮真实编译并部署了上述 Flutter JIT runtime 文件时，等全部 overlay 分片成功后删除目标应用 `app_flutter` 直属的 `res_timestamp-*`，再完整重启 App，让 Flutter 自己从已生效的 overlay 重新解压。这条路径不重打包、不重签名、不安装 APK，用户看到的部署类型是 Hot Fix。

Flutter Profile/Release 使用 AOT 产物 `libapp.so`，继续按 native lib 的开关选择 overlay 或 APK 更新路径，不进入该解压缓存失效流程。失效命令只删除 `app_flutter` 直属的 `res_timestamp-*` 普通文件，不触碰 `flutter_assets`、kernel、overlay 和应用其它数据；timestamp 不存在时视为成功。

### Flutter 引擎持有的 AssetManager

Flutter 引擎会长期保留创建时取得的 `AssetManager`，而 Apply Changes 更新应用资源时会建立新的资源视图。仅让 Android `Resources` 使用新 overlay，已经运行的 Flutter 引擎仍可能从旧 `AssetManager` 读取 asset。

Jugg runtime 在 overlay 生效后把包含该 overlay 的 `AssetManager` 更新给存活的 Flutter 引擎；新引擎创建时使用的宿主包资源也会先补齐相同的 overlay。overlay 目录以最高查找优先级加入，并通过 Android 原生目录读取路径提供文件，避免 Flutter 工作线程依赖 Java asset 回调。这样 Flutter 的后续 asset 读取与 Android 当前 overlay 保持一致。

## 需要回到 Gradle 的情况

- 删除 Flutter asset 时，Jugg 不生成删除产物，也不触发增量编译失败或 Gradle 回退。已安装 APK 或既有 overlay 中的旧 asset 继续保留；需要让删除真正生效时，再执行完整 Gradle 构建刷新 APK 基线。
- 外部构建成功且约定输出路径可访问时，Flutter asset 或 native lib 产物集合缩小，乃至本轮没有可部署产物，都会被视为成功且不生成对应的移除结果。旧 asset 或 `.so` 继续保留；只有需要让删除真正生效时才执行完整 Gradle 构建。
- 被删除的路径会被输入规则忽略：已识别的 Dart/C/C++ 源码、Flutter asset 或外部构建配置输入消失后不再产生变更项，也不会移除旧产物。已经进入待编译队列后才被删除的外部输入仍会回退完整 Gradle 构建而不是部分外部构建，因为它已经无法解析。
- 同一轮包含多个外部输入，或一个物理 source 匹配多个模块时，Jugg 要求所有输入和 target 都能解析。任一 target 缺少 metadata、task 或产物契约时整轮回退完整 Gradle，不会只构建可识别的部分。
- 已识别 Flutter/C++ 源码根但缺少 task、输出目录或 Flutter native 输出元数据时，Jugg 会回退完整 Gradle 构建；外部 task 执行失败、约定输出路径缺失或不可读、native 归档损坏或包含不安全/重复条目时，本轮编译失败。具体原因由对应 compiler 打印并保存在编译错误中。
- 远程编译和无法安全派生外部 task 的自定义命令会回到完整 Gradle 构建。
- `pubspec.yaml`、`pubspec.lock`、`l10n.yaml`、`CMakeLists.txt`、项目内 `*.cmake`、`Android.mk` 和 `Application.mk` 是外部构建的配置输入。修改它们会执行既有外部 task，并在 task 结束后刷新项目模型，不需要单独触发完整构建；只有 NDK、ABI、native source set、packaging 规则等无法由该 task 覆盖的配置变化，才需要完整 Gradle 构建刷新 APK 基线。
- 修改 asset source set、variant 或影响 APK 路径与归属的构建配置后，需要刷新 Gradle 基线。
- native lib 走 APK 更新时依赖可用的 APK 签名配置；无法完成重签名时，不能继续使用该路径。

## 相关页面

- [增量编译总览](./index.md)
- [资源增量编译](./resource.md)
- [资源编译能力](../../capabilities/compile/resource-compile.md)
- [so 更新能力](../../capabilities/compile/so-update.md)
- [多 APK 部署](../../capabilities/deploy/multi-apk.md)
- [部署策略](../deploy-strategy.md)
