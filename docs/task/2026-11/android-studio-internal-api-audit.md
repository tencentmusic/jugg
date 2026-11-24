# Android Studio 非公开 API 依赖梳理

**Purpose** — Identify Studio-owned APIs used by the IDEA host and deploy compatibility modules, including reflective and runtime resource dependencies.

**Decision** — Propose removing incidental host dependencies first and evaluating the existing pinned Quail deployer behind the IDE deployment boundary. Preserve native IDE integrations where their behavior is required.

**Impact** — The deployer migration could remove Studio ABI dependencies for installation and Apply Changes while retaining the IDE device picker, Gradle Sync, active variant, and native debugger integration.

## 范围和口径

基线为 `862395a30`。盘点 `idea/src/main`、`idea/src/ide_entry`、`deploy_compat/interface`、12 个 `v_*` 适配层及 Standalone 的可复用实现。把 Studio Android 插件内部类和 IDE 自带的 deployer ABI 都计入范围，包含反射类名、字段、方法及 IDE 安装目录里的 installer。单独记录由 IDE 提供的 ddmlib/adblib 和 protobuf ABI。独立固定版本的 SDK 工具库与 Jugg 内置的 Quail 代码属于另一种维护成本。JVM `public` 只说明可访问性，公开扩展契约需要文档或 SDK 依据。 [JetBrains API 状态说明](https://plugins.jetbrains.com/docs/intellij/api-internal.html)

## idea 层

| 位置与依赖 | 现有用途 | 处理建议 |
|---|---|---|
| `DirectorySelector` → `com.android.tools.idea.util.toIoFile` | 打开设置中选中的本地目录 | 复用已有的 `File` 或 IntelliJ `VfsUtil.virtualToIoFile`，保留打开目录和错误提示语义。 |
| `JuggCompileUiHandler`、`IdeaDeployEnvironment` → `IdeService` | 安装冲突确认、部署消息。降级已有 Jugg 确认弹窗，Run/MCP 已有消息通道 | 用现有 UI 和消息通道替换，核对普通安装冲突的卸载/取消、数据删除提示、消息展示方式和 EDT。 |
| `IdeaDeviceAdb`、`IdeaDeployEnvironment` → `LogWrapper` | 把部署 ADB 日志适配给 Android logger | 复用 Jugg 日志边界，保留 debug/verbose 路由和异常。ddmlib 设备与异常类型仍在 `IdeaDeviceAdbClient`、转换器和诊断接收器使用。 |
| `IdeaProjectModelSource` → `AndroidSdkAdditionalData.buildTargetHashString` | 为 IDE 模块的 compile SDK 作额外覆盖 | Gradle 项目信息已读 compile SDK，合并时优先使用 Gradle 值；Android 模型另有 `compileTarget`。比较 IDE-only、预览/扩展 SDK、定制 ROM 场景的实际差异后决定移除覆盖或保留局部兼容。 |
| `JuggProjectManagerListener`、`JuggGradleSyncListener` → 静态 `GradleSyncListener`、反射 `GradleSyncState.subscribe` | 项目打开后订阅 Sync；成功触发项目模型刷新与配置协调，跳过仍会协调，开始/失败影响依赖同步状态 | 暂保留一处订阅。IntelliJ External System 的导入事件是候选，需要证明能覆盖 Studio 的 SKIPPED、失败和 composite build 去重；仅作反射代理也会保留依赖。 |
| `AsDeployerCompat.RunConfigurationDeviceSelectionMarker` → 反射新版及旧版 toolbar key | 标记 Jugg Run Configuration 支持 Studio 原生设备下拉，随后还会调用各版本的实现 | 原生下拉需要保留时，集中在现有设备入口，并验证是否可以去掉重复写入分支。读取设备还依赖 `DeployTargetContext` 等版本接口。 |
| `CopyEmbeddedDistributionPaths` → installer 路径、反射 `AndroidProfilerDownloader` 与 `IdeInfo` | 从 AS 安装、IDE 下载或开发布局找 installer，用于安装会话与 Direct Overlay agent 提取 | 与固定 deployer/installer 配对迁移。当前 `LaunchContextFactory` 在建会话前就取 installer 路径，替换反射探测无法解除对发行包布局的依赖。若仍保留旧执行器，它仍要用匹配的 installer。 |
| `plugin.xml`、`idea/build.gradle` → Android 插件 | 加载 Android 模型、设备及调试等服务 | 以上集成仍使用 Android 插件。短期衡量具体调用点与 ABI 的删除，插件依赖是最终剩余边界之一。 |

## deploy_compat 层

| 能力 | 依赖和版本分布 | 处理建议 |
|---|---|---|
| 安装、APK 解析/设备 dump、Overlay ID/更新、缓存、代码/资源 swap | Chipmunk 基线及 Giraffe/Iguana/Narwhal 分支使用旧 deployer ABI；Quail 自成一支，Rabbit 改 `AdbClient` 参数，Stingray 再处理 `ConnectedDevice` 和 DEX 类型迁移。旧分支还读 `StudioFlags`。 | 优先评估已有固定 Quail `IApplyChangesExecutor`。它已经覆盖 install、parse/dump、overlay、swap、cache 等；普通安装单独改走 adb 仅能移除少数调用，还要保留 split、降级、delta 和签名不匹配流程。 |
| 设备发现、原生选择 | `AdbService`/ddmlib 枚举设备；旧版 toolbar action 和新版 `DeployTargetContext` 读取选择，`DeployableToDevice` 等 key 注册配置。 | 将“在线设备”与“当前 IDE 选择”分开处理。前者可评估复用固定 ddmlib/ADB；后者仍保留窄适配，确保多选与未启动 AVD 的行为。Jugg 自有选机才会真正解除 toolbar 依赖，会改变操作路径。 |
| 部署就绪检查 | Chipmunk/Quail 经 `DeploymentApplicationService.findClient` 判断 debuggable 客户端，区分无设备、未授权、API 不支持、无法识别包名及无对应客户端。 | 可用设备状态和 JDWP 客户端列表评估替换。Standalone 已有 ADB 检查，但只看 PID 的分支无法保持 debuggable 语义；还需检查多进程及进程映射。 |
| Android 模块模型和运行配置建议 | `GradleAndroidModel`、`AndroidFacet`、Gradle DSL、Android Run Configuration 类型、Gradle 路径反射。Narwhal Feature Drop、Otter、Panda、Quail 等覆盖其中一部分。 | 使用 Gradle snapshot 提供构建事实，IDE 仅补充活动 variant、导入时尚未具备的字段和原生配置建议。先减少重复读取，再收敛适配；现有配置自动匹配依赖 variant、完整 Gradle 任务路径、composite build 标识、实际 APK 输出目录及 androidTest 元数据。 |
| Native Debug attach 与挂调试器时的类替换 | Giraffe 起等待 Android debug client，然后反射调用 `AndroidConnectDebugger`/`AndroidJavaDebugger`。旧基线可用 `DebuggerRedefiner` 给已附加会话做类替换，Quail 的 `makeDebuggerRedefiners` 返回空。 | 保留 native attach 桥。它创建 IDE 调试会话、接通断点。早期版本的类替换单列回归，旧 raw redefiner 与固定 deployer 的类不可跨加载器复用。 |
| 可立即清理的历史边 | Chipmunk、Giraffe 的私有 `isApplyChangesRelevant` 只有定义，挂着 `BaseAction.SHOW_APPLY_CHANGES_UI`。`JavaDebuggerSessionStarter` 只有测试和架构检查引用。 | 可以在小批次分别删除；同步清理对应 Stub 或 Allowlist/测试引用。现役 native attach 帮助类有实际调用。 |
| IntelliJ 平台兼容 | `IAsDeployerCompat.getModuleManager` 也在这里处理平台访问。 | 单独计为 IntelliJ 平台兼容，避免夸大 Studio Android 插件依赖的减少量。 |

## 建议实施顺序

1. 先做可独立交付的小改动。替换目录、对话框和日志 helper，删除已确认无生产调用的分支。用最老兼容基线和新 IDE 验证目录打开、安装冲突和消息路径，用 `DeployCompatArchitectureTest` 与发布包静态引用复核边界。
2. 对 Quail 及更新的 IDEA 先验证固定 deployer。复用 `StandaloneApplyChangesExecutor`、`StandaloneDeployerResources` 和现有 `IApplyChangesExecutor`，将安装/Swap 与 `IdeaDeployDebugger` 的 IDE 调试桥解耦。当前 `IdeaDeployEnvironment.createDeployDebugger` 强转会话执行器为 `IAsDeployerCompat`，直接替换会失败。先验证怎样让固定 deployer、protobuf 和设备传输与 IDE 的同名类隔离。仅搬动 JAR 会留下宿主类与 vendored 类的冲突风险，固定执行器转换器也要求其加载器可接受的 ddmlib `IDevice`。打包隔离可通过验证后再选择实施形式。
3. 验证成对的 Java deployer 与四 ABI installer，包含 Direct Overlay 取 agent。保持会话始终绑定同一执行器，切换前后用各自运行时重建缓存对象。对既有 overlay、失败恢复、全量/差量安装、类 swap、资源 swap 与 Activity 重启、Debug 断点及 androidTest 跑真实 IDE 回归。开始覆盖 Quail 与已遇到 ABI 分叉的 Rabbit/Stingray，再决定向旧版扩展；旧 IDE 还需验证原有类替换行为。
4. 独立推进项目模型和就绪检查。先验证去掉 SDK 覆盖、重用 Gradle 快照、用设备/JDWP 状态复现就绪结果，再评估是否能替换 Sync 订阅。原生设备下拉、活动 variant 与 Debug 会话如果继续作为产品行为，保留各自最小桥接。若要移除 Android 插件硬依赖，这几处需要另行确定体验。

## 本轮验证

- 交叉检索生产源码、反射、插件描述符、构建声明、执行器与测试调用。抽查本机 Quail 4 RC2 和 Rabbit 2 的 `android.jar`、`android-common.jar` 和 `sdk-tools.jar`。两者均含上述 Sync、日志、SDK、调试相关类。用 `javap` 确认 Quail 4 RC2 的 `AdbClient` 仍有 ddmlib `IDevice` 构造器，Rabbit 2 只留 `DeviceHolder` 构造器。
- 当前验证只覆盖调用链和局部二进制签名。未重新构建 ZIP、运行 Plugin Verifier 或做真机迁移验证。实施阶段沿用 `DeployCompatArchitectureTest`、`JuggDeployerInstallTest`、L3 `TopLevelFlowTest`、`AndroidTestTopLevelFlowTest`、`StandaloneDeployerDeviceFlowTest`。Stub 有变化时用 `verify_stub_api.sh` 验证。ADB 安装与进程命令可参考官方工具契约。 [Android Debug Bridge](https://developer.android.com/tools/adb)
