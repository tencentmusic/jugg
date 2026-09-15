# Hilt 旧版 Receiver 入口转换兼容方案

> 创建日期：2026-09-10
> 状态：已完成。

## 1. 目标

补齐 Hilt `2.41`～`2.48.1` 的 `BroadcastReceiver` 入口转换兼容，并明确 Jugg 已核对的 Hilt 版本范围。同步完善独立 HiltDemo，使其可以在旧字段 marker 与新版注解 marker 两类 Hilt 版本之间切换验证。

## 2. 已确认事实

- Hilt `2.41`～`2.48.1` 的 Android 入口父类、generic signature、构造器和普通 `super` 调用转换语义与当前 Jugg 实现一致。
- 该版本区间的 Receiver 生成父类使用私有 boolean 字段 `onReceiveBytecodeInjectionMarker` 标记需要插入 `super.onReceive()`。
- Hilt `2.49` 起改用 `OnReceiveBytecodeInjectionMarker` 类注解；当前 Jugg 只识别该注解。
- Hilt `2.49`～`2.60.1` 的官方 visitor 核心转换语义保持一致；当前真实设备回归基线为 `2.51.1`。

## 3. 最小实现

### Jugg 生产代码

- `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/HiltAndroidEntryPointTransformer.kt`
  - 在解析已有 `Hilt_*` 生成父类时，同时识别新版 marker 注解和旧版 marker 字段。
  - 字段只匹配官方名称和 boolean descriptor，避免同名非 boolean 字段误触发。
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/TransformerCompiler.kt`
  - 将已经读取的生成父类字节传给 Hilt transformer，不增加 classpath 文件读取。
  - 生成父类查找顺序、D8 classpath 传递和失败语义保持不变。

### 自动化验证

- `main/src/test/java/com/sickworm/intellij/jugg/compiler/source/HiltAndroidEntryPointTransformerTest.kt`
  - 先增加旧字段 marker 的失败用例，再实现兼容。
  - 保留新版注解 marker、无 marker 和重复转换回归。
- 复用现有 `TransformerCompilerTest` 验证 classpath 查找与失败收口。

### 文档

- `docs/ai_knowledge/02_compile_source.md`
  - 记录 Hilt `2.41`～`2.60.1` 的源码级兼容范围、marker 分界和真实验证边界。
- `docs/wiki/zh/capabilities/compile/annotation-processors.md` 与英文镜像
  - 面向用户说明支持版本、旧版 Receiver 兼容和完整 Gradle 基线要求。
- `docs/wiki/zh/capabilities/compile/source-compile.md` 与英文镜像
  - 在源码编译支持项中补充 Hilt 版本范围，并链接注解器页面的详细边界。

### 独立验证 Demo

- `/Users/wormchen/IdeaProjects/demo/HiltDemo/settings.gradle.kts`
- `/Users/wormchen/IdeaProjects/demo/HiltDemo/build.gradle.kts`
- `/Users/wormchen/IdeaProjects/demo/HiltDemo/gradle.properties`
- `/Users/wormchen/IdeaProjects/demo/HiltDemo/gradle/wrapper/gradle-wrapper.properties`
- `/Users/wormchen/IdeaProjects/demo/HiltDemo/app/build.gradle.kts`
  - 使用 `-PhiltVersion=` 在 Hilt `2.48.1` 与新版基线间切换插件和依赖版本。
  - 固定到 AGP `7.3.1`、Gradle `7.4`、Kotlin `1.9.22`，让旧版 Hilt visitor 能读取该工具链的生成 class 路径，同时保持 `2.51.1` 基线可构建。
  - 使用 AGP `7.3.1` 同期的 AndroidX 与 `compileSdk 33`，避免新版 AndroidX class 产物超出旧 D8 的处理范围。
  - 要求 Gradle 使用 JDK 17，不写入机器专属 `org.gradle.java.home`。
- `/Users/wormchen/IdeaProjects/demo/HiltDemo/app/src/main/java/com/jugg/demo/hilt/MainActivity.kt`
  - 展示当前构建使用的 Hilt 版本，避免安装不同基线后误判。
- `/Users/wormchen/IdeaProjects/demo/HiltDemo/app/src/main/java/com/jugg/demo/hilt/GreetingReceiver.kt`
  - 增加可直接修改的普通逻辑 revision 文案，同时保留注入值，验证入口转换和注入均生效。
- `/Users/wormchen/IdeaProjects/demo/HiltDemo/README.md`
  - 增加旧版、新版完整构建和 Jugg Receiver 增量验证步骤。

## 4. 验证策略

- 失败证据：旧字段 marker 生成父类转换后缺少 `Hilt_*#onReceive()` 调用。
- 测试价值：Hilt 版本兼容和 Receiver 注入是稳定外部契约，适合 L1 字节码行为测试。
- L1 owner：`HiltAndroidEntryPointTransformerTest`。
- 定向回归：Hilt transformer、TransformerCompiler 和既有 SourceCompile Hilt 用例。
- 编译验证：`:idea:compileKotlin`。
- Demo 验证：分别使用 Hilt `2.48.1` 和 `2.51.1` 完成 `:app:assembleDebug`；切换版本前执行完整 Gradle 构建刷新 Hilt 生成物。
- Wiki 验证：中英文镜像校验、链接检查、production build、`git diff --check`。

## 5. 不在范围内

- 不运行 Hilt/Dagger APT、KAPT 或 KSP。
- 不根据依赖或插件版本添加条件分支。
- 不扩大到 Hilt `2.40.x` 的真实构建矩阵。
- 不为每个 Hilt patch 版本增加独立测试工程或 CI matrix。
- 不修改 Hilt 注入图变化仍需完整 Gradle 构建的边界。

## 6. 实施中发现的工具链约束

Hilt `2.48.1` 在原 Demo 的 AGP `8.7.3` 上执行官方 `transformDebugClassesWithAsm` 时，会从已失效的 `intermediates/javac/debug/classes` 路径读取 `Hilt_GreetingReceiver.class` 并失败。该问题发生在完整 Gradle/Hilt 基线生成阶段，不属于 Jugg transformer。Demo 因此使用同时兼容 `2.48.1` 与 `2.51.1` 的较早工具链，不把外部插件失败误判为 Jugg 兼容结果。

切换到 AGP `7.3.1` 后，原 Demo 使用的 AndroidX `1.7/1.8/2.8` 产物又超出该版本 D8 的处理范围。Demo 同步固定到 AndroidX 同期稳定版本和 `compileSdk 33`；入口类型、注入关系与 Receiver marker 验证目标不变。

AGP `7.3.1` 在 JDK 21 下执行 `JdkImageTransform` 失败。Demo 文档明确 Gradle JDK 17 前置条件，但不把本机 JDK 绝对路径写入工程配置。

## 7. 实施结果

- TDD 失败证据：新增旧字段 marker 用例后，转换结果没有插入生成父类的 `onReceive()` 调用，定向测试按预期失败。
- Jugg 定向回归：Hilt transformer、TransformerCompiler、SourceCompile Hilt 用例以及 `:idea:compileKotlin` 全部通过。
- Demo 完整构建：Hilt `2.48.1` 与 `2.51.1` 均在 JDK 17 下完成 `clean :app:assembleDebug`。
- 生成物核对：`2.48.1` 的 `Hilt_GreetingReceiver` 包含私有 boolean 字段 marker；`2.51.1` 包含 `OnReceiveBytecodeInjectionMarker` 类注解。
- 文档同步：AI 知识库与中英文 Wiki 均标注 Hilt `2.41`～`2.60.1` 的源码级兼容范围和完整 Gradle 构建边界。
