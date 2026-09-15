# Flutter 与 C++ 外部构建增量接入方案

## 背景

Flutter 混合工程修改 Dart 文件后，Jugg 当前不会识别该变更。完整 Gradle 构建会执行 Flutter 编译，同时重复编译耗时较长的 Android 原生部分。用户期望每次 Dart 变化都执行 Flutter 自身编译，再将生成的 Flutter assets 或 native libraries 交给 Jugg 现有增量部署链路。

C++ 模块也存在相同入口缺失：源码变化不会触发 external native build，生成在中间目录的 `.so` 又被 build 目录过滤，最终无法进入 APK 更新流程。

## 目标行为

- Dart 文件变化时始终执行当前 Variant 的 `compileFlutterBuild<Variant>`，不增加 Jugg 侧的 Flutter 编译缓存。
- Flutter Debug 产出的 `flutter_assets` 进入现有 Asset 增量部署；Profile/Release 额外将 `app.so` 和 native assets 转为现有 NativeLib 输入。
- C/C++ 文件变化时执行当前 Variant 的 native build/merge task，将生成的 `.so` 转为现有 NativeLib 输入。
- Android Java、Kotlin、资源等文件继续使用 Jugg 增量编译，不因 Flutter 或 C++ 变化回退完整 Android 构建。
- 外部任务失败或无法取得有效产物时明确失败，不使用旧产物伪造成功。

## 实现方案

1. 在 Module 工程信息中记录外部构建类型、源码目录、Gradle task 和输出目录。Gradle 工程读取阶段从当前 Variant 的 Flutter 与 external native build task 中取得这些信息，旧工程快照缺少字段时使用空列表兼容。
2. `FileChangesHandler` 在已配置的外部源码目录内识别 `.dart`、`.c`、`.cc`、`.cpp`、`.cxx`、`.h`、`.hh`、`.hpp`、`.hxx`，产生新的 `ExternalBuildSource`。`.dart_tool`、`.cxx`、`.externalNativeBuild` 和 build 目录继续排除。
3. 在现有 Asset/NativeLib 编译前增加 `ExternalBuildCompiler`：按 Module 合并变更并执行一次对应 Gradle task，随后从声明的输出目录收集 Flutter assets 或 `.so`。
4. Flutter 输出按 Flutter Gradle 插件的目录契约转换：`flutter_assets/**` 转为 Asset；Profile/Release 的 `<abi>/app.so` 转为 `lib/<abi>/libapp.so`；native assets 保持 ABI 目录进入 NativeLib。
5. 生成产物通过内容校验过滤。Asset 与 Jugg 已部署文件比较，NativeLib 与目标 APK 中对应 entry 的 CRC 比较。该过滤只避免重复部署，不跳过 Flutter/C++ 编译。
6. 转换后的 Asset 和 NativeLib 继续交给 `AssetOverlayCompiler`、`DeployDataGenerator` 和现有 APK 更新、重签名、安装流程。
7. 本地子进程沿用 Kotlin 编译进程的取消和超时语义。远程编译、非标准 Gradle 命令、构建配置变化以及无法发现任务的工程回退完整 Gradle 构建。

## 预计改动

- `main/src/main/java/com/sickworm/intellij/jugg/project/data/JuggProjectInfo.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/gradle/script/GradleProjectInfoReader.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/project/data/JuggProjectInfoSerialize.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/gradle/script/ProjectInfoSerializerInGradle.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/project/merger/JuggProjectInfoMerger.kt`
- `main/src/main/resources/gradle/readProjectInfo.gradle.kts`
- `main/src/main/java/com/sickworm/intellij/jugg/project/FileChangesHandler.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/ICompiler.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/CompilerExt.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompiler.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/IncrementalCompilerHelper.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompileHelper.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/ui/JuggControlPanelController.kt`
- 新增外部构建编排与进程执行实现。

实际实现保持最小范围；若现有数据流能够直接提供所需信息，不为匹配上述文件清单而增加无意义修改。

## 验证

- 先增加失败测试，证明当前 Dart/C++ 文件不会进入编译输入。
- 验证 Dart/C++ 文件识别、目录排除和真实 Module 归属。
- 验证 Flutter Debug assets、Flutter Profile/Release native libraries 和 C++ `.so` 的转换。
- 验证多文件任务去重、失败不复用旧产物、内容未变化时不重复部署。
- 增加等价 L3 Flow，覆盖 Dart 到 Asset 部署以及 C++ 到 APK native entry 更新。
- 执行定向测试、`./gradlew :idea:compileKotlin`、Gradle 工程信息脚本一致性检查和日志格式检查。

## 范围外

- 删除源码后推导需要删除的 APK native entry。
- `pubspec.yaml`、CMake、ndk-build、ABI、NDK 或 packaging 配置变化的局部适配。
- 远程增量拉取 Flutter/C++ 中间产物。
- 非 Gradle 管理的自定义 Flutter/C++ 构建脚本。
