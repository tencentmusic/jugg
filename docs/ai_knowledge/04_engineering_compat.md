# 工程化：兼容层与命令行模块

> 最后核对：2026-08-08
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页说明 Jugg 如何隔离 IDE / Android Studio API 变动，以及无 IDE 命令行、平台桩、自定义编译器示例这些工程边界如何接入主链路。

本页不展开 install / code swap / direct overlay 的部署业务细节；部署状态机见 `03_deploy_core.md`、`03_deploy_complete.md`、`03_runtime_jvmti.md`。

---

## 2. 核心源码索引

| 类/接口 | 文件 | 作用 |
|---|---|---|
| `AsDeployerCompat` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/AsDeployerCompat.kt` | IDE 侧统一门面；所有能力按当前 AS 版本选择优先实现并保留兼容 fallback，成功创建 session 后由该实现直接承接本轮 Apply Changes runtime |
| `AsDeployerCompatDispatcher` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/AsDeployerCompat.kt` | 兼容层方法分发器；只对已知 Android Studio API 链接错误尝试其他版本实现，业务异常保持原样 |
| `IAsDeployerCompat` | `deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/IAsDeployerCompat.kt` | deploy 兼容层接口，封装 install session、swap、IDE deploy state、module info、Java debugger attach 等 AS 版本差异 API |
| `IApplyChangesExecutor` | `deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/IApplyChangesExecutor.kt` | Host-neutral Apply Changes 执行面；仅使用 `deploy.api` 自有设备、APK、overlay、arch 与 logger 类型 |
| `DeployApiTypes` | `deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/api/DeployApiTypes.kt` | 保持 `IDevice`、`Apk`、`ApkEntry`、`DexClass`、`ByteString` 等既有调用面的 Jugg 自有部署契约；`DexClass` 保留 D8 swap 已使用的字段重初始化状态 |
| deploy API converters | `deploy_compat/v_chipmunk/.../LegacyDeployApiConverter.kt`、`deploy_compat/v_quail/.../QuailDeployApiConverter.kt`、`deploy_compat/standalone_deployer/.../StandaloneDeployApiConverter.java` | 在版本 API 边界转换自有类型与真实 ddmlib/deployer/protobuf 类型；Device 通过公共 runtime handle 解包，APK 直接携带当前进程的 transient runtime object，converter 不保存 APK origin map |
| `JuggDeployCompatTypes` | `deploy_compat/interface/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployCompatTypes.kt` | 运行时中立 wrapper；`JuggInstallSession` 同时记录成功创建它的 executor，使 installer、overlay、cache 和 redefiner 留在同一 Apply Changes runtime |
| `StandaloneApplyChangesExecutor` / `StandaloneDeployerResources` | `deploy_compat/standalone_deployer/src/main/java/com/sickworm/intellij/jugg/deploy/run/` | Java 11 standalone install/session/cache/optimistic swap 实现，以及固定 Quail installer/protocol 资源预检 |
| `JuggResourceManager` | `main/src/main/java/com/sickworm/intellij/jugg/project/runtime/JuggResourceManager.kt` | 在全局写锁内按 metadata 原子释放资源，校验 SHA-256 并修复损坏文件 |
| `JuggDeploymentCacheStore` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/cache/JuggDeploymentCacheStore.kt` | 项目级 deployment 磁盘 checkpoint；在项目锁内持久化 APK path 与 overlay snapshot，使用临时文件原子替换，不依赖 AS deployer runtime 类型；IDEA Service 另保留 Runtime 本地 memoryCache |
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

IDE 部署主路径（例如 `JuggDeployerHelper` / `JuggDeployTask` / `JuggDeployer` / `JuggDeploymentService` / `IdeaDeviceAdb`）不应直接 import、构造或持有旧 deployer runtime 类型，包括 `AdbClient`、`Installer`、`InstallOptions`、`UIService`、`OverlayId`、`DeploymentCacheDatabase.Entry`、`DeployerException`。这些类型只允许在 `deploy_compat` 的版本实现中局部创建，并通过 `JuggInstallSession`、`JuggOverlayId`、`JuggDeploymentCacheEntry`、`JuggDeployerException` 等 wrapper 返回主路径。`JuggInstallSession` 绑定成功创建它的 executor，`LaunchContext` 后续使用同一 executor 和 debugger；`JuggDeploymentCacheStore` 只持久化 Jugg 自有 snapshot，加载后由当前 bound executor 重新 parse APK、重建 OverlayId 和 `DeploymentCacheDatabase.Entry`，memory cache 也按 executor identity 隔离。ADB transport 的 `shell` / `push` / `uninstall` / pid / arch 查询由 `IdeaDeviceAdbClient` 基于 `IDevice` 封装，不属于 AS deployer 版本兼容接口。ADB transport 恢复检查通过 `IDeviceAdb.isAdbTransportReady()` 暴露业务语义，调用方不注入 shell-ready 探针。

共享调用中的 `IDevice`、`Apk`、`ApkEntry`、`DexClass`、`ByteString`、`DexComparator.ChangedClasses`、`Deploy.Arch` 与 `ILogger` 均来自 `com.sickworm.intellij.jugg.deploy.api`。类名和已依赖成员保持不变，使业务迁移主要表现为 import 变化。`IRuntimeDevice` 表达设备属于当前 host runtime，而不是某个 deployer compat；Legacy、Quail 和 IDEA ADB 边界都从同一 handle 解包真实 ddmlib device。`Apk.runtimeObject` 是只在当前进程有效且不参与序列化的 raw APK attachment，避免 owned APK 依赖 converter 实例私有 origin map。共享 API 仍禁止静态暴露 ddmlib、deployer model、deploy proto、shaded protobuf 或 Android logger 类型。

Run Configuration 的 Gradle module identity 通过反射调用 `GradleProjectPathKt.getGradleProjectPath(Module)` 获取 project path 与 build root，并结合 external project id 区分 composite build。该调用是可选增强，必须整体捕获 `Throwable`；类、方法或返回数据不符合预期时回退到原有 `module.name` 解析，禁止让 module identity 增强影响旧版 Android Studio 的 Configuration 创建流程。

部署主路径也不应直接 import 或字段访问 `StudioFlags`。例如 install mode 通过 `IAsDeployerCompat.getInstallMode()` 获取；legacy compat 可读取旧 `StudioFlags.DELTA_INSTALL`，Quail compat 则提供不依赖该已移除 flag 的实现，避免新版 Android Studio 在 `JuggDeployTask` 触发 `NoSuchFieldError`。

Debug attach 同样必须走 `IAsDeployerCompat.attachJavaDebugger()`，不要在 IDE 主路径直接 import Android Studio debugger 内部类。Giraffe 及后续兼容实现先通过 `AndroidDebugClientReadyWaiter` 反射调用 AS `waitForClientReadyForDebug`，等待目标 app 的 `ClientData.DebuggerStatus.WAITING`；随后通过 `AndroidStudioDebuggerAttachStarter` 反射调用 AS 原生 `AndroidConnectDebugger.closeOldSessionAndRun(project, AndroidJavaDebugger(), client, null)`，让 Android Studio 自身创建/激活 `XDebugSession` 与 Debug tool window。低版本默认返回“不支持”，调用方负责在 Run 输出和通知中展示明确原因。

Quail 的 deployer API 已迁移到 `com.android.tools.deployer.common` 与 `com.android.tools.deployer.install` 包，`OptimisticApkUpdater` 不存在。`deploy_compat/v_quail` 必须独立实现，不继承 legacy compat 链，避免 superclass 或方法签名在启动期解析旧 root deployer 类型。

Quail 新版 `AdbClient` 的标准/full install 路径强制要求 `AdbSession`，无 session 时不再回退 ddmlib，而是抛出 `AdbSession is required for installation`。`QuailAsDeployerCompat` 创建 `AdbClient` 时必须使用三参数构造并传入 `AdbLibApplicationService` 的 application session；同一 helper 同时供 daemon installer 与 `ApkInstaller` 使用，确保 delta install 回退 full install 时仍可正常安装。

Meerkat～Panda 与 Quail 的设备选择通过 `DeployTargetContext` 获取当前 deploy target，再调用无启动副作用的 `getAndroidDevices(project)` 读取 IDE 选中顺序。只有全部选中设备都已运行并可解析为 `IDevice` 时才返回完整列表；任一选中 AVD 未运行时返回空，不启动 AVD，也不静默执行部分设备。不能用 ADB 已连接设备列表代替选中列表，否则单选设备时会错误部署到所有在线设备。

### 3.2 Standalone Quail deployer

`deploy_compat/standalone_deployer` 固定 Android Studio Quail 1 build `AI-261.23567.138.2611.15503007`，只保留 install、APK model/cache、diff、D8 split 和 `OptimisticApkSwapper` 的实际传递闭包，并以 Java 11 重新编译。运行时禁止依赖完整 `sdk-tools.jar` 或任何 class major version 65 的 Quail class；协议仅由仓库内 Java 8 `deploy_java_proto.jar`、`studio-proto.jar` 与四 ABI installer binary 组成。

资源 metadata 的 protocol version 必须与 `Version.hash()` 一致。`JuggResourceManager` 将 installer、Apache 2.0 license、NOTICE 和 `SOURCE_CLASSES.sha256` 释放到 `~/.jugg/runtime/<runtimeVersion>/deployer/quail`，已存在文件必须先通过 SHA-256 校验，否则在全局写锁内原子修复；Java/installer 协议不一致时 daemon 启动立即失败。

Standalone 使用真实 ddmlib `AdbClient`，不依赖 Quail IDE runtime 的 adblib application session。D8 split 生成的字段重初始化状态会经自有 `DexClass` 往返并交给 `OptimisticApkSwapper`，禁止在边界转换中丢弃。类 Apply Changes 调用 `OptimisticApkSwapper(restartActivity=false)`；资源 full swap 与现有 IDEA `JuggDeployer.fullSwap` 一致，使用 `restartActivity=true` 刷新 `AssetManager/Resources`，进程保持不变且 Activity 只发生一次预期重启。Step 9 只落地 executor 和资源，不迁移 IDEA deploy lifecycle，也不注册 standalone MCP deploy 能力。

### 3.3 平台抽象

| 运行环境 | `PlatformApi.impl` 设置点 | 语义 |
|---|---|---|
| IDE 插件 | `JuggManagerCreator.create()` | 设置为 `IdeaPlatformApi`，main 层可访问 IDE 侧服务 |
| 命令行 | `CmdLine` companion init | 设置为 `CmdPlatformApi`，避免 main 层直接依赖 IDE runtime |
| main / test 编译与 CLI runtime | `platform_compat/base_api` | 提供 IntelliJ / log4j 最小实现；不再包含 `com.android.*`，CLI 可安全打包且不产生 Android class owner 冲突 |

---

## 4. 核心调用链路

### 4.1 Android Studio API 兼容调用

```text
JuggManager.init()
  -> AsDeployerCompat.init(logger)
     读取 ApplicationInfo，选择 priorityImpl
  -> 业务层调用 AsDeployerCompat 任意能力
       -> 先调用 priorityImpl
       -> 兼容错误时逐个尝试其他版本实现
     成功创建 session
       -> session 记录实际 executor
       -> LaunchContext 使用该 executor 和对应 debugger
       -> 后续有状态调用直接进入 bound executor，不再经过门面分发
       -> install / APK / overlay / cache / swap 保持在同一 Apply Changes runtime
     全部实现失败才 warn 并抛出原始 priority 兼容异常
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
- Device 属于 host runtime，不属于 Legacy/Quail compat；新增版本 Adapter 必须实现 `IRuntimeDevice`，不得恢复按具体 Adapter 类强转。
- APK raw attachment 必须跟随 owned APK，并保持 transient；禁止恢复 converter 实例级 APK origin map。
- Install session 创建允许 compatibility fallback；一旦创建成功，本轮 `LaunchContext` 必须使用 session 绑定的 executor 和对应 debugger，deployment memory cache 也必须按 executor identity 隔离。
- `AsDeployerCompat` 所有接口必须保留 compatibility-error fallback；禁止重新引入绕过 dispatcher 的 priority-only 调用。有状态 owner 一致性由 session-bound executor 保证。
- 新增 Android Studio 版本时，至少要新增 `deploy_compat/v_*` 实现，并同步 `AsDeployerCompat.compatImplList` 的顺序和本文档版本表。
- 真实 Android Studio JAR 只能通过本地 `deploy_compat/local.properties` 临时接入，不得重新放回 `deploy_compat/v_*/libs`。
- 不要在 `AsDeployerCompat` 启动初始化阶段反射 `IAsDeployerCompat` 全量方法；高版本 AS 可能已经删除旧 deployer 类型，反射解析会早于业务 fallback 直接终止插件初始化。
- 不要在 `JuggDeployTask` / `JuggDeployer` 等主路径直接访问 `StudioFlags` 字段；新增 flag 读取必须经兼容接口或安全反射封装。
- `platform_compat/base_api` 不得包含 `com/android/**`；Android runtime class 必须由 ddmlib、standalone deployer 或 protocol JAR 唯一提供。
- 自定义编译器示例在 `custom_compilers`，生产装载由 `CustomCompilerManager` 读取 `build/jugg/config/custom_compilers`；示例代码不是默认编译阶段。
- `buildIncrementalApk` 的 `changedFiles` 是外部契约，不是提示信息。过滤后数量与输入不一致、路径越界或含 build file 都必须明确失败，不能静默跳过后继续产出 APK。

---

## 6. 排查入口

| 现象 | 优先入口 |
|---|---|
| 某 AS 版本部署 API 崩溃 | `AsDeployerCompat` 的 priorityImpl 选择和 proxy fallback 日志 |
| 新版 AS 上 `NoSuchMethodError` / `NoClassDefFoundError` | 对应 `deploy_compat/v_*/*AsDeployerCompat.kt`，确认是否需要新增更高版本实现 |
| 设备选择与 IDE 行为不一致 | `IAsDeployerCompat.getSelectedDevices()` 的版本实现 |
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
