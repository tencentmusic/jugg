# 多模块共享 Native Source Demo 复刻 Prompt

请把以下任务当作一次“结构等价、源码完全合成”的 Android Native 工程复刻。你可以读取当前真实工程以提取构建事实，但必须保持真实工程源码只读，并把所有新文件写入独立的 Demo 目录。

## 任务目标

创建一个可独立构建、可交付的 Android Demo 工程，准确复刻当前工程中“多个 Android/Native 模块共享同一个 C/C++ source”的关键特征，用于复现和验证增量构建工具对共享 Native source 的识别与任务调度。

已知需要重点复刻的场景特征：

- 工程中至少存在两个 Android Library/Native 构建模块，可分别抽象为 `mp.appcommon` 和 `mp.dtmp`。
- 两个模块拥有不同的 Android module root、CMake 入口、Gradle Native task 和最终 `.so` 输出。
- 一个 Android App 同时依赖这两个模块，并把两个模块的 `.so` 打包进同一个 Debug APK。
- 一个位于 Android module root 之外的共享 `.cc` 文件被两个 Native 构建共同使用。
- `appcommon` 对应一个源码和产物规模明显更大的 `.so`。
- Native include/source path 中存在 `..`、`../..` 等路径，标准化后可能覆盖较宽的共同父目录。
- 修改共享 `.cc` 后，正确行为应是所有真正使用它的 Native task 都被触发，而不是按模块遍历顺序只选择一个 task。

最终交付的是 Demo 工程，不是问题修复。不要修改 Jugg、真实工程业务代码或真实工程构建配置。

## 工作目录与安全边界

1. 将当前工程视为 `REAL_PROJECT_ROOT`，只允许读取。
2. 默认将 Demo 创建在 `REAL_PROJECT_ROOT` 的同级目录：

   ```text
   ../jugg-shared-native-source-demo
   ```

3. 如果目标目录已存在且非空，禁止覆盖或删除；改用带时间戳的新目录，并在结果中说明。
4. 禁止对真实工程执行 `clean`、删除构建缓存、切换分支、reset、checkout、自动格式化或批量改写。
5. 优先读取现有 Gradle/CMake 元数据。只有在缺少必要事实时，才允许执行不会修改源码的 Gradle 配置或定向 Native task，并记录执行过的命令。
6. 不复制真实源码、资源、二进制产物、keystore、证书、token、账号、服务地址、`local.properties`、绝对用户路径或内部仓库配置。
7. 所有 Demo Kotlin/Java/C/C++ 源码必须从零编写，只表达依赖关系，不能改名复制真实实现。
8. 不得为了让 Demo 构建成功而擅自升级或降级 AGP、Gradle、Kotlin、NDK、CMake。版本不可用时明确报告，不伪造“已对齐”。

## 第一阶段：提取真实工程事实

先调查，后创建 Demo。将调查结果写入 Demo 根目录的 `REAL_PROJECT_FACTS.md`。只记录构建结构和版本，不记录业务源码内容。

### 1. 构建工具版本

必须提取并记录以下信息及其证据来源：

- Gradle Wrapper 完整版本和 `distributionUrl`。
- AGP 版本及声明位置。
- Kotlin Gradle Plugin 版本及声明位置。
- 使用的 JDK major version。
- `compileSdk`、`minSdk`、`targetSdk`。
- `ndkVersion`，以及实际解析到的 NDK revision。
- CMake 配置版本和实际使用版本。
- Android ABI 列表，重点记录 Debug variant 实际构建的 ABI。
- 影响 Native 构建的关键 Gradle property、CMake argument、编译类型和 STL 配置。

版本必须从 Wrapper、build script、version catalog、Gradle task/model 或现有 `.cxx` 元数据中读取，不能只根据 Android Studio 版本推测。

### 2. Gradle 模块结构

提取并记录：

- 根工程与 included build/composite build 关系。
- App module 的 Gradle path。
- 两个目标 Native module 的 Gradle path、实际 module root 和 projectDir 映射。
- App 到两个 Library module 的依赖类型与方向。
- 两个 Library module 之间是否存在直接 Gradle 依赖。
- Debug variant 下对应的 `merge<Variant>NativeLibs`、`buildCMake<Variant>` 或等价任务路径。
- 两个模块的 Native 输出目录，以及最终进入 APK 的 `.so` 名称。
- Gradle/module 遍历顺序或依赖顺序中，哪个模块位于前面。

### 3. C/C++ 构建结构

对两个 Native module 分别提取：

- `externalNativeBuild` 使用 CMake 还是 ndk-build。
- Make file/CMake 入口文件的真实相对位置。
- Android module root 到 Native workspace、共享 source root 的相对路径关系。
- 目标 `.so` 对应的 CMake target。
- target 直接 source、object/static library 和 link dependency 的结构。
- 共享 `.cc` 是被两个 `.so` 直接编译，还是通过共享 object/static library 间接进入两个 `.so`。
- include directory、配置文件和 source directory 中标准化后覆盖共同父目录的路径，特别是包含 `..` 或 `../..` 的条目。
- CMake File API codemodel、`compile_commands.json`、`build.ninja` 或 AGP Native model 中，能证明共享 source 被哪些 target 使用的条目。

只在 `REAL_PROJECT_FACTS.md` 中写结构化结论和经过脱敏的相对路径。不要复制完整 CMakeLists、Ninja rule、编译命令、宏定义或真实 source 内容。

### 4. 事实与假设分离

在 `REAL_PROJECT_FACTS.md` 中使用以下分类：

- `已确认事实`：有 build file、Gradle model、CMake codemodel、Ninja dependency 或 task 输出支持。
- `合理推断`：根据目录或宽泛 include root 推断，但没有精确 target/source 证据。
- `仍缺证据`：当前环境无法确认。

不得把“某个文件位于一个 source/include root 下”直接写成“该文件一定被这个 target 编译”。

## 第二阶段：设计脱敏映射

创建 `STRUCTURE_MAPPING.md`，逐项说明真实工程角色如何映射到 Demo，但不能包含真实业务符号或源码片段。

至少包含以下映射：

| 真实工程角色 | Demo 角色 | 必须保持的关系 |
|---|---|---|
| App module | `:app` | 同时依赖两个 Native Library module |
| 大型 Native module | `:mp:appcommon` | 独立 CMake 入口，生成较大的 `libappcommon.so` |
| 第二 Native module | `:mp:dtmp` | 独立 CMake 入口，生成 `libdtmp.so` |
| 共享 Native source | `native-workspace/modules/shared_feature/shared_logic.cc` | 同时影响两个 Native target |
| 共同父目录 | `native-workspace/` | 可被标准化后的宽泛路径覆盖 |
| 大型 target 的其他 source | 纯合成 translation units | 只模拟规模，不表达真实业务 |

如果真实工程中共享 source 是通过 static/object library 进入两个 `.so`，Demo 必须复刻这一层；不能为了简单而改成两个 target 直接引用同一 `.cc`。如果真实工程确实是直接共享 source，则保持直接引用。

## 第三阶段：创建 Demo 工程

### 1. 推荐目录形态

最终目录应与真实工程的相对层级特征等价。可在调查结果基础上调整，但至少满足：

```text
jugg-shared-native-source-demo/
  settings.gradle[.kts]
  build.gradle[.kts]
  gradle.properties
  gradle/wrapper/
  app/
  native-workspace/
    CMakeLists.txt                       # appcommon 或顶层 Native 入口
    modules/
      shared_feature/
        shared_logic.h
        shared_logic.cc                 # 两个 Native 构建共享的唯一测试文件
    platform_bridge/
      android/                          # :mp:appcommon projectDir
        build.gradle[.kts]
        src/main/cpp/appcommon_jni.cc
    nested-core/
      CMakeLists.txt                    # dtmp Native 入口
      protocol/
      foundation/platform/android/      # :mp:dtmp projectDir
        build.gradle[.kts]
        src/main/cpp/dtmp_jni.cc
  scripts/
    verify_shared_native_rebuild.sh
  evidence/
  README.md
  REAL_PROJECT_FACTS.md
  STRUCTURE_MAPPING.md
  VALIDATION.md
  PRIVACY_AUDIT.md
```

目录名称可以按真实结构调整，但必须保留以下核心特征：Android module 分散在不同深度；共享 Native source 位于两个 module root 之外；两个模块通过不同 CMake 入口引用它。

### 2. Gradle 配置要求

- Wrapper、AGP、Kotlin、NDK、CMake、SDK 和 ABI 与真实工程对齐。
- `settings.gradle[.kts]` 使用嵌套 Gradle path：`:mp:appcommon`、`:mp:dtmp`。
- 使用 `projectDir` 映射表达非标准 module root。
- `:app` 同时依赖两个 Library module。
- 两个 Library module 分别配置自己的 `externalNativeBuild` 入口。
- 不引入与复现无关的第三方库、DI、Compose、网络、数据库或测试框架。
- 如果真实工程使用 Kotlin DSL、Version Catalog 或 pluginManagement，则 Demo 保持相同方式；否则不要主动引入。
- 不打包签名文件；使用标准 Debug 签名。

### 3. 合成 Native 源码要求

所有源码必须使用纯合成命名，例如 `syntheticSharedValue`、`AppCommonBridge`、`DtmpBridge`，不得沿用真实类名、函数名、namespace、日志文案或业务常量。

共享源码必须承担一个可观察行为，例如：

```text
syntheticSharedValue() -> 返回一个易修改的整数
```

两个 `.so` 都必须真实依赖这个函数，并通过各自独立的 JNI bridge 暴露结果：

- `libappcommon.so` 暴露 `AppCommonBridge.value()`。
- `libdtmp.so` 暴露 `DtmpBridge.value()`。
- App 页面同时显示两个值，用于确认两个 `.so` 是否包含同一轮共享源码变更。

共享函数应使用合适的 hidden/internal linkage，避免两个 `.so` 同名符号被动态链接器意外 interpose，导致运行结果不能代表各自产物内容。

### 4. 大型 appcommon 特征

`appcommon` 必须明显大于 `dtmp`，但不允许复制真实源码或生成不可控的大文件。

采用纯合成 translation units 模拟规模：

- 创建一组结构简单的合成 `.cc` 文件，由 `appcommon` target 编译。
- 数量根据真实工程规模特征做合理缩放，默认 40～100 个。
- 每个文件只包含不同编号的简单函数或小型静态数据。
- 总 Demo 源码体积应保持可交付，建议不超过 5 MB。
- 在 `STRUCTURE_MAPPING.md` 中说明这是规模缩放，不声称与真实 `.so` 大小完全相等。

### 5. 宽泛路径和标准化特征

在不制造无效路径的前提下，复刻真实工程中路径标准化后的重叠关系。例如让第二个 CMake 构建存在类似：

```cmake
${CMAKE_CURRENT_LIST_DIR}/protocol/../..
```

标准化后它应覆盖共享 source 所在的共同父目录。同时保留一个更具体的 shared source 路径，使 Demo 能同时验证：

- 宽泛 watch/include root 会匹配共享文件。
- 精确 CMake target source 关系能够证明该文件真正影响哪些 task。

不要仅为了制造字符串相似而添加未被 target 使用的无效目录。

## 第四阶段：验证

创建并执行 `scripts/verify_shared_native_rebuild.sh`。脚本必须使用严格错误检查，并完成以下验证：

1. 执行干净 Demo 的首次 Debug 构建：

   ```text
   ./gradlew :app:assembleDebug
   ```

2. 确认 APK 同时包含预期的 `libappcommon.so` 和 `libdtmp.so`。
3. 记录两个 `.so` 的初始 SHA-256。
4. 只修改合成共享文件中的测试整数，不修改任何其他源码或构建文件。
5. 在一次 Gradle invocation 中执行两个模块的 Native merge task：

   ```text
   ./gradlew :mp:appcommon:mergeDebugNativeLibs :mp:dtmp:mergeDebugNativeLibs --info
   ```

6. 从任务输出、Ninja 输出或目标文件时间戳确认共享 `.cc` 在两个 Native 构建中都被重新编译。
7. 确认两个最终 `.so` 的 SHA-256 都发生变化。
8. 再次执行相同命令但不修改源码，确认构建系统进入 up-to-date/no-work 状态。
9. 将关键命令输出保存到 `evidence/`，但必须过滤本机绝对路径、用户名、内部仓库地址和环境变量。
10. 验证结束后将共享测试整数恢复到 Demo 初始值，并确认 Demo 仍可构建。

如果环境具备 Jugg，可以额外执行并记录一次 Jugg 验证，但不能把它作为 Demo 基础构建成功的必要条件：

- 完整 Gradle build 建立 baseline。
- 第一次修改共享 `.cc`，运行一次 Jugg 增量编译。
- 记录 Jugg 识别出的全部候选模块和实际执行 task。
- 第二次修改后重复。
- 不修复或规避 Jugg 行为，只收集复现证据。

## 验收标准

只有同时满足以下条件才能宣布完成：

- 所有要求的工具链版本均已对齐，并有证据来源。
- Demo 使用两个独立 Android Native module 和两个独立 CMake 构建入口。
- 两个 Native 构建确实共享同一个物理 `.cc` 文件。
- App 同时依赖并打包两个 Native module 的 `.so`。
- 修改共享 `.cc` 后，两个 Native task 都发生实际工作，两个 `.so` 都变化。
- 不修改时再次构建显示 no-work/up-to-date。
- Demo 源码完全合成，没有复制真实业务实现。
- `README.md` 能让另一台已安装对应 SDK/NDK/CMake 的机器按步骤复现。
- `REAL_PROJECT_FACTS.md`、`STRUCTURE_MAPPING.md`、`VALIDATION.md`、`PRIVACY_AUDIT.md` 内容完整。

如果无法满足某项，必须明确标记为未完成，并保留失败日志。禁止用不同版本、不同 ABI、单模块结构或手工复制 `.so` 的方式伪造成功。

## 隐私与交付检查

在打包前执行一次隐私审计，并把结果写入 `PRIVACY_AUDIT.md`：

- Demo 中不存在真实业务 package、namespace、类名、函数名、文案、资源或注释。
- Demo 中不存在真实源文件内容或由真实源码轻微改名得到的内容。
- Demo 中不存在用户名、真实工程绝对路径、内部域名、仓库地址、账号和密钥。
- Demo 中不存在 `local.properties`、keystore、`.gradle/`、`.cxx/`、`build/` 或 IDE 私有状态。
- Demo C/C++、Kotlin、Java 文件的 SHA-256 不与真实工程中的源码文件相同。
- `evidence/` 日志已脱敏，但仍保留 task 名、相对路径、版本、Ninja 是否工作和产物 hash 等复现所需信息。

交付时生成：

```text
jugg-shared-native-source-demo.zip
jugg-shared-native-source-demo.zip.sha256
```

压缩包中不包含任何构建缓存和本机配置。

## 最终回复格式

完成后只报告以下内容：

1. Demo 工程绝对路径。
2. ZIP 和 SHA-256 文件绝对路径。
3. 对齐的 Gradle、AGP、Kotlin、JDK、NDK、CMake、SDK、ABI 版本摘要。
4. Demo 中两个 Native module、共享 source、CMake target 和 `.so` 的对应关系。
5. 首次构建、共享 source 修改、第二次 no-op 构建的验证结果。
6. 是否执行了 Jugg 验证，以及实际触发的 task。
7. 隐私审计结果。
8. 仍未对齐或缺少证据的事项。

不要在最终回复中粘贴大量构建日志；提供 `evidence/` 内对应文件路径。
