# Jugg 技术文档 - IDE 插件层

---

## 一、模块概述

IDE 插件层是 Jugg 与 IntelliJ/Android Studio 平台的交互层，负责：
- 插件生命周期管理
- 运行配置管理
- 编译/部署任务调度
- UI 交互和用户反馈
- IDE 事件监听（Sync、文件变化等）

### 核心职责

| 职责 | 说明 |
|------|------|
| 插件加载 | 支持热更新，动态加载 JAR |
| 运行配置 | 创建和管理 Jugg 运行配置 |
| 任务执行 | 调度编译和部署任务 |
| IDE 集成 | 监听 Sync、文件变化等事件 |
| 兼容性 | 适配不同版本的 Android Studio |

---

## 二、架构设计

### 2.1 分层架构

```
┌─────────────────────────────────────────────────────────┐
│                    IDE Platform                         │
│  (IntelliJ / Android Studio)                            │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│              ide_entry (插件入口层)                      │
│  • PluginLoadListener    • JuggInitializer              │
│  • JuggLoader           • JuggRunConfiguration          │
│  • SyncEvent            • IJuggManagerCaller           │
└──────────────────────┬──────────────────────────────────┘
                       │ (Proxy 跨 ClassLoader)
┌──────────────────────▼──────────────────────────────────┐
│              main (核心逻辑层)                           │
│  • JuggManager          • JuggCompilerHelper            │
│  • JuggDeployerHelper   • JuggRunningTask              │
│  • ProjectInfoReader    • DeployTargetManager          │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│              deploy_compat (兼容层)                      │
│  • AsDeployerCompat      • *AsDeployerCompat           │
│  • IdeVersion            • CompatImpl                   │
└─────────────────────────────────────────────────────────┘
```

### 2.2 核心类关系

```
JuggManager (核心管理器)
├── JuggCompilerHelper (编译助手)
│   ├── JuggCompiler (编译器)
│   ├── GradleCompileClientManager (Gradle 客户端)
│   └── IncrementalCompilerHelper (增量编译)
├── JuggDeployerHelper (部署助手)
│   ├── JuggDeployTask (部署任务)
│   ├── JuggDeployer (部署器)
│   └── JuggDeploymentService (部署服务)
├── DeployTargetManager (设备管理)
├── DeployStateManager (状态管理)
├── CompileContextManager (编译上下文)
├── FileChangesDetector (文件变化检测)
└── DependencyChangeManager (依赖变化管理)
```

---

## 三、插件加载机制

### 3.1 热更新支持

**核心类**: `JuggLoader`, `JuggHotUpdateManager`

Jugg 支持插件热更新，无需重启 IDE：

```kotlin
// JuggLoader.kt
class JuggLoader(val project: Project, val projectDir: File) {
    fun init() {
        loadManager(project, projectDir)
    }

    private fun createInstance(project: Project, projectDir: File) {
        val classLoader: ClassLoader
        val creatorName: String

        if (JuggHotUpdateManager.isEmbeddedUpdated) {
            // 嵌入版本已更新，清除热更新
            JuggHotUpdateManager.clearHotUpdate()
            classLoader = getOriginClassLoader()
            creatorName = "embedded_updated"
        } else if (JuggHotUpdateManager.isHotUpdateAvailable) {
            // 使用热更新 JAR
            classLoader = getHotUpdateClassLoader()
            creatorName = "hot_update"
        } else {
            // 使用嵌入 JAR
            classLoader = getOriginClassLoader()
            creatorName = "embedded"
        }

        // 使用 Proxy 跨 ClassLoader 调用
        juggManager = Proxy.newProxyInstance(...) as IJuggManagerCaller
    }
}
```

**ClassLoader 策略**:

```kotlin
// JuggPriorityURLClassLoader
class JuggPriorityURLClassLoader(
    urls: Array<URL>,
    parent: ClassLoader,
    private val isUseOriginClassLoader: (String) -> Boolean
) : URLClassLoader(urls, parent) {

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        // 某些类必须使用原始 ClassLoader
        if (isUseOriginClassLoader(name)) {
            return parent.loadClass(name)
        }
        // 优先从热更新 JAR 加载
        return super.loadClass(name, resolve)
    }
}
```

**不可热更新的包**:

```kotlin
val canNotHotUpdatePackage = setOf(
    "com.sickworm.intellij.jugg.loader",  // 加载器本身
    "com.sickworm.intellij.jugg.ide",     // IDE 接口层
    "com.intellij",                       // IntelliJ API
)
```

### 3.2 插件初始化流程

```
PluginLoadListener.onPluginLoaded()
    ↓
JuggInitializer.init(project)
    ↓
JuggLoader.loadManager()
    ↓
JuggManagerCreator.create()
    ↓
JuggManager.init()
    ├── AsDeployerCompat.init()
    ├── loadCustomConfig()
    ├── tryCreateRunConfigurations()
    ├── recoverDeployContext()
    └── updateProjectInfo()
```

---

## 四、运行配置管理

### 4.1 运行配置类型

**核心类**: `JuggRunConfiguration`, `JuggConfigurationType`

```kotlin
class JuggRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String,
) : RunConfigurationBase<JuggRunConfigurationOptions>(project, factory, name) {

    override fun getType(): ConfigurationType {
        return JuggConfigurationType.getInstance()
    }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> {
        return JuggSettingsEditor()
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        return JuggRunProfileState(project, state!!)
    }
}
```

### 4.2 运行配置选项

**核心类**: `JuggRunConfigurationOptions`, `JuggRunSettingsComponent`

支持的配置项：

| 配置项 | 说明 |
|--------|------|
| `compileCommand` | Gradle 编译命令 |
| `outputApkName` | 输出 APK 名称/路径 |
| `isRemoteCompile` | 是否启用远程编译 |
| `syncMode` | 同步模式 (IFT/RSYNC/RSYNC_SIMPLE) |
| `remoteSshUser` | SSH 用户名 |
| `remoteSshPassword` | SSH 密码 |
| `remoteSshIp` | SSH 主机 |
| `remoteSshPort` | SSH 端口 |
| `localToRemoteSyncPath` | 本地到远程同步路径 |
| `remoteToLocalSyncPath` | 远程到本地同步路径 |

### 4.3 自动创建运行配置

**核心类**: `JuggManager.tryCreateRunConfigurations()`

```kotlin
private fun tryCreateRunConfigurations(isSyncFinished: Boolean, maxRetryCount: Int = 5) {
    val currentList = RunManager.getInstance(project)
        .getConfigurationSettingsList(JuggConfigurationType::class.java)

    // 获取建议的运行配置
    val suggestRunConfiguration = AsDeployerCompat.getSuggestRunConfigurations(
        currentListNames, project, logger, isNeedDefaultRunConfig = maxRetryCount <= 0
    )

    // 创建运行配置
    val settingsList = suggestRunConfiguration.map { suggest ->
        val factory = JuggConfigurationType.getInstance().configurationFactories[0]
        val settings = RunManager.getInstance(project)
            .createConfiguration(suggest.runConfigName, factory)
        (settings.configuration as JuggRunConfiguration).state?.let {
            it.compileCommand = suggest.compileCommand
            it.outputApkName = suggest.outputApkPath
            it.setDefaultRemoteOption(JuggSettings.defaultCompileSettings)
        }
        settings
    }

    // 添加到 RunManager
    settingsList.forEach {
        RunManager.getInstance(project).addConfiguration(it)
    }
}
```

---

## 五、任务执行流程

### 5.1 运行任务

**核心类**: `JuggRunningTask`

```kotlin
class JuggRunningTask(
    private val options: JuggGradleCompileOptions,
    private val project: Project,
    private val juggServer: JuggServer,
    private val deployTargetManager: IDeployTargetManager,
    private val dependencyChangeManager: IDependencyChangeManager,
    private val statusManager: IJuggRunningTaskStatusManager,
    private val deployHistoryManager: IDeployHistoryManager,
    private val juggCompileHelper: JuggCompilerHelper,
    private val juggDeployHelper: JuggDeployerHelper,
    private val initIncrementalCompileTask: () -> Unit,
    private val compileUiHandler: CompileUiHandler,
) : Task.Backgroundable(project, "Running Jugg...") {

    override fun run(indicator: ProgressIndicator) {
        // 1. 开始构建
        dependencyChangeManager.onStartBuilding()
        JuggLogger.recreateLogFileIfDeleted(project)
        juggServer.onCompile()

        // 2. 执行编译
        val compileTaskResult = juggCompileHelper.compile(options, compileUiHandler)

        // 3. 执行部署
        if (compileTaskResult.isSuccess && deployTargetManager.hasDevice) {
            val devices = deployTargetManager.getSelectedDevices()
            devices.forEachIndexed { index, device ->
                val deployTaskResult = juggDeployHelper.deploy(
                    DeployOptions(device, index == devices.size - 1, ...)
                )
            }
        }

        // 4. 结束构建
        dependencyChangeManager.onEndBuilding(isSuccess, isCanceled)
    }
}
```

### 5.2 编译流程

**核心类**: `JuggCompilerHelper`

```
JuggCompilerHelper.compile()
    ↓
preprocessIncrementalCompile()
    ├── checkDeviceFallback()          // 检查设备状态
    ├── checkFilesRollback()           // 检查文件回滚
    ├── checkFilesFallback()           // 检查文件数量
    └── checkLibraryIncrementalCompile() // 检查依赖变化
    ↓
if (需要增量编译)
    incrementalCompile()
        ├── 获取未部署文件
        ├── IncrementalCompilerHelper.compile()
        └── 返回 CompileTaskResult
else
    gradleCompile()
        ├── GradleCompileTask.run()
        ├── 获取 APK 信息
        └── 返回 GradleCompileResult
```

### 5.3 部署流程

**核心类**: `JuggDeployerHelper`

```
JuggDeployerHelper.deploy()
    ↓
if (isInstall)
    // 安装 APK
    JuggDeployData.forInstall(apks)
    runTask(device, deployData)
else if (isEmbeddedToApk)
    // 嵌入到 APK
    embeddedToApk()
else
    // 增量部署
    deployData = deployFileManager.getDeployData()
    if (deployData.isNeedUpdateApk)
        // 重签名 APK
        IncrementalDeployHelper.updateApk()
    if (!deployStateManager.isReadyDeploy)
        // 恢复部署状态
        recoverDeployState()
    runTask(device, deployData)
    ↓
JuggDeployTask.run()
    ├── JuggDeployer.install() / codeSwap() / fullSwap()
    ├── JuggDeploymentService.storeEntry()
    └── 返回 LaunchResult
```

---

## 六、IDE 事件监听

### 6.1 Gradle Sync 事件

**核心类**: `JuggGradleSyncListener`, `JuggGradleSyncWithRootListener`

```kotlin
class JuggGradleSyncListener : GradleSyncListener {
    override fun syncSucceeded(project: Project) {
        JuggInitializer.getManager(project)?.onSyncEvent(SyncEvent.SUCCEEDED)
    }

    override fun syncFailed(project: Project, errorMessage: String) {
        JuggInitializer.getManager(project)?.onSyncEvent(SyncEvent.FAILED)
    }

    override fun syncSkipped(project: Project) {
        JuggInitializer.getManager(project)?.onSyncEvent(SyncEvent.SKIPPED)
    }
}
```

**Sync 事件处理**:

```kotlin
// JuggManager.kt
override fun onSyncEvent(syncEvent: SyncEvent) {
    when (syncEvent) {
        SyncEvent.SUCCEEDED -> {
            ideSyncProblemResolver.onIdeSyncSucceeded()
            tryCreateRunConfigurations(isSyncFinished = true)
            updateProjectInfo(isAfterSync = true)
        }
        SyncEvent.SKIPPED -> {
            tryCreateRunConfigurations(isSyncFinished = false)
            updateProjectInfo(isAfterSync = false)
        }
        SyncEvent.STARTED -> {
            dependencyChangeManager.onStartSyncing(isFromIde = true)
        }
        SyncEvent.FAILED -> {
            dependencyChangeManager.onEndSyncing(isFromIde = true, false, ...)
        }
    }
}
```

### 6.2 文件变化监听

**核心类**: `FileChangesDetector`, `GitFileChangesDetector`

```kotlin
class FileChangesDetector(
    private val project: Project,
    private val projectDir: File,
) : IFileChangesDetector, Disposable {

    override fun startListen(listener: FileChangesListener) {
        val vfsListener = AsyncFileListener { events ->
            object: AsyncFileListener.ChangeApplier {
                override fun afterVfsChange() {
                    notifyFileChanges(events)
                }
            }
        }
        VirtualFileManager.getInstance().addAsyncFileListener(vfsListener, this)
    }

    private fun notifyFileChanges(events: MutableList<out VFileEvent>) {
        val changedFiles = mutableListOf<File>()
        val deletedFiles = mutableListOf<File>()

        events.forEach { event ->
            when (event) {
                is VFileMoveEvent -> {
                    deletedFiles.add(File(event.oldPath))
                    changedFiles.add(File(event.path))
                }
                is VFileDeleteEvent -> {
                    deletedFiles.add(File(event.path))
                }
                else -> {
                    changedFiles.add(File(event.path))
                }
            }
        }

        listener?.onFileChanges(changedFiles, deletedFiles)
    }
}
```

**文件变化处理**:

```kotlin
// JuggManager.kt
private fun processFileChanged(
    changedFiles: List<File>,
    deletedFiles: List<File>,
    isFromRecover: Boolean,
) {
    // 过滤文件
    val realChangedFiles = fileChangesHandler.filter(changedFiles)

    // 添加到部署文件管理器
    deployFileManager.addChangedFile(realChangedFiles)

    // 检查构建文件变化
    val isBuildFileChanged = realChangedFiles.any { it.type == CompileFile.Type.BuildFile }
    if (isBuildFileChanged) {
        dependencyChangeManager.onUpdateChangedBuildFiles(allBuildFiles)
    }

    // Git 变化检测
    if (!isFromRecover) {
        gitFileChangesDetector.onSourceFileChanged(realChangedFiles)
    }

    // 自动编译
    if (JuggSettings.compileOnSave) {
        runTaskSafe("Compile Changes", ::compileChanges)
    }
}
```

---

## 七、兼容层设计

### 7.1 Android Studio 版本兼容

**核心类**: `AsDeployerCompat`, `IAsDeployerCompat`

Jugg 支持多个 Android Studio 版本：

| 版本 | 代码名 | Build Version |
|------|--------|---------------|
| Otter 2 FD | OtterAsDeployerFeatureCompat | 252.27397.103 |
| Narwhal FD | NarwhalAsDeployerFeatureCompat | 251.27812.49 |
| Narwhal | NarwhalAsDeployerCompat | 251.23774.16 |
| Meerkat | MeerkatAsDeployerCompat | 243.22562.218 |
| Iguana | IguanaAsDeployerCompat | 232.10227.8 |
| Hedgehog | HedgehogAsDeployerCompat | 231.9225.16 |
| Giraffe | GiraffeAsDeployerCompat | 223.8836.35 |
| Chipmunk | ChipmunkAsDeployerCompat | 212.5712.43 |

**版本检测和适配**:

```kotlin
object AsDeployerCompat : IAsDeployerCompat {

    private val impl: IAsDeployerCompat = Proxy.newProxyInstance(...) { proxy, method, args ->
        try {
            // 优先使用匹配版本的实现
            return method.invoke(priorityImpl.impl.value, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            if (!e.targetException.isCompatError) {
                throw e.targetException
            }

            // 尝试其他版本实现
            compatImplList
                .filter { it.ideVersion != priorityImpl.ideVersion }
                .forEach {
                    try {
                        return method.invoke(it.impl.value, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        if (!e.targetException.isCompatError) {
                            throw e.targetException
                        }
                    }
                }

            throw e
        }
    }

    fun init(logger: Logger) {
        val ideVersion = IdeVersion(ApplicationInfo.getInstance())

        // 查找匹配版本
        var impl = compatImplList.firstNotNullOfOrNull { compatImpl ->
            if (compatImpl.ideVersion == ideVersion) {
                return@firstNotNullOfOrNull compatImpl
            } else if (compatImpl.ideVersion < ideVersion) {
                return@firstNotNullOfOrNull compatImpl
            }
            return@firstNotNullOfOrNull null
        }

        this.priorityImpl = impl ?: compatImplList.last()
    }
}
```

### 7.2 兼容接口

```kotlin
interface IAsDeployerCompat {
    fun getApkProvider(project: Project, config: AndroidRunConfiguration): ApkProvider
    fun getSelectedDevices(project: Project): List<IDevice>?
    fun getConnectedDevices(project: Project): List<IDevice>?
    fun getInstaller(installersFolder: String, adb: AdbClient, logger: ILogger): AdbInstaller
    fun install(...): Boolean
    fun makeDebuggerRedefiners(...): Map<Int, ClassRedefiner>
    fun optimisticSwap(...): OverlayId
    fun getIdeDeployStateResult(...): IdeDeployState
    fun getDeploymentService(project: Project): DeploymentService
    fun parseApks(paths: List<String>): List<Apk>
    fun setAllowSelectDevice(runConfiguration: RunConfigurationBase<*>)
    fun getSuggestRunConfigurations(...): List<SuggestRunConfiguration>
    fun getIdeModuleInfo(...): IdeModuleInfo?
}
```

---

## 八、UI 组件

### 8.1 运行配置设置 UI

**核心类**: `JuggRunSettingsComponent`

提供以下 UI 元素：
- 编译命令输入框
- 输出 APK 名称/路径输入框
- 远程编译选项
- SSH 配置（用户、密码、主机、端口）
- 同步路径配置
- 更多选项按钮

### 8.2 更多选项菜单

**核心类**: `MoreOptionsManager`

提供的选项：

| 选项 | 说明 |
|------|------|
| 确认无文件变化时回退 | 是否在无文件变化时确认回退 |
| 部署后总是重启 App | 部署后是否总是重启 App |
| 嵌入到 APK | 是否将增量变化嵌入 APK |
| 设置自定义服务器 URL | 设置自定义服务器地址 |
| 检查更新 | 检查插件更新 |
| 清理并重置 Jugg | 清理缓存并重置 |
| 启用注入 Gradle 编译 | 是否启用 Gradle 注入编译 |
| 启用从 Gradle 读取项目信息 | 是否从 Gradle 读取项目信息 |
| 启用兼容部署模式 | 是否启用兼容部署模式 |
| 启用项目 Kotlin 编译器 | 是否使用项目 Kotlin 编译器 |
| 启用备份 classpath | 是否启用 classpath 备份 |

### 8.3 对话框

| 对话框 | 用途 |
|--------|------|
| `BuildChangesConfirmDialog` | 确认构建文件变化 |
| `CommonConfirmDialog` | 通用确认对话框 |
| `ReportConfirmDialog` | 报告问题确认 |
| `ReportProgressDialog` | 报告问题进度 |
| `CheckUpdatesProgressDialog` | 检查更新进度 |
| `RemoteCompileApplierDialog` | 远程编译配置 |
| `UserAndPasswordInputDialog` | 用户名密码输入 |

### 8.4 通知

**核心类**: `JuggCommonNotification`

```kotlin
class JuggCommonNotification(private val project: Project) {
    fun showUpgrade(downloadUrl: String) {
        show(NotificationData("Jugg is ready to upgrade", "", "Download", downloadUrl, false))
    }

    fun show(data: NotificationData) {
        val notificationGroup = getGroup(data.isSticky)
        val notification = notificationGroup.createNotification(
            data.title, data.content, NotificationType.INFORMATION
        )
        notification.notify(project)
    }
}
```

---

## 九、RPC 通信

### 9.1 RPC 接口

**核心类**: `RpcCaller`, `RpcRequest`, `RpcResponse`

支持的 RPC 命令：

| 命令 | 说明 |
|------|------|
| `ECHO` | 回显测试 |
| `RUN` | 运行编译和部署 |

```kotlin
class RpcCaller(private val juggManager: JuggManager, private val gitFileChangesDetector: GitFileChangesDetector) {
    fun call(rpcRequest: RpcRequest): RpcResponse {
        return when (rpcRequest.cmd) {
            RpcCommand.ECHO -> echo(rpcRequest)
            RpcCommand.RUN -> run(rpcRequest)
            else -> notSupport(rpcRequest)
        }
    }

    private fun run(rpcRequest: RpcRequest): RpcResponse {
        val runConfiguration = getRunConfiguration()
        val state = runConfiguration.state ?: return error("Run configuration state is null.")

        val compileUiHandler = object : JuggCompileUiHandler(...) {
            override fun onEnd(runResult: RunResult) {
                synchronized(waitLock) {
                    waitLock.notify()
                }
                runResultFinal = runResult
            }
        }

        // 执行任务
        SwingUtilities.invokeLater {
            val executionResult = juggManager.runTask(state, compileUiHandler)
            // 显示运行内容
        }

        // 等待完成
        synchronized(waitLock) {
            waitLock.wait()
        }

        return RpcResponse(RpcResult.OK, Gson().toJson(result))
    }
}
```

---

## 十、部署状态管理

### 10.1 部署状态

**核心类**: `DeployStateManager`, `JuggDeployState`

```kotlin
data class JuggDeployState(
    val state: State,
    val message: String,
    val ideDeployState: IdeDeployState,
) {
    enum class State {
        NOTHING_CAN_DO,           // 无事可做
        READY_FULL_COMPILE,       // 准备全量编译
        READY_INCREMENTAL_COMPILE,// 准备增量编译
        READY,                    // 准备就绪
    }
}
```

### 10.2 IDE 部署状态

```kotlin
data class IdeDeployState(
    val state: State,
    val message: String,
) {
    enum class State {
        OK,                    // 正常
        INVALID_DEVICE,        // 设备无效
        APP_NOT_FOUND,         // App 未找到
        APP_NOT_DEBUGGABLE,    // App 不可调试
        DEVICE_OFFLINE,        // 设备离线
    }
}
```

### 10.3 状态更新

```kotlin
override fun updateDeployState(): JuggDeployState {
    var lastState = deployState
    deployState = getNewDeployState()

    // 等待状态稳定
    while (lastState != deployState) {
        lastState = deployState
        deployState = getNewDeployState()
        Thread.sleep(10)
    }

    return deployState
}

private fun getNewDeployState(): JuggDeployState {
    val ideDeployState = ideDeployStateHelper.getIdeDeployState(device, packageName)

    if (!deployHistoryManager.hasBeenFullCompiled) {
        return JuggDeployState(READY_FULL_COMPILE, "not gradle compile yet", ideDeployState)
    }

    if (deployHistoryManager.isLastFullCompileFailed) {
        return JuggDeployState(READY_FULL_COMPILE, "last gradle compile not success", ideDeployState)
    }

    if (isBuildFileChanged) {
        return JuggDeployState(READY_FULL_COMPILE, "$whatBuildFileChanged changed", ideDeployState)
    }

    if (ideDeployState.state != IdeDeployState.State.OK) {
        return JuggDeployState(READY_INCREMENTAL_COMPILE, ideDeployState.message, ideDeployState)
    }

    return JuggDeployState.READY
}
```

---

## 十一、部署服务

### 11.1 部署缓存数据库

**核心类**: `JuggDeploymentService`

```kotlin
object JuggDeploymentService {
    private val deploymentCacheDatabase: DeploymentCacheDatabase by lazy {
        val deployDbPath = Paths.get(PathManager.getSystemPath(), ".deploy_cache.db")
        DeploymentCacheDatabase(4, deployDbPath.toFile())
    }

    private var memoryCache: ConcurrentHashMap<String, DeploymentCacheDatabase.Entry> = ConcurrentHashMap()

    fun storeEntry(deviceSerial: String, packageName: String, newFiles: List<Apk>, overlayId: OverlayId, logger: ILogger) {
        // 存储到内存
        val key = String.format("%s:%s", deviceSerial, packageName)
        memoryCache[key] = createEntry(newFiles, overlayId)

        // 异步持久化到数据库
        postWithLock {
            deploymentCacheDatabase.store(deviceSerial, packageName, newFiles, overlayId)
        }
    }

    fun loadEntry(deviceSerial: String, packageName: String, logger: ILogger): DeploymentCacheDatabase.Entry? {
        // 从内存加载
        val memCache = memoryCache[String.format("%s:%s", deviceSerial, packageName)]
        if (memCache != null) {
            return memCache
        }

        // 从数据库加载
        return withLock { deploymentCacheDatabase[deviceSerial, packageName] }
    }
}
```

### 11.2 部署器

**核心类**: `JuggDeployer`

```kotlin
class JuggDeployer(
    private val adb: AdbClient,
    private val deploymentService: JuggDeploymentService,
    private val installer: Installer,
    private val service: UIService,
    private val exceptOverlayIds: Map<String, String>,
    private val isSkipExceptOverlayCheck: Boolean,
    private val logger: AdbLogWrapper
) {
    fun install(packageName: String, apks: List<String>, options: InstallOptions, installMode: InstallMode): Result {
        // 安装 APK
        result.skippedInstall = !AsDeployerCompat.install(...)

        // 解析 APK
        val apkList = AsDeployerCompat.parseApks(apks)
        val oid = OverlayId(apkList)

        // 更新数据库
        deploymentService.storeEntry(adb.serial, appId, apkList, oid, logger)

        result.overlayId = oid.sha
        return result
    }

    fun codeSwap(classFiles: List<String>, redefiners: Map<Int, ClassRedefiner>, data: JuggDeployData): Result {
        return optimisticSwap(classFiles, false, redefiners, data)
    }

    fun fullSwap(classFiles: List<String>, data: JuggDeployData): Result {
        return optimisticSwap(classFiles, true, ImmutableMap.of(), data)
    }

    private fun optimisticSwap(argPaths: List<String>, argRestart: Boolean, redefiners: Map<Int, ClassRedefiner>, data: JuggDeployData): Result {
        // 解析 APK
        val newFiles = AsDeployerCompat.parseApks(argPaths)

        // 获取包名和进程 ID
        val packageName = ApplicationDumper.getPackageName(newFiles)
        val pids = adb.getPids(packageName)
        val arch = adb.getArch(pids)

        // 从缓存加载
        val speculativeDump = deploymentService.loadEntry(deviceSerial, packageName, logger)

        // 验证缓存
        val verifyDump = verifyCache(speculativeDump, dumper, logger)

        // 构建更新数据
        val overlayUpdate = OverlayUpdateBuilder().build(verifyDump, data)

        // 执行交换
        val overlayId = AsDeployerCompat.optimisticSwap(...)

        // 更新数据库
        deploymentService.storeEntry(deviceSerial, packageName, newFiles, overlayId, logger)

        return Result().also { it.overlayId = overlayId.sha }
    }
}
```

---

## 十二、工具类

### 12.1 平台 API

**核心类**: `IdeaPlatformApi`

```kotlin
class IdeaPlatformApi : IPlatformApi {
    override fun showDialog(title: String, content: String, ...): Boolean {
        return CommonConfirmDialog.showAndGetResult(...)
    }

    override fun showChangeConfirmDialog(diffResult: DependencyDiffResult?, ...): ConfirmResult {
        return DependencyChangeDialogHelper(logger).showChangeConfirmDialog(...)
    }

    override fun showUserAndPasswordInputDialog(...): String? {
        return UserAndPasswordInputDialog.showAndGetResult(...)
    }

    override fun allAvailableJavaHomes(): List<String> {
        return ProjectJdkTable.getInstance().allJdks
            .filter { it.sdkType == JavaSdk.getInstance() }
            .map { it.homePath!! }
    }

    override fun getGradleJdkPath(project: Project, logger: Logger): String? {
        // 从模块获取 JDK
        val rootModule = AsDeployerCompat.getModuleManager(project).modules.find {
            it.name == project.name
        }
        val moduleRootManager = ModuleRootManager.getInstance(rootModule)
        return moduleRootManager.sdk?.homePath
    }

    override fun getAndroidHomePath(logger: Logger): String? {
        return CompileContextManager.getAndroidSdkRootDir(logger)?.absolutePath
            ?: System.getenv("ANDROID_HOME")
    }

    override fun getIdeVersion(): String {
        return AsDeployerCompat.ideVersion.toString()
    }

    override fun isHasRelaunchActivityIssues(device: IDeviceAdb, logger: Logger): Boolean {
        // Android 15 + Android Studio < Meerkat 有 relaunch activity 问题
        val isAndroid15 = device.api >= 35
        val meerkatVersion = IdeVersion("Android Studio Meerkat", "IA", "243.22562.218")
        val isBelowAndroidStudioMeerkat = AsDeployerCompat.ideVersion < meerkatVersion
        return isAndroid15 && isBelowAndroidStudioMeerkat
    }
}
```

### 12.2 运行时 Mock 工具

**核心类**: `RuntimeMockUtils`

```kotlin
object RuntimeMockUtils {
    val isTestMode = File(System.getProperty("user.home"), ".jugg_test_mode").exists()

    fun isNeedRunTest(): Boolean {
        return File("${System.getProperty("user.home")}/.jugg_runtime_test").exists()
    }

    fun runTest(logger: Logger): ExecutionResult {
        doRunTest(logger)
        val result = DefaultExecutionResult()
        result.processHandler.detachProcess()
        return result
    }
}
```

### 12.3 IDE Sync 问题解决器

**核心类**: `IdeSyncProblemResolver`

```kotlin
class IdeSyncProblemResolver(project: Project) {
    private val propertiesComponent = PropertiesComponent.getInstance(project)

    private var lastSyncSuccessTime: Long
        get() = propertiesComponent.getLong("lastSyncSuccessTime_${AsDeployerCompat.ideVersion}", 0L)
        set(value) = propertiesComponent.setValue("lastSyncSuccessTime", value.toString())

    fun isNeedSyncAfterBuild(): Boolean {
        return lastSyncSuccessTime <= 0 // 从未同步
    }

    fun onIdeSyncSucceeded() {
        lastSyncSuccessTime = System.currentTimeMillis()
    }
}
```

---

## 十三、总结

### 13.1 关键技术点

1. **热更新机制**: 使用自定义 ClassLoader 实现 JAR 动态加载
2. **版本兼容**: 通过 Proxy 模式适配多个 Android Studio 版本
3. **事件驱动**: 监听 Sync 和文件变化事件，自动触发编译
4. **状态管理**: 维护部署状态，决定使用全量编译还是增量编译
5. **UI 集成**: 提供运行配置、对话框、通知等 UI 组件

### 13.2 模块依赖

```
ide_entry
  ↓ (Proxy)
main
  ├── compiler (编译模块)
  ├── deploy (部署模块)
  ├── project (项目管理)
  ├── gradle (Gradle 集成)
  └── platform (平台抽象)
  ↓
deploy_compat (兼容层)
  ↓
platform_compat (平台 Mock)
```

### 13.3 扩展点

- **自定义编译器**: 通过 `CustomCompilerManager` 扩展
- **自定义配置**: 通过 `CustomConfigManager` 扩展
- **RPC 命令**: 通过 `RpcCaller` 扩展
- **兼容实现**: 通过 `IAsDeployerCompat` 扩展

---

## 附录：文件清单

### ide_entry

| 文件 | 说明 |
|------|------|
| `PluginLoadListener.java` | 插件加载监听器 |
| `JuggInitializer.kt` | Jugg 初始化器 |
| `JuggLoader.kt` | Jugg 加载器（热更新） |
| `JuggManagerCreator.kt` | Jugg 管理器创建器 |
| `JuggHotUpdateManager.kt` | 热更新管理器 |
| `JuggPriorityURLClassLoader.kt` | 优先级 ClassLoader |
| `JuggRunConfiguration.kt` | 运行配置 |
| `JuggConfigurationType.kt` | 运行配置类型 |
| `JuggRunConfigurationOptions.kt` | 运行配置选项 |
| `IJuggRunSettingsComponent.kt` | 运行设置组件接口 |
| `SyncEvent.kt` | Sync 事件 |
| `IJuggManagerCaller.kt` | Jugg 管理器调用接口 |

### main

| 文件 | 说明 |
|------|------|
| `JuggManager.kt` | 核心管理器 |
| `compiler/JuggCompilerHelper.kt` | 编译助手 |
| `compiler/JuggCompileUiHandler.kt` | 编译 UI 处理器 |
| `compiler/JuggGradleCompileTask.kt` | Gradle 编译任务 |
| `compiler/JuggCompileStatusHolder.kt` | 编译状态持有者 |
| `compiler/GradleOutputParser.kt` | Gradle 输出解析器 |
| `compiler/ForceGradleCompileHelper.kt` | 强制 Gradle 编译助手 |
| `compiler/DependencyMissingResolver.kt` | 依赖缺失解析器 |
| `deploy/run/JuggDeployerHelper.kt` | 部署助手 |
| `deploy/run/JuggDeployTask.kt` | 部署任务 |
| `deploy/run/JuggDeployer.kt` | 部署器 |
| `deploy/run/JuggDeploymentService.kt` | 部署服务 |
| `deploy/run/DeployOptions.kt` | 部署选项 |
| `deploy/run/DeployStateManager.kt` | 部署状态管理器 |
| `deploy/run/DeployTargetManager.kt` | 部署目标管理器 |
| `deploy/run/AsDeployerCompat.kt` | Android Studio 部署兼容层 |
| `project/ProjectInfoReader.kt` | 项目信息读取器 |
| `project/FileChangesDetector.kt` | 文件变化检测器 |
| `project/CompileContextManager.kt` | 编译上下文管理器 |
| `project/TaskRunnerManager.kt` | 任务运行管理器 |
| `ide/logic/JuggRunningTask.kt` | 运行任务 |
| `ide/logic/IdeaPlatformApi.kt` | IDE 平台 API |
| `ide/logic/MoreOptionsManager.kt` | 更多选项管理器 |
| `ide/logic/JuggRunSettingsComponent.kt` | 运行设置组件 |
| `ide/logic/RuntimeMockUtils.kt` | 运行时 Mock 工具 |
| `ide/logic/IdeSyncProblemResolver.kt` | IDE Sync 问题解决器 |
| `ide/ui/JuggCommonNotification.kt` | 通用通知 |
| `ide/ui/BuildChangesConfirmDialog.kt` | 构建变化确认对话框 |
| `ide/ui/CommonConfirmDialog.kt` | 通用确认对话框 |
| `ide/ui/GradleCompileAction.kt` | Gradle 编译动作 |
| `ide/ui/RestartAppAction.kt` | 重启 App 动作 |
| `rpc/RpcCaller.kt` | RPC 调用器 |
| `rpc/LogCollector.kt` | 日志收集器 |
| `server/JuggHotUpdateDownloader.kt` | 热更新下载器 |

### deploy_compat

| 文件 | 说明 |
|------|------|
| `AsDeployerCompat.kt` | Android Studio 部署兼容层 |
| `IAsDeployerCompat.kt` | Android Studio 部署兼容接口 |
| `OtterAsDeployerFeatureCompat.kt` | Otter 2 FD 兼容实现 |
| `NarwhalAsDeployerFeatureCompat.kt` | Narwhal FD 兼容实现 |
| `NarwhalAsDeployerCompat.kt` | Narwhal 兼容实现 |
| `MeerkatAsDeployerCompat.kt` | Meerkat 兼容实现 |
| `IguanaAsDeployerCompat.kt` | Iguana 兼容实现 |
| `HedgehogAsDeployerCompat.kt` | Hedgehog 兼容实现 |
| `GiraffeAsDeployerCompat.kt` | Giraffe 兼容实现 |
| `ChipmunkAsDeployerCompat.kt` | Chipmunk 兼容实现 |
