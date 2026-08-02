# Application 接入模式与字节码插桩方案评估

> 日期：2026-07-21  
> 状态：方案评估完成，尚未进入实施

## 1. 背景

Jugg 当前在 Gradle 完整构建时将 Manifest 中的 `Application` 替换为
`com.sickworm.intellij.jugg.hotfix.BootstrapApplication`。Bootstrap Application 会在用户 Application 创建前安装增量 DEX、资源和 native lib 路径，再创建用户原始 Application，并通过反射替换 Android Runtime 中保存的 Application 实例。

该方案能保证兼容热修复尽可能早地接入 ClassLoader，但会修改以下运行时状态：

- Manifest 中的 `application android:name`。
- 存在自定义 `AppComponentFactory` 时的 `android:appComponentFactory`。
- `ActivityThread.mInitialApplication`、`mAllApplications`、`LoadedApk.mApplication` 等内部字段。
- ContentProvider 启动阶段可见的 Application 实例。
- ActivityLifecycleCallbacks 的注册和迁移过程。

这些修改可能造成部分 SDK、框架或业务代码观察到非预期的 Application 类型、实例或生命周期时序。因此希望增加一种不替换 Application 的接入方式，同时保留当前方案作为默认行为。

## 2. 已评估方案

### 2.1 用户手动初始化

用户在自己的 Application 中主动调用：

```kotlin
override fun attachBaseContext(base: Context) {
    JuggRuntime.initialize(base)
    super.attachBaseContext(base)
}
```

该方案能够避免替换 Application，但存在以下问题：

- 用户源码有侵入。
- 当前 Jugg runtime 只在 Jugg Gradle 构建时以 `runtimeOnly` 注入，普通 Gradle Sync 和 CI 无法解析直接引用的 API。
- 用户可能遗漏调用，或错误地放在 `Application.onCreate()` 中。
- 初始化缺失时兼容部署可能静默失效。
- 多进程项目需要保证每个目标进程都执行初始化。

结论：技术可行，但不适合作为首选产品方案。

### 2.2 Application class 字节码插桩

Jugg 在 Application class 编译完成后、进入 D8/R8 前插入初始化调用，用户不需要修改源码。

建议插入两个生命周期点：

```text
Application.attachBaseContext() 入口
  -> JuggRuntime.attachBaseContext(base)

Application.onCreate() 正常返回前
  -> JuggRuntime.applicationCreated(application)
```

如果目标 Application 没有重写对应方法，Transformer 生成 override，并保持父类生命周期调用。

该方案可以：

- 保持 Manifest 中声明的用户 Application。
- 不创建 Bootstrap 和用户 Application 两个实例。
- 不替换 AppComponentFactory。
- 不反射修改 ActivityThread 和 LoadedApk 中的 Application 引用。
- 不要求用户修改源码或增加编译依赖。

结论：这是当前推荐方向，但完整实现的主要难度在 Gradle/AGP 全量构建插桩，而不是 ASM 指令本身。

### 2.3 临时反射 Helper

为验证 ContentProvider 创建阶段能否取得 Bootstrap Application 内部保存的原始 Application，已在 demo 工程增加临时 Helper：

- `android_demo_project/app/src/main/java/com/sickworm/jugg/demo/testcase/applicationcontext/BootstrapApplicationHelper.kt`
- `android_demo_project/app/src/main/java/com/sickworm/jugg/demo/testcase/applicationcontext/ApplicationContextProbeProvider.kt`

Helper 通过反射读取 `BootstrapApplication#rawApplication`，反射失败或字段尚未初始化时返回传入的 Application。该代码仅用于观察和临时兼容，不替代正式接入方案。

## 3. 当前推荐架构

新增 Application 接入模式，默认保持现有替换行为：

```text
ApplicationBootstrapMode
  ├─ REPLACE
  │    └─ 保持 BootstrapApplication 现有方案
  └─ BYTECODE
       ├─ Gradle 完整构建：AGP class transform
       └─ Jugg 增量构建：class 输出后、D8 前 transform
                            ↓
                  ApplicationClassInstrumenter
                            ↓
                       JuggRuntime
```

UI 可以表现为“Replace Application”开关，但内部建议使用枚举，避免 Boolean 语义继续扩张。

Gradle 参数建议为：

```text
-Pjugg.application.bootstrap.mode=replace
-Pjugg.application.bootstrap.mode=bytecode
```

不能直接复用 `jugg.inject.application.enable=false`。现有参数关闭后会同时跳过 runtime jar 注入、Manifest 处理和相关兼容能力，无法满足“保留兼容部署但不替换 Application”的需求。

## 4. 修改清单

### 4.1 设置与模式切换

| 文件 | 修改内容 |
|---|---|
| `main/.../ide/bean/JuggSettings.kt` | 新增持久化 bootstrap mode，默认 `REPLACE` |
| `idea/.../ide/logic/MoreOptionsManager.kt` | 增加开关；切换后清理 deploy history，并强制下一次完整构建 |
| `main/.../gradle/compile/SshCommand.kt` | 向本地和远端 Gradle 命令传递 bootstrap mode |
| deploy history 相关模型 | 保存最近一次完整构建使用的 mode，防止设置与设备 APK 不一致 |

模式变化后必须：

1. 禁止继续使用旧增量上下文。
2. 执行 Gradle 完整构建。
3. 完整安装新 APK。
4. 重建 deployment cache 和 overlay checkpoint。

### 4.2 公共字节码 Transformer

建议新增纯 JVM 模块，例如 `application_instrumenter`，由 Gradle 全量构建和 Jugg 增量编译共同使用。

核心类：

```text
ApplicationClassInstrumenter
```

职责：

- 根据 class internal name 精确匹配 Application。
- 在已有 `attachBaseContext(Context)` 入口插入调用。
- 缺少 `attachBaseContext(Context)` 时生成 override。
- 在已有 `onCreate()` 的正常返回前插入调用。
- 缺少 `onCreate()` 时生成 override。
- 保持直接父类生命周期调用。
- 更新 `maxStack` 并保持合法 StackMapFrame。
- 写入插桩 marker 和版本。
- 重复处理同一个 class 时不重复插入。
- 非目标 class 原样返回。

全量和增量必须共用同一个 Transformer，禁止维护两套 ASM 实现。

### 4.3 Gradle 完整构建

涉及文件：

| 文件 | 修改内容 |
|---|---|
| `main/.../gradle/script/GradleApplicationInjector.kt` | 按 mode 选择 Manifest 替换或 class transform；两种模式都继续注入 runtime jar |
| `main/.../gradle/script/InitScriptManifestXmlHelper.kt` | 读取并规范化 merged manifest 中的 Application 类名 |
| `main/.../project/JuggPathManager.kt` | 增加 Gradle instrumenter jar 路径 |
| `main/.../gradle/compile/GradleScriptWriter.kt` | 将 instrumenter jar 写入工程稳定目录 |
| `main/buildReadProjectInfoScript.gradle` | 将新的 Gradle 逻辑同步到生成脚本 |
| `main/src/main/resources/gradle/readProjectInfo.gradle.kts` | 由生成任务更新 |
| 新增 Gradle instrumentation adapter | 注册 Android Components class transform |

正式实现必须使用独立 transform 输出，不能在 compile task 的 `doLast` 中原地覆盖 javac/Kotlin 输出目录。

原地覆盖会产生严重污染：用户之后不带 Jugg init script 执行普通 Gradle 构建时，可能复用已插桩 class，但 APK 中没有 Jugg runtime，启动时将出现 `NoClassDefFoundError`。

Gradle transform 需要覆盖当前支持范围，至少验证：

- AGP 7.2 / Gradle 7。
- AGP 8.7 / Gradle 9。
- AGP 9.0 移除 `applicationVariants` 后的路径。
- Java、Kotlin、KAPT/KSP 生成 class。
- build cache 和 configuration cache。

首版建议仅支持 Application 位于 app 工程输出中。Application 位于依赖 AAR/JAR 时暂不支持，后续再评估 `InstrumentationScope.ALL`。

### 4.4 Application 类名解析

必须读取对应 variant 的 merged manifest，而不是源码 Manifest，并正确解析：

```xml
android:name=".MyApplication"
android:name="MyApplication"
android:name="com.example.MyApplication"
android:name="${customApplication}"
```

规则：

- 相对类名根据 merged manifest package 补全。
- placeholder 应在 merged manifest 中完成解析。
- 仍存在未解析 placeholder 时构建失败。
- 不同 variant 单独解析，不复用全局 Application 名称。
- androidTest APK 不插桩目标 app 的 Application。

### 4.5 Jugg 增量编译

修改 `main/.../compiler/source/SourceCompiler.kt`：

```text
Java/Kotlin 编译
  -> 收集 classCompileResult
  -> ApplicationClassInstrumenter
  -> DexCompiler
  -> DexMinifyCompiler（release）
```

Jugg 增量 class 位于 Jugg 自有临时目录，可以在进入 D8 前原地处理。

要求：

- 本轮没有编译 Application 时不做额外操作。
- Application 本轮重新编译时必须重新插桩。
- release/minified 变体保持相同调用目标。
- 插桩后的 class 必须继续携带正确 `baseDir`、module 和 APK 归属。

### 4.6 Runtime 入口

建议新增：

```text
jvmti_agent/src/main/java/com/sickworm/intellij/jugg/hotfix/JuggRuntime.java
```

公开方法：

```java
public static void attachBaseContext(Context base)
public static void applicationCreated(Application application)
```

`attachBaseContext()` 负责：

- API 支持判断。
- `HotfixLoader.init()`。
- 判断是否启用兼容热修复。
- 安装 DEX、资源和 native lib 路径。
- 进程级幂等。

`applicationCreated()` 负责：

- 初始化 ViewHierarchy Server。
- 写入 runtime 初始化成功标记。
- 进程级幂等。

现有 `BootstrapApplication` 也应改为调用同一个 `JuggRuntime`，避免两种模式长期产生行为差异。

### 4.7 R8 / ProGuard

必须保留稳定的 runtime 调用入口：

```proguard
-keep class com.sickworm.intellij.jugg.hotfix.JuggRuntime {
    public static void attachBaseContext(android.content.Context);
    public static void applicationCreated(android.app.Application);
}
```

如果入口被 R8 inline、删除或改名，后续 Jugg 增量插入的调用可能出现 `NoSuchMethodError`。

字节码模式不再依赖 metadata 反射恢复用户 Application，因此可以评估移除当前“keep 所有 Application/AppComponentFactory 子类”的宽泛规则，仅保留 runtime 精确规则。

### 4.8 Runtime 与 Agent 版本

新增或修改 `jvmti_agent` Java runtime 后必须递增根工程 `agentVersion`，确保设备不会复用旧 bundle。

同时验证：

- `jugg-runtime.jar` 包含新入口。
- `jugg-instruments.jar` 与 runtime 版本一致。
- Gradle instrumenter 使用的 owner/method descriptor 与 runtime 一致。

### 4.9 初始化握手与部署保护

建议新增初始化成功标记，例如：

```text
code_cache/.jugg_runtime_initialized
```

字节码模式下，IDE 在首次兼容部署前检查：

- 标记存在：允许兼容热修复。
- 标记不存在：提示插桩未生效并要求完整构建。
- 禁止静默继续运行旧代码。

可能涉及：

- `BuildConfig` 新增 flag 名称。
- `JuggJvmtiAgentManagerHelper` 增加检查。
- `JuggDeployerHelper` 在 compat deploy 前增加保护。
- reinstall 时清理旧标记。

## 5. 固有能力差异

字节码方案无法完全复制 Bootstrap 方案的最早加载时机。

系统在调用被插桩的 `attachBaseContext()` 前，已经完成：

- 用户 Application class 加载。
- Application 实例构造。
- 静态初始化。
- 成员字段初始化。
- 上述过程引用的部分依赖类加载。

因此在 JVMTI 不可用、真正依赖经典兼容热修复的设备上，以下变更不能通过重启热修复可靠覆盖：

- Application 类本身变化。
- AppComponentFactory 变化。
- Application 构造、静态或字段初始化阶段提前加载的类变化。

处理原则：

- Application 或 AppComponentFactory 变化时强制 Gradle 完整构建和安装。
- 提前加载依赖的精确检测较复杂，首版以文档边界和保守 fallback 为主。
- JVMTI 正常设备仍可由 startup agent 在 Application 创建前介入，因此影响主要集中在兼容设备。

## 6. 副作用与风险

| 风险 | 严重程度 | 是否可规避 | 处理方式 |
|---|---:|---:|---|
| Application 类在初始化前已经加载 | 高 | 否 | Application 变化强制完整安装 |
| 构造、静态和字段初始化依赖提前加载 | 高 | 不能完全 | 文档说明并保守 fallback |
| 未声明自定义 Application | 高 | 否 | 禁止 BYTECODE 模式或关闭兼容部署 |
| Application 位于依赖 AAR/JAR | 高 | 可做但复杂 | 首版不支持，后续评估 scope ALL |
| Gradle/AGP API 跨版本差异 | 高 | 可规避 | adapter + AGP 7/8/9 fixture |
| 原地修改 compiler 输出污染普通构建 | 高 | 可规避 | 使用独立 AGP transform 输出 |
| 全量与增量插桩逻辑不一致 | 高 | 可规避 | 共用同一个 Transformer |
| R8 删除或 inline runtime 入口 | 高 | 可规避 | 精确 keep JuggRuntime |
| 模式切换后继续复用旧 APK | 高 | 可规避 | mode 写入 history，强制 full build |
| 插桩重复 | 中 | 可规避 | marker + runtime 幂等 |
| Gradle cache 复用错误模式产物 | 中 | 可规避 | mode/version 作为 transform input |
| 第三方 ASM 插件执行顺序冲突 | 中 | 部分可控 | 进入 AGP instrumentation pipeline |
| 生成 override 改变方法栈和堆栈信息 | 中 | 部分可控 | 保持父类调用和原生命周期顺序 |
| 多进程重复初始化 | 中 | 属于预期 | 进程级幂等，每个进程独立初始化 |
| runtime 初始化异常导致启动失败 | 中 | 策略选择 | compat patch 失败建议 fail-fast |
| 壳、反篡改或字节码完整性校验 | 中 | 难以通用处理 | 明确为不支持或需专项适配 |
| ViewHierarchy 启动时机变化 | 低 | 可规避 | 在 onCreate 正常返回前调用 |
| 构建耗时增加 | 低 | 可控 | 精确过滤一个目标 class |

## 7. 首版范围建议

首版支持：

- 默认继续使用 `REPLACE`。
- 用户可切换为 `BYTECODE`。
- 必须声明自定义 Application。
- Application 必须位于 app 工程 class 输出中。
- 支持 Java、Kotlin以及生成到 app class 目录的 Application。
- 同时插桩 `attachBaseContext()` 和 `onCreate()`。
- Application/AppComponentFactory 变化强制完整构建。
- 实现 runtime 初始化握手。
- 覆盖 AGP 7.2、8.7、9.0。

首版不支持：

- Application 位于依赖 AAR/JAR。
- 未声明自定义 Application 时自动生成替代类。
- 插桩失败后静默回退 Bootstrap 替换。
- 原地覆盖 javac/Kotlin 输出目录。
- 对 Application 构造阶段所有提前加载依赖做精确影响分析。

## 8. 测试清单

### 8.1 L1 字节码测试

建议新增 `ApplicationClassInstrumenterTest`，覆盖：

- 已有和缺少 `attachBaseContext()`。
- 已有和缺少 `onCreate()`。
- Java/Kotlin class。
- 非目标 class。
- 重复插桩。
- 插桩后 ASM 校验和 D8 编译。

### 8.2 Gradle 兼容测试

扩展：

- `ReadProjectInfoGradle7CompatTest`
- `ReadProjectInfoGradle9CompatTest`
- `ReadProjectInfoScriptContentTest`

验证：

- REPLACE 模式继续修改 Manifest。
- BYTECODE 模式保持原 Application。
- BYTECODE 模式输出 class 含 runtime 调用。
- 普通 Gradle 构建不包含插桩。
- runtime jar 正确进入 APK。
- 模式和插桩版本参与 cache input。

### 8.3 Runtime 测试

验证：

- 两阶段初始化幂等。
- API 低于 26 时安全跳过。
- ViewHierarchy 只初始化一次。
- 初始化成功标记写入。
- hotfix 安装失败的 fail-fast/no-op 策略。

### 8.4 L3 主链路

至少覆盖：

```text
Gradle BYTECODE baseline build
  -> install
  -> Provider 启动时看到用户 Application
  -> JuggRuntime 初始化成功
  -> 普通类增量修改
  -> compat deploy
  -> 重启后修改生效
```

另需覆盖 Application 变化和模式切换强制 full build。

## 9. 文档同步范围

实施时至少同步：

- `docs/ai_knowledge/02_compile_source.md`
- `docs/ai_knowledge/03_runtime_jvmti.md`
- `docs/ai_knowledge/04_engineering_ide.md`
- `docs/ai_knowledge/98_code_map.md`
- `docs/wiki/zh/concepts/jugg-runtime.md`
- `docs/wiki/zh/concepts/fallback-and-limits.md`

## 10. 待决问题

进入实施前需要最终确认：

1. BYTECODE 模式在未声明自定义 Application 时，是阻止开启，还是允许开启但关闭兼容部署。
2. Application 位于依赖 AAR/JAR 是否进入首版范围。
3. runtime 安装失败时采用 fail-fast，还是仅在确实存在 compat payload 时 fail-fast。
4. bootstrap mode 写入现有 deploy history，还是新增独立 baseline metadata。
5. Gradle instrumentation adapter 是单一最低版本二进制兼容，还是按 AGP 大版本拆分。
6. 是否在 UI 中直接暴露模式名称，还是继续使用“Replace Application”开关表达。

## 11. 当前结论

Application class 字节码插桩方案技术上成立，能够解决 Application 实例替换、AppComponentFactory 代理和 Android Runtime 私有字段反射带来的问题，同时保持用户源码无侵入。

该方案不能解决的核心差异是：用户 Application 及其构造阶段依赖已经在 runtime 初始化前加载。因此它不能在 JVMTI 不可用设备上完全复制 Bootstrap 方案的兼容热修复覆盖范围。

若接受该边界，推荐以 `REPLACE` 为默认模式，以 `BYTECODE` 为用户主动选择的可选模式，并优先保证 Gradle 独立 transform、全量/增量共用 Transformer、模式切换强制 full build 和 runtime 初始化握手四项基础能力。
