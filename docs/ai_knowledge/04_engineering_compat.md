# 工程化：兼容层与命令行模块

> 最后核对：2026-08-07
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页说明 Jugg 如何隔离 IDE / Android Studio API 变动，以及无 IDE 命令行、平台桩、自定义编译器示例这些工程边界如何接入主链路。

本页不展开 install / code swap / direct overlay 的部署业务细节；部署状态机见 `03_deploy_core.md`、`03_deploy_complete.md`、`03_runtime_jvmti.md`。

---

## 2. 核心源码索引

| 类/接口 | 文件 | 作用 |
|---|---|---|
| `AsDeployerCompat` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/AsDeployerCompat.kt` | IDE 侧统一门面；按当前 AS 版本选择优先实现，并在兼容错误时尝试其他版本实现 |
| `AsDeployerCompatDispatcher` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/AsDeployerCompat.kt` | 兼容层方法分发器；显式捕获 AS API 兼容错误后 fallback，避免 JDK Proxy 在启动期反射解析缺失方法签名 |
| `IAsDeployerCompat` | `deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/IAsDeployerCompat.kt` | deploy 兼容层接口，封装 APK provider、install session、swap、IDE deploy state、module info、Java debugger attach 等 AS 版本差异 API |
| `JuggDeployCompatTypes` | `deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployCompatTypes.kt` | 运行时中立 wrapper，承接 `JuggInstallSession`、`JuggOverlayId`、deployment cache entry、deployer exception 等 AS deployer 类型 |
| `JuggDeploymentCacheStore` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeploymentCacheStore.kt` | Jugg 本地源码版 deployment cache；持久化 APK path 与 overlay snapshot，不依赖 AS deployer runtime 类型 |
| `*AsDeployerCompat` | `deploy_compat/v_*/src/main/java/com/sickworm/intellij/jugg/deploy/run/` | 各 Android Studio 版本的具体 API 适配实现 |
| `StubApiGenerator` | `tools/stub_api_generator/` | 从 compat 编译产物引用闭包和显式 Android Studio JAR 目录生成版本化编译 Stub API |
| `IdeVersion` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/AsDeployerCompat.kt` | 用 `ApplicationInfo` 的 product code / API version 选择兼容实现 |
| `PlatformApi` | `main/src/main/java/com/sickworm/intellij/jugg/platform/PlatformApi.kt` | main 层访问平台能力的全局抽象 |
| `IdeaPlatformApi` | `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/IdeaPlatformApi.kt` | IDE 运行时的 `PlatformApi` 实现 |
| `CmdPlatformApi` | `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/CmdPlatformApi.kt` | 命令行运行时的 `PlatformApi` 实现 |
| `IDeviceAdb` / `IdeaDeviceAdb` / `IdeaDeviceAdbClient` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/IDeviceAdb.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/deploy/IdeaDeviceAdb.kt`, `idea/src/main/java/com/sickworm/intellij/jugg/deploy/IdeaDeviceAdbClient.kt` | 设备 ADB 语义抽象；IDE 侧通过 `IDevice` 封装 shell/push/pid/arch/uninstall，不再把这些 transport 能力挂在 deployer compat 上 |
| `CmdLine` | `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/CmdLine.kt` | 命令行入口，分发 `buildGradleBase` / `buildIncrementalApk` |
| `BuildGradleBaseCommand` / `BuildIncrementalApkCommand` | `cmd_line/src/main/java/com/sickworm/intellij/jugg/cmdline/` | CI 两阶段构建：建立可复用基线，再以调用方显式变更文件生成增量 APK |
| `CustomCompilerManager` / `ICompilerCreator` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/custom/` | 自定义编译器 SPI 装载与生命周期管理 |

---

## 3. 核心边界模型

### 3.1 deploy_compat 版本层级

| 目录 | 适配版本 |
|---|---|
| `deploy_compat/v_quail` | Android Studio Quail |
| `deploy_compat/v_panda` | Android Studio Panda |
| `deploy_compat/v_otter` | Android Studio Otter 2 Feature Drop |
| `deploy_compat/v_narwhal_feature` | Android Studio Narwhal Feature Drop |
| `deploy_compat/v_narwhal` | Android Studio Narwhal |
| `deploy_compat/v_meerkat` | Android Studio Meerkat |
| `deploy_compat/v_iguana` | Android Studio Iguana |
| `deploy_compat/v_hedgehog` | Android Studio Hedgehog |
| `deploy_compat/v_giraffe` | Android Studio Giraffe |
| `deploy_compat/v_chipmunk` | Android Studio Chipmunk |

`AsDeployerCompat.compatImplList` 必须按版本从高到低排列。当前 IDE 版本完全匹配时用对应实现；当前 IDE 高于已知最高版本时用最高版本实现并 warn；低于已知最低版本时退到 Chipmunk。

各版本 compat 默认通过 `compileOnly` 使用 `deploy_compat/stub_api/v_*/stubapi.jar`，仓库不再保存真实 Android Studio JAR。新增版本时依次使用 `create_compat_module.sh` 创建模块、`switch_api.sh real <jar-dir>` 显式切换本地真实 JAR、完成适配和真实 IDE 验证、用 `generate_stub_api.sh` 生成 Stub，最后切回 `switch_api.sh stub`。脚本不自动检测 Android Studio 安装目录，本地选择写入被忽略的 `deploy_compat/local.properties`。

Stub helper 保留类、继承、成员 descriptor、泛型、Kotlin metadata、内层类、方法声明及注解和会被内联的常量，只移除普通方法实现。方法注解中的 nullability、`@JvmStatic` 等信息会影响 Kotlin 编译结果，不能在生成 Stub 时丢弃。纯源码信息（例如完全未使用的 import）不会进入编译产物，因此生成后必须切回 Stub clean compile；若失败，优先删除无效 import 或完成最小源码适配，不能无边界扩大 Stub。

Stub 生成后的最终验收必须从 Stub checkout 执行 `./deploy_compat/verify_stub_api.sh <real-api-jugg-repo>`。参数必须是本地指向真实 Android Studio JAR、具有对应 compat 模块的另一份 Jugg checkout；脚本不自动检测该目录。脚本先报告每个 `v_*` 模块的源码差异供人工确认，但生成文件和无效 import 差异不直接决定结果；随后 clean 构建两边全部 compat JAR，最后比较 class entry 和 `com.android.*`、`com.intellij.*`、`org.jetbrains.android.*` 的规范化字节码引用（包含调用 opcode、owner、成员名和 descriptor）。全部模块必须显示 `MATCH`；任一产物差异均验收失败，详细 manifest 和 diff 保存在 `build/stub-api-verify/`。不得用未 clean 的历史 JAR、仅“编译成功”或忽略 opcode 的目标名比较替代该验收。

Android Studio Quail（`AI-261.x`）已不再携带旧 `com.android.tools.deployer.*` runtime（例如 `AdbClient`）。`AsDeployerCompat` 因此不能使用 `Proxy.newProxyInstance(IAsDeployerCompat::class.java)` 这类会在启动期反射解析接口全部方法签名的机制，否则项目打开阶段就会因缺失 deployer 类型触发 `NoClassDefFoundError`。门面方法必须用显式 dispatcher，在实际调用某个兼容能力时再捕获 `NoSuchMethodError` / `NoSuchFieldError` / `NoClassDefFoundError` / `IncompatibleClassChangeError` 并尝试其他版本实现。

IDE 部署主路径（例如 `JuggDeployerHelper` / `JuggDeployTask` / `JuggDeployer` / `JuggDeploymentService` / `IdeaDeviceAdb`）不应直接 import、构造或持有旧 deployer runtime 类型，包括 `AdbClient`、`Installer`、`InstallOptions`、`UIService`、`OverlayId`、`DeploymentCacheDatabase.Entry`、`DeployerException`。这些类型只允许在 `deploy_compat` 的版本实现中局部创建，并通过 `JuggInstallSession`、`JuggOverlayId`、`JuggDeploymentCacheEntry`、`JuggDeployerException` 等 wrapper 返回主路径；`JuggDeploymentCacheStore` 只持久化 Jugg 自有 snapshot（APK path、overlay sha/base 标记与 overlay file checksum），加载后由 `JuggDeploymentService` 经 `AsDeployerCompat` 重新 parse APK、重建 OverlayId，并在进入 AS deployer swap 前按版本创建 `DeploymentCacheDatabase.Entry`。ADB transport 的 `shell` / `push` / `uninstall` / pid / arch 查询由 `IdeaDeviceAdbClient` 基于 `IDevice` 封装，不属于 AS deployer 版本兼容接口。ADB transport 恢复检查通过 `IDeviceAdb.isAdbTransportReady()` 暴露业务语义，调用方不注入 shell-ready 探针。

Run Configuration 的 Gradle module identity 通过反射调用 `GradleProjectPathKt.getGradleProjectPath(Module)` 获取 project path 与 build root，并结合 external project id 区分 composite build。该调用是可选增强，必须整体捕获 `Throwable`；类、方法或返回数据不符合预期时回退到原有 `module.name` 解析，禁止让 module identity 增强影响旧版 Android Studio 的 Configuration 创建流程。

部署主路径也不应直接 import 或字段访问 `StudioFlags`。例如 install mode 通过 `IAsDeployerCompat.getInstallMode()` 获取；legacy compat 可读取旧 `StudioFlags.DELTA_INSTALL`，Quail compat 则提供不依赖该已移除 flag 的实现，避免新版 Android Studio 在 `JuggDeployTask` 触发 `NoSuchFieldError`。

Debug attach 同样必须走 `IAsDeployerCompat.attachJavaDebugger()`，不要在 IDE 主路径直接 import Android Studio debugger 内部类。Giraffe 及后续兼容实现先通过 `AndroidDebugClientReadyWaiter` 反射调用 AS `waitForClientReadyForDebug`，等待目标 app 的 `ClientData.DebuggerStatus.WAITING`；随后通过 `AndroidStudioDebuggerAttachStarter` 反射调用 AS 原生 `AndroidConnectDebugger.closeOldSessionAndRun(project, AndroidJavaDebugger(), client, null)`，让 Android Studio 自身创建/激活 `XDebugSession` 与 Debug tool window。低版本默认返回“不支持”，调用方负责在 Run 输出和通知中展示明确原因。

Quail 的 deployer API 已迁移到 `com.android.tools.deployer.common` 与 `com.android.tools.deployer.install` 包，`OptimisticApkUpdater` 不存在。`deploy_compat/v_quail` 必须独立实现，不继承 legacy compat 链，避免 superclass 或方法签名在启动期解析旧 root deployer 类型。

Quail 新版 `AdbClient` 的标准/full install 路径强制要求 `AdbSession`，无 session 时不再回退 ddmlib，而是抛出 `AdbSession is required for installation`。`QuailAsDeployerCompat` 创建 `AdbClient` 时必须使用三参数构造并传入 `AdbLibApplicationService` 的 application session；同一 helper 同时供 daemon installer 与 `ApkInstaller` 使用，确保 delta install 回退 full install 时仍可正常安装。

Meerkat～Panda 与 Quail 的设备选择通过 `DeployTargetContext` 获取当前 deploy target，再调用无启动副作用的 `getAndroidDevices(project)` 读取 IDE 选中顺序。只有全部选中设备都已运行并可解析为 `IDevice` 时才返回完整列表；任一选中 AVD 未运行时返回空，不启动 AVD，也不静默执行部分设备。不能用 ADB 已连接设备列表代替选中列表，否则单选设备时会错误部署到所有在线设备。

### 3.2 平台抽象

| 运行环境 | `PlatformApi.impl` 设置点 | 语义 |
|---|---|---|
| IDE 插件 | `JuggManagerCreator.create()` | 设置为 `IdeaPlatformApi`，main 层可访问 IDE 侧服务 |
| 命令行 | `CmdLine` companion init | 设置为 `CmdPlatformApi`，避免 main 层直接依赖 IDE runtime |
| main / test 编译 | `platform_compat/base_api` | 提供 IntelliJ / Android API mock 或 stub，保证 main 模块可独立编译 |

---

## 4. 核心调用链路

### 4.1 Android Studio API 兼容调用

```text
JuggManager.init()
  -> AsDeployerCompat.init(logger)
     读取 ApplicationInfo，选择 priorityImpl
  -> 业务层调用 AsDeployerCompat.*
     例如 selected devices、APK provider、installer、optimisticSwap、IDE deploy state
  -> AsDeployerCompatDispatcher 先调用 priorityImpl
     若抛出 NoSuchMethodError / NoSuchFieldError / NoClassDefFoundError / IncompatibleClassChangeError
  -> 逐个尝试其他版本实现
     成功即返回；全部失败才 warn 并抛出原兼容异常
```

兼容层只兜底 Android Studio API 形态差异。业务异常不能被当作兼容异常吞掉，否则会隐藏真实部署失败。

### 4.2 命令行入口

```text
main(args)
  -> CmdLine.run(args)
     设置 PlatformApi.impl = CmdPlatformApi
  -> cmd=buildGradleBase
     执行完整 Gradle 基线构建，准备增量编译上下文
  -> cmd=buildIncrementalApk
     在已有 project info / classpath / history 基础上跑增量构建
```

命令行入口复用 main 层编译能力，但没有 IDE 的运行配置、Run tool window、设备选择 UI。对比 CLI/IDE 行为时，优先看 `CmdPlatformApi` 与 `IdeaPlatformApi` 的差异。

CI 命令行把构建拆成两个可审计阶段：

1. `buildGradleBase` 清理目标 Jugg 目录，执行完整 Gradle 构建，保存 APK、project info、classpath、deploy history、APK database 和 source index，形成由 CI 管理的只读基线。
2. `buildIncrementalApk` 从该基线恢复上下文，只编译调用方显式传入的 `changedFiles`，合并 dex 后把增量产物写回 APK 输出目录。

这里故意不由 Jugg 自行猜测 CI diff。调用方负责给出将要编译的文件集合，命令会校验文件存在、位于 `sourceProjectDir`、能被 `FileChangesHandler` 完整识别且不含 build file。基线目录首次使用时写入 `.dirty`；同一份可变基线再次执行会失败，避免前一轮增量已经改写 history/database 后仍被当成干净输入。若流水线需要多组增量结果，应为每组复制独立基线，而不是并发共享同一目录。

---

## 5. 隐形约束

- `IAsDeployerCompat.updateMinApi()` 会根据兼容部署开关在 Android 11 与 Android 8 之间切换最小设备 API；排查“旧设备能否部署”时不要只看当前 AS 版本。
- `setAllowSelectDevice()` 是早期特殊 API，`AsDeployerCompat` 会遍历所有实现尝试调用；不要把它改成只走 priorityImpl。
- 新增 Android Studio 版本时，至少要新增 `deploy_compat/v_*` 实现，并同步 `AsDeployerCompat.compatImplList` 的顺序和本文档版本表。
- 真实 Android Studio JAR 只能通过本地 `deploy_compat/local.properties` 临时接入，不得重新放回 `deploy_compat/v_*/libs`。
- 不要在 `AsDeployerCompat` 启动初始化阶段反射 `IAsDeployerCompat` 全量方法；高版本 AS 可能已经删除旧 deployer 类型，反射解析会早于业务 fallback 直接终止插件初始化。
- 不要在 `JuggDeployTask` / `JuggDeployer` 等主路径直接访问 `StudioFlags` 字段；新增 flag 读取必须经兼容接口或安全反射封装。
- `platform_compat/base_api` 只解决编译期 API 缺口，不表示运行时一定有对应 IDE 行为；运行时能力仍以当前 AS API 和 compat 实现为准。
- 自定义编译器示例在 `custom_compilers`，生产装载由 `CustomCompilerManager` 读取 `build/jugg/config/custom_compilers`；示例代码不是默认编译阶段。
- `buildIncrementalApk` 的 `changedFiles` 是外部契约，不是提示信息。过滤后数量与输入不一致、路径越界或含 build file 都必须明确失败，不能静默跳过后继续产出 APK。

---

## 6. 排查入口

| 现象 | 优先入口 |
|---|---|
| 某 AS 版本部署 API 崩溃 | `AsDeployerCompat` 的 priorityImpl 选择和 proxy fallback 日志 |
| 新版 AS 上 `NoSuchMethodError` / `NoClassDefFoundError` | 对应 `deploy_compat/v_*/*AsDeployerCompat.kt`，确认是否需要新增更高版本实现 |
| 设备选择 / APK provider 与 IDE 行为不一致 | `IAsDeployerCompat.getSelectedDevices()` / `getApkProvider()` 的版本实现 |
| main 模块编译缺 IDE API | `platform_compat/base_api` 是否缺 stub |
| CLI 行为与 IDE 不一致 | `CmdLine`、`CmdPlatformApi`、`IdeaPlatformApi` |
| CI 增量基线提示 `.dirty` | 当前基线已被一次增量构建消费；重新复制未修改的 `buildGradleBase` 产物后再执行 |
| 自定义编译器未加载 | `CustomCompilerManager` 与 `build/jugg/config/custom_compilers` |

---

## 7. 关联文档

- IDE 层：`04_engineering_ide.md`
- Jugg Debug attach：`04_engineering_debug_attach.md`
- 项目模型：`04_engineering_project.md`
- 部署核心：`03_deploy_core.md`
- JVMTI / startup agent：`03_runtime_jvmti.md`
- 自定义编译器：`02_compile_custom_ui.md`
