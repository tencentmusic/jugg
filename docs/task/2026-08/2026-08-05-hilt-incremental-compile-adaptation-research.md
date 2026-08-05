# Hilt 增量编译适配调研

> 状态：调研完成，本次不实施
> 更新时间：2026-08-05

## 1. 背景

Jugg 当前只支持少量明确识别的注解处理场景。Dagger/Hilt 被列为不支持的增量注解处理器：普通源码变化可能生效，但新增、删除或修改依赖注入声明时不会重新生成完整依赖图，生成代码可能保持旧状态。

本次调研聚焦两个问题：

1. Hilt 除注解处理器外，是否依赖 ASM 字节码修改。
2. Jugg 能否直接启用已有 KAPT/KSP 链路完成 Hilt 适配，以及 `IJuggAptProcessor` 是否适合作为完整实现入口。

## 2. 结论

### 2.1 核心结论

- Hilt 在常规 Gradle 插件模式下同时依赖注解处理和 ASM 字节码修改。
- `@Inject`、`@Module`、`@InstallIn`、`@HiltViewModel` 等主要依赖 Hilt/Dagger 注解处理器。
- `@AndroidEntryPoint` 和 `@HiltAndroidApp` 除生成 `Hilt_*` 基类外，通常还由 Hilt Gradle 插件修改原 class 的父类和 `super` 调用。
- Jugg 已有的 KAPT 基础链路可以实际运行 processor，适合作为首个 PoC 的基础。
- KSP1 可以通过现有 Kotlin compiler plugin 路径尝试；KSP2 当前没有独立 runner，只读取 Gradle 已生成目录，不能通过打开开关真正执行处理器。
- 仅打开 KAPT/KSP 只能完成当前编译轮次的局部代码生成，不能自动完成 Hilt 的跨 module classpath 聚合、根组件生成和 Android EntryPoint Transform。
- `IJuggAptProcessor` 只能在语言编译前返回当前 module 的 Java/Kotlin generated source，不适合作为完整 Hilt 适配 owner。

### 2.2 推荐方向

短期先增加正确性保护，对 Hilt 依赖图变化和 Hilt Android EntryPoint 变化执行 Gradle fallback。若继续投入原生增量支持，建议按以下顺序演进：

1. 基于现有 KAPT 链路验证局部 Hilt/Dagger 产物生成。
2. 增加 app root 的跨 module 聚合与组件生成阶段。
3. 在语言编译和 dex 之间增加 Hilt AndroidEntryPoint ASM Transform。
4. 最后评估 KSP2 独立执行能力。

## 3. Hilt 的完整编译模型

### 3.1 注解处理阶段

Hilt/Dagger 会根据不同注解生成多类产物：

| 类型 | 典型注解 | 主要产物或影响 |
|---|---|---|
| Android 根与入口 | `@HiltAndroidApp`、`@AndroidEntryPoint` | `Hilt_*` 基类、generated injector、应用根关联 |
| 依赖构造与成员注入 | `@Inject` | `_Factory`、`_MembersInjector`、依赖请求 |
| Module 与组件安装 | `@Module`、`@InstallIn`、`@Provides`、`@Binds` | binding metadata、aggregated deps、组件 module 列表 |
| ViewModel | `@HiltViewModel` | ViewModel binding module、factory 依赖 |
| EntryPoint 与自定义组件 | `@EntryPoint`、`@DefineComponent` | 组件接口、组件树 metadata |
| 测试能力 | `@HiltAndroidTest`、`@TestInstallIn`、`@UninstallModules`、`@BindValue` | 测试根、替换 binding、测试组件 |

Hilt 不只处理当前源码文件。`@InstallIn` module 和 EntryPoint 会先产生聚合 metadata，应用根编译阶段再从传递 classpath 收集这些 metadata，生成 `ComponentTreeDeps` 和最终组件树。

### 3.2 Gradle 聚合阶段

当前 Hilt Gradle 插件默认开启 `enableAggregatingTask`。典型链路为：

```text
各 module KAPT/KSP/Javac processor
  -> 生成 Factory、MembersInjector、AggregatedDeps 等局部产物
  -> hiltAggregateDeps<Variant> 扫描完整传递 classpath
  -> 生成 ComponentTreeDeps source
  -> hiltJavaCompile<Variant> 运行 Hilt/Dagger processor
  -> 生成 HiltComponents、Dagger*_HiltComponents_*、Hilt Application 等根产物
```

这意味着 library 中新增一个 `@InstallIn` module，也可能要求 application module 重新生成 SingletonComponent。单 module、单轮 KAPT 不能独立证明依赖图已经更新。

### 3.3 ASM Transform 阶段

使用 Hilt Gradle 插件时，用户源码通常直接继承 Android 基类：

```kotlin
@AndroidEntryPoint
class MainActivity : AppCompatActivity()
```

注解处理器生成：

```text
Hilt_MainActivity extends AppCompatActivity
```

Hilt Gradle 插件再通过 ASM 将已编译 class 改为：

```text
MainActivity extends Hilt_MainActivity
```

同时还会修正构造器和普通 `super` 调用；BroadcastReceiver 还存在额外的 `onReceive` 注入逻辑。因此重新编译 `@AndroidEntryPoint` class 后，如果不重新执行该 Transform，即使注解和依赖图没有变化，也可能丢失注入入口。

不使用 Hilt Gradle 插件时，可以让源码显式继承 `Hilt_*` 生成类，从而避免 ASM Transform。但这不是常规项目的默认写法，Jugg 不能据此假设所有项目都不需要 Transform。

## 4. Jugg 当前实现事实

### 4.1 Java APT 默认关闭

`main/src/main/java/com/sickworm/intellij/jugg/compiler/source/JavaCompiler.kt` 创建 `JavaCompilerInvoker.Options` 时固定：

```kotlin
isEnableApt = false
```

因此普通 Java 增量编译最终使用 `-proc:none`。虽然 module 已保存 `annotationProcessorDependencies`、`kaptDependencies` 和 processor options，但不会执行通用 Java annotation processor。

### 4.2 普通 Kotlin KAPT 默认关闭

`KotlinCompilerInvoker` 已实现以下 KAPT 基础能力：

- KAPT sources/classes/stubs/incrementalData 目录。
- processor classpath。
- `javaAnnotationProcessorOptions`、`kaptArguments` 和额外 KAPT options 合并。
- generated Java source 和 class 输出收集。
- 独立 JVM 执行模式。

DataBinding 已经通过该能力运行一次性隔离 KAPT，证明 KAPT 基础设施可实际工作。

但 `KotlinCompiler.analyzeSource()` 构造 `KotlinCompilerInvoker.Options` 时，第一个 `isEnableKapt` 参数固定为 `false`，普通 Kotlin 源码不会进入 KAPT。

### 4.3 KSP 只有白名单场景

当前 `KotlinCompiler.analyzeSource()` 只在 import 命中 `com.squareup.moshi.JsonClass` 且 module 存在 KSP 依赖时启用 KSP。Hilt 注解不会触发该路径。

KSP 后端还存在版本差异：

| 模式 | 当前行为 |
|---|---|
| KSP1 | 通过 Kotlin compiler plugin 参数运行 processor |
| KSP2 | 不启动独立 KSP2 runner；读取 `build/generated/ksp/<variant>` 等 Gradle 产物 |

因此即使把 Hilt 加入 KSP 触发白名单，现代 KSP2 项目仍不会由 Jugg 真正执行 Hilt processor。

### 4.4 JuggApt 的能力边界

`IJuggAptProcessor` 接收当前 compile context、当前 module 和当前轮文件，只能返回需要在同轮编译的 Java/Kotlin generated source。

`JuggAptCompiler` 的现有特征包括：

- 按当前 module 执行。
- 只接受 Java/Kotlin 输出。
- 在语言编译前运行。
- processor 异常时 fail-open，记录 warn 后继续主编译。
- generated source 引发直接源码诊断时，可以移除本轮 generated source 并重试一次。

当前注册表只有 `KuiklyPageJuggAptProcessor`。该模型适合改写一个已知 generated aggregation source，但不包含 processor rounds、跨 module root 调度、generated output 删除和 post-class Transform。

## 5. 直接启用 KAPT/KSP 的可行性

### 5.1 KAPT

KAPT 是当前最适合先验证的后端。打开 `isEnableKapt` 后，理论上可以执行 Hilt/Dagger processor，并生成当前输入对应的 Factory、MembersInjector 和部分 Hilt metadata。

但直接打开开关仍有以下缺口：

1. 当前 compile task 主要包含本轮 changed Kotlin 和 JuggApt Kotlin，不等同于 Gradle KAPT task 的完整输入。
2. library processor 输出需要进入 app root 的传递 classpath，再触发根组件生成。
3. Hilt Gradle 插件会向具体 variant task 注入 processor flags；当前 Jugg 读取的 extension/defaultConfig 参数不保证完整覆盖 task argument provider。
4. 默认 aggregating task 模式会设置 `dagger.hilt.internal.useAggregatingRootProcessor=false`，只运行普通 KAPT 而不生成新的 `ComponentTreeDeps`，根组件会继续使用旧输入。
5. 处理器成功后仍缺 AndroidEntryPoint ASM Transform。

所以 KAPT 可以作为 Hilt 适配的第一段链路，但不是单开关方案。

### 5.2 KSP1

KSP1 可以沿现有 compiler plugin 参数路径尝试，主要工作包括：

- 扩展触发判断，不再只识别 Moshi。
- 读取并传递 Hilt Gradle 插件设置的 KSP processor options。
- 将 KSP 生成的 Java/Kotlin/class/metadata 纳入后续聚合输入。

它仍然面临与 KAPT 相同的跨 module root aggregation 和 ASM Transform 问题。

### 5.3 KSP2

当前实现没有执行 KSP2 的基础设施，打开 `isEnableKsp` 不会启动 processor。要原生支持 Hilt KSP2，需要新增独立 KSP2 runner 或 Kotlin toolchain worker，至少解决：

- 项目 Kotlin/KSP2 runtime 加载。
- processor classpath 和 processor options。
- source/classpath/output/caches 配置。
- structured diagnostics。
- 跨版本兼容和进程隔离。

在 KAPT PoC 和聚合模型没有验证前，不建议先投入 KSP2。

## 6. `IJuggAptProcessor` 是否可以完成 Hilt 适配

结论是不可以单独完成，原因如下：

1. **输出类型不足**：只能返回 Java/Kotlin source，无法修改 Kotlin/Java 编译后的 class。
2. **时机不足**：只在语言编译前运行，无法承担 AndroidEntryPoint post-class Transform。
3. **作用域不足**：按当前 module 运行，无法自然表达 library metadata 变化后重编 app root component。
4. **缺少删除语义**：binding 或注解删除后，需要清理旧 Factory、AggregatedDeps 和组件引用；接口只能返回新增或改写文件。
5. **缺少替换语义**：不能安全地用 shadow source 替换原始 `@AndroidEntryPoint` class；同时编译相同 FQCN 会产生重复类。
6. **失败策略不匹配**：JuggApt 是 fail-open，Hilt 图生成失败后继续使用旧组件可能形成编译成功、运行时注入错误。
7. **没有 processor round 模型**：Hilt/Dagger 依赖多轮生成和 generated element 再处理，普通 source rewriter 无法等价模拟。

`IJuggAptProcessor` 最多作为未来 Hilt 编译器中的辅助能力，例如登记或交接少量 generated source，不应成为完整 Hilt behavior owner。

## 7. 可选方案比较

| 方案 | 正确性 | 实现成本 | 性能 | 结论 |
|---|---|---|---|---|
| 用 `IJuggAptProcessor` 手工改写 Hilt generated source | 低 | 高且版本敏感 | 可能较快 | 排除 |
| 只打开现有 KAPT/KSP 开关 | 只能覆盖局部生成 | 低 | 快 | 适合 PoC，不可直接发布 |
| Hilt 变化统一 Gradle fallback | 高 | 低 | 慢 | 推荐作为第一阶段正确性保护 |
| 定向执行 Gradle Hilt/KAPT/KSP task 并收集产物 | 高 | 中 | 中 | 推荐作为首个可发布适配方向 |
| Jugg 内重建完整 Hilt processor、aggregation、Transform 链 | 理论上高 | 很高 | 可优化 | 长期方案，需要真实收益驱动 |

## 8. 推荐分阶段方案

### P0：正确性保护

识别项目是否使用 Hilt，并对以下变化强制 Gradle fallback：

- `@HiltAndroidApp`、`@AndroidEntryPoint` class 被重新编译。
- 新增、删除或修改 `@Inject` constructor/field。
- 修改 `@Module`、`@Provides`、`@Binds`、`@InstallIn`、`@EntryPoint`、`@HiltViewModel` 等依赖图声明。
- 删除 Hilt/Dagger annotated type 或 generated owner。
- 无法确定变化是否只影响普通方法体。

这一步先消除静默使用旧 generated graph 的风险。

### P1：KAPT 局部生成 PoC

目标不是立即支持完整 Hilt，而是验证现有 KAPT invoker 能否稳定加载项目 Hilt/Dagger processor。

建议场景：

1. Kotlin `@Inject constructor` 类新增一个依赖。
2. 打开 KAPT 并使用独立 JVM。
3. 检查新的 `_Factory.java`、`_Factory.class` 和 aggregated metadata。
4. 明确区分局部 processor 成功与根组件仍然过期。

PoC 必须记录实际 processor options、processor classpath、JDK、Kotlin、KAPT 和 Hilt 版本。

### P2：根组件聚合

有两条路径：

#### 路径 A：Gradle 辅助式

执行 variant 对应的 Hilt/KAPT/KSP 定向任务，让 Hilt Gradle 插件继续负责完整 classpath aggregation 和 root generation，再由 Jugg 收集 generated class/source/dex 输入。

优点是复用官方版本匹配逻辑，正确性风险最低。缺点是会进入 Gradle task graph，实际耗时需 benchmark。

#### 路径 B：Jugg 原生聚合

Jugg 读取所有 module 的 Hilt aggregated metadata，生成 `ComponentTreeDeps`，再运行 Hilt/Dagger processor 生成根组件。

该路径必须兼容不同 Hilt 版本的 metadata package、annotation schema、processor flags 和 aggregating mode，不建议作为首个版本。

### P3：AndroidEntryPoint Transform

在 Kotlin/Java class 输出和 DexCompiler 之间增加专用 Hilt class transformer：

```text
Kotlin/Java compile
  -> HiltAndroidEntryPointTransformer
  -> DexCompiler
```

至少需要验证：

- 父类改为 `Hilt_*`。
- generic signature 同步修改。
- 构造器首个 `invokespecial` owner 修改。
- 普通 `super` 调用 owner 修改。
- BroadcastReceiver `onReceive` 特殊逻辑。
- 缺少对应 `Hilt_*` class 时 fail-closed 或 Gradle fallback。

### P4：KSP2

在 KAPT、聚合和 Transform 已经形成稳定契约后，再决定是否通过独立 Kotlin worker 支持 KSP2。不能继续使用“读取 Gradle generated 目录”冒充本轮 processor 已执行。

## 9. 失败与回退策略

Hilt 属于正确性关键链路，不应沿用 JuggApt 的 fail-open 策略。

建议规则：

- 局部 processor、metadata 聚合、root generation 或 Transform 任一必要阶段失败，停止 Hilt 增量路径。
- 如果尚未写入或部署部分新产物，可以回退 Gradle build。
- 已产生临时 generated output 时只删除本轮临时目录，不修改 Gradle baseline。
- 缺少 Hilt plugin 参数、无法识别 Hilt 版本或发现未知 metadata schema 时直接 fallback。
- 不伪造 processor 成功，不继续部署已知可能使用旧组件图的 class。

## 10. 测试与验证矩阵

| 层级 | 建议 owner | 场景 | 修改前预期 | 完成后预期 |
|---|---|---|---|---|
| L1 | Hilt 变化分类器 | Hilt graph annotation 增删改 | 未识别或继续增量 | 稳定选择 fallback/Hilt 路径 |
| L1 | Hilt class transformer | `@AndroidEntryPoint` class | 父类仍为 Android 基类 | 父类和 `super` 调用指向 `Hilt_*` |
| L1 | KAPT generated output | `@Inject constructor` 依赖变化 | Factory 保持旧签名 | Factory 重新生成并可编译 |
| L2 | Hilt 编译编排 | library binding 变化影响 app root | app root 未触发 | app root aggregation 被调度 |
| L2 | 失败回退 | processor/aggregation/Transform 失败 | 可能继续部署 | fail-closed 并回退 Gradle |
| L3 | `JuggCompilerTest` 或 `TopLevelFlowTest` | 修改 binding 后真实编译部署 | 注入旧值或运行时失败 | 新 binding 在设备运行时生效 |
| L3 | `TopLevelFlowTest` | 修改 Activity 普通方法体 | Transform 丢失导致注入失败 | 注入保持有效且方法体更新 |

完整 Hilt 能力不能只由 `JuggAptCompilerTest` 证明。最终必须有真实 demo、真实 generated outputs、真实 class Transform 和运行时注入结果。

## 11. 已确认事实、待验证事项与非目标

### 11.1 已确认事实

- Jugg 普通 Java APT 和 Kotlin KAPT 当前默认关闭。
- KAPT invoker 已具备实际 processor 执行和 generated output 收集能力。
- KSP 当前只由 Moshi 白名单触发。
- KSP2 当前不执行 processor。
- Hilt Gradle 插件默认存在独立 aggregation task 和 AndroidEntryPoint ASM Transform。
- 当前 `IJuggAptProcessor` 不能承担 post-class Transform 和跨 module root generation。

### 11.2 待验证事项

- `GradleProjectInfoReader` 当前读取的 `kaptArguments` 是否包含 Hilt 插件挂到 variant KAPT task argument provider 的全部参数。
- KSP variant task 的 Hilt processor options 当前是否完全缺失。
- 使用项目 `hilt-compiler` processor classpath 时，现有 KAPT isolated process 是否需要额外 JVM exports/opens。
- 定向运行 Hilt Gradle task 的真实 task graph、耗时和最小可复用产物。
- Hilt 不同版本下 generated metadata 和 ASM Transform 的兼容范围。

### 11.3 非目标

- 不通过源码字符串拼接手工生成 Dagger component。
- 不为单个 Hilt 版本硬编码完整 generated source 模板。
- 不在无法确认依赖图完整性的情况下伪造增量成功。
- 本次不修改 Jugg 编译链、processor 开关或 Gradle fallback 策略。

## 12. 代码与资料入口

### Jugg

- `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/apt/IJuggAptProcessor.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/apt/JuggAptCompiler.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/apt/ProcessorRegistration.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/SourceCompiler.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/JavaCompiler.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/JavaCompilerInvoker.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/kotlin/KotlinCompiler.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/kotlin/KotlinCompilerInvoker.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/kotlin/KspArgsManager.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/gradle/script/GradleProjectInfoReader.kt`
- `docs/skills/jugg-android-dev-loop/references/policy_incremental_compile_limits.md`

### Hilt 官方资料

- [Hilt Gradle Build Setup](https://dagger.dev/hilt/gradle-setup.html)
- [Hilt Application](https://dagger.dev/hilt/application.html)
- [Hilt Android Entry Points](https://dagger.dev/hilt/android-entry-point.html)
- [Hilt Design Overview](https://dagger.dev/hilt/design-overview.html)
- [Hilt Monolithic Components](https://dagger.dev/hilt/monolithic.html)
- [Hilt Gradle Plugin aggregation implementation](https://github.com/google/dagger/blob/11ab714207abb1f48688cea024ba23739a4af166/java/dagger/hilt/android/plugin/main/src/main/kotlin/dagger/hilt/android/plugin/HiltGradlePlugin.kt)
- [Hilt AggregateDepsTask](https://github.com/google/dagger/blob/11ab714207abb1f48688cea024ba23739a4af166/java/dagger/hilt/android/plugin/main/src/main/kotlin/dagger/hilt/android/plugin/task/AggregateDepsTask.kt)
- [Hilt AndroidEntryPointClassVisitor](https://github.com/google/dagger/blob/11ab714207abb1f48688cea024ba23739a4af166/java/dagger/hilt/android/plugin/main/src/main/kotlin/dagger/hilt/android/plugin/transform/AndroidEntryPointClassVisitor.kt)
