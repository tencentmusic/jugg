# Jugg 部署系统 - 完整部署机制

---

## 一、部署系统完整架构

### 1.1 部署流程总览

```
用户触发部署
    ↓
JuggDeployerHelper (部署协调器)
    ├─ 状态检查 (DeployStateManager)
    ├─ 数据准备 (DeployFileManager)
    ├─ 设备选择 (DeployTargetManager)
    └─ 历史管理 (DeployHistoryManager)
    ↓
JuggDeployTask (部署任务)
    ├─ 部署类型选择 (AndroidDeployType)
    ├─ 部署器创建 (JuggDeployer)
    └─ 上下文准备 (LaunchContext)
    ↓
JuggDeployer (核心部署器)
    ├─ install() - APK 安装
    ├─ codeSwap() - 代码热替换
    └─ fullSwap() - 重启 Activity
    ↓
AsDeployerCompat (AS 兼容层)
    ├─ 版本适配 (8个 IDE 版本)
    ├─ ADB 通信 (AdbClient)
    └─ 安装器 (Installer)
    ↓
Android 设备
```

### 1.2 核心组件关系

| 组件 | 职责 | 依赖关系 |
|------|------|----------|
| **JuggDeployerHelper** | 部署协调器 | 依赖所有管理器 |
| **JuggDeployTask** | 部署任务执行 | 依赖 JuggDeployer |
| **JuggDeployer** | 核心部署逻辑 | 依赖 AsDeployerCompat |
| **AsDeployerCompat** | AS 版本兼容 | 代理到具体版本实现 |
| **DeployStateManager** | 部署状态管理 | 独立 |
| **DeployFileManager** | 部署文件管理 | 独立 |
| **DeployTargetManager** | 设备目标管理 | 独立 |
| **DeployHistoryManager** | 部署历史管理 | 独立 |

---

## 二、JuggDeployerHelper - 部署协调器

### 2.1 核心职责

**定义位置**: `JuggDeployerHelper.kt`

| 职责 | 说明 |
|------|------|
| **部署协调** | 协调整个部署流程 |
| **状态管理** | 管理部署状态和恢复 |
| **错误重试** | 处理部署错误和重试 |
| **性能监控** | 监控部署性能和时间 |
| **JVMTI 管理** | 管理 JVMTI Agent |

### 2.2 部署流程

```kotlin
fun deploy(deployOptions: DeployOptions): DeployTaskResult {
    // 1. 检查取消状态
    if (deployOptions.processHandler?.isCanceled == true) {
        return DeployTaskResult(isSuccess = false, costTime = costTime(), failedReason = "deploy canceled")
    }
    
    // 2. 获取部署数据
    val deployData = deployOptions.retryDeployData ?: deployFileManager.getDeployData(deployOptions.isWarmUp, ...)
    
    // 3. 检查是否需要重新签名 APK
    if (deployData.isNeedUpdateApk && !isRetry) {
        val (isSuccess, failedReason) = IncrementalDeployHelper.updateApk(deployData.apks, deployData.updateApkFiles)
        if (!isSuccess) {
            return DeployTaskResult(isSuccess = false, isCanFallback = true, costTime = costTime(), failedReason = failedReason)
        }
    }
    
    // 4. 恢复部署状态
    if (isNeedReinstallApk || !deployStateManager.getDeployState(device).isReadyDeploy) {
        val (isSuccess, isReinstalled) = recoverDeployState(device, deployOptions.indicator, ...)
        if (!isSuccess) {
            return DeployTaskResult(isSuccess = false, isCanFallback = true, costTime = costTime(), failedReason = "Try recover deploy state failed.")
        }
    }
    
    // 5. 执行部署任务
    val launchResult = runTask(device, deployData, finalIsSkipExceptOverlayCheck)
    
    // 6. 更新部署历史
    if (deployOptions.isLastDevice) {
        updateInfoAfterIncDeploy(launchResult, deployData)
    }
    
    return DeployTaskResult(isSuccess = true, costTime = costTime(), deployType = deployData.deployType)
}
```

### 2.3 错误重试机制

**重试策略**:
```kotlin
private fun tryRetry(
    deployOptions: DeployOptions,
    finalIsFallbackAllHotFix: Boolean,
    deployData: JuggDeployData,
    reason: String,
): DeployTaskResult? {
    
    // 1. JVMTI 兼容性问题
    val isRedeployWithCompatMode = reason.contains(REDEPLOY_WITH_COMPAT_MESSAGE)
    if (isRedeployWithCompatMode) {
        val nextRetryDeployData = deployFileManager.appendCompatDeployFiles(deployData)
        val nextDeployOptions = deployOptions.copy(retryReason = reason, retryDeployData = nextRetryDeployData, isSkipExceptOverlayCheck = true)
        return deploy(nextDeployOptions)
    }
    
    // 2. 类修改错误 (降级到 hot_fix)
    val isUnmodifiableClass = reason.contains("JVMTI_ERROR_UNMODIFIABLE_CLASS")
    val isRequiresAppRestart = reason.contains("app restart")
    val isRedifinerError = reason.contains("R+ Device should have FULL debugger swap support")
    val isInternalError = reason.contains("JVMTI_ERROR_INTERNAL")
    
    val isClassModifiedError = (!finalIsFallbackAllHotFix) && (isUnmodifiableClass || isRequiresAppRestart || isRedifinerError || isInternalError)
    if (isClassModifiedError) {
        val nextRetryDeployData = deployData.toFallbackToHotFixData()
        val nextDeployOptions = deployOptions.copy(retryReason = reason, retryDeployData = nextRetryDeployData, isSkipExceptOverlayCheck = true)
        return deploy(nextDeployOptions)
    }
    
    // 3. Agent 无响应
    val isAgentNotResponses = reason.contains("MISSING_AGENT_RESPONSES") || reason.contains("AGENT_ATTACH_FAILED")
    val isDeployTimeout = reason.contains("MessagePipeWrapper read() timeout")
    if (isAgentNotResponses || isDeployTimeout) {
        if (detectJvmtiCompatIssue(deployOptions.device, deployData)) {
            val nextRetryDeployData = deployFileManager.appendCompatDeployFiles(deployData)
            val nextDeployOptions = deployOptions.copy(retryReason = reason, retryDeployData = nextRetryDeployData, isSkipExceptOverlayCheck = true)
            return deploy(nextDeployOptions)
        }
    }
    
    // 4. Overlay ID 不匹配
    val isOverlayIdNotCorrect = reason.contains("OVERLAY_ID_MISMATCH") || reason.contains("unable to recognize the APK")
    val isClassNotFoundException = reason.contains("Class not found")
    val isOverlayIdNotMatch = reason.contains("The target app on the device is in a state unknown to Studio")
    
    if (isOverlayIdNotCorrect || isClassNotFoundException || isOverlayIdNotMatch) {
        val (isSuccess, _) = recoverDeployState(deployOptions.device, deployOptions.indicator, ...)
        if (isSuccess) {
            val nextDeployOptions = deployOptions.copy(retryReason = reason, isSkipExceptOverlayCheck = true)
            return deploy(nextDeployOptions)
        }
    }
    
    return null
}
```

### 2.4 部署状态恢复

**状态恢复流程**:
```kotlin
private fun recoverDeployState(
    device: IDevice, 
    indicator: ProgressIndicator?,
    isNeedTryDeyDeployFirst: Boolean,
    isSkipExceptOverlayCheck: Boolean,
    isInstallUpdateApk: Boolean = false,
): Pair<Boolean, Boolean> {
    
    // 1. 尝试 Dry Deploy
    if (isNeedTryDeyDeployFirst && !isCleanAndReinstall) {
        val isSuccess = tryDryDeploy(device, isSkipExceptOverlayCheck)
        if (isSuccess) {
            return true to false
        }
    }
    
    // 2. 重新安装 APK
    val deployData = JuggDeployData.forInstall(deployTargetManager.getApks())
    runTask(device, deployData)
    
    // 3. 等待设备可部署
    val isDeviceDeployable = waitingForDeployable(device, maxWaitTimeSecond = 5)
    if (!isDeviceDeployable) {
        return false to false
    }
    
    // 4. 重置部署文件
    deployFileManager.resetAfterReinstall()
    
    return true to true
}
```

### 2.5 JVMTI Agent 管理

**Agent 推送和检测**:
```kotlin
private fun detectJvmtiCompatIssue(device: IDevice, deployData: JuggDeployData): Boolean {
    val adb = IdeaDeviceAdb(device, logger)
    val jvmtiAgentManagerHelper = JuggJvmtiAgentManagerHelper(logger)
    
    // 1. 检查是否需要推送 Agent
    if (jvmtiAgentManagerHelper.isNeedPushAgentAfterDeploy(adb, deployData)) {
        jvmtiAgentManagerHelper.pushAgentToApps(adb, deployData)
        deployTargetManager.restartApp(device)
    }
    
    // 2. 检测兼容性问题
    return jvmtiAgentManagerHelper.isHasJvmtiCompatIssue(adb, deployData)
}
```

---

## 三、AsDeployerCompat - Android Studio 兼容层

### 3.1 核心职责

**定义位置**: `AsDeployerCompat.kt`

| 职责 | 说明 |
|------|------|
| **版本兼容** | 支持 8 个 Android Studio 版本 |
| **动态代理** | 使用 Java Proxy 实现动态适配 |
| **降级策略** | 高版本降级到低版本 API |
| **错误处理** | 处理兼容性错误 |

### 3.2 版本兼容架构

**支持的 IDE 版本**:
```kotlin
private val compatImplList = listOf(
    CompatImpl("Android Studio Otter 2 Feature Drop", "IA", "252.27397.103", OtterAsDeployerFeatureCompat()),
    CompatImpl("Android Studio Narwhal Feature Drop", "IA", "251.27812.49", NarwhalAsDeployerFeatureCompat()),
    CompatImpl("Android Studio Narwhal", "IA", "251.23774.16", NarwhalAsDeployerCompat()),
    CompatImpl("Android Studio Meerkat", "IA", "243.22562.218", MeerkatAsDeployerCompat()),
    CompatImpl("Android Studio Iguana", "IA", "232.10227.8", IguanaAsDeployerCompat()),
    CompatImpl("Android Studio Hedgehog", "IA", "231.9225.16", HedgehogAsDeployerCompat()),
    CompatImpl("Android Studio Giraffe", "IA", "223.8836.35", GiraffeAsDeployerCompat()),
    CompatImpl("Android Studio Chipmunk", "IA", "212.5712.43", ChipmunkAsDeployerCompat()),
)
```

**版本选择策略**:
```kotlin
fun init(logger: Logger) {
    // 1. 精确匹配
    var impl: CompatImpl? = compatImplList.firstNotNullOfOrNull { compatImpl ->
        if (compatImpl.ideVersion == ideVersion) {
            return@firstNotNullOfOrNull compatImpl
        }
        return@firstNotNullOfOrNull null
    }
    
    // 2. 高版本降级
    if (impl == null) {
        impl = compatImplList.firstNotNullOfOrNull { compatImpl ->
            if (compatImpl.ideVersion < ideVersion) {
                return@firstNotNullOfOrNull compatImpl
            }
            return@firstNotNullOfOrNull null
        }
    }
    
    // 3. 低版本升级
    if (impl == null) {
        impl = compatImplList.last()
    }
    
    this.priorityImpl = impl
}
```

### 3.3 动态代理实现

**代理调用机制**:
```kotlin
private val impl : IAsDeployerCompat = Proxy.newProxyInstance(this.javaClass.classLoader,
    arrayOf<Class<*>>(IAsDeployerCompat::class.java), object : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<Any?>?): Any? {
            try {
                // 1. 优先使用优先级实现
                return method.invoke(priorityImpl.impl.value, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                if (!e.targetException.isCompatError) {
                    throw e.targetException
                }
                
                // 2. 兼容性错误，尝试其他版本
                compatImplList
                    .filter { it.ideVersion != priorityImpl.ideVersion }
                    .forEach {
                        try {
                            val result = method.invoke(it.impl.value, *(args ?: emptyArray()))
                            return result
                        } catch (e: InvocationTargetException) {
                            // 继续尝试下一个版本
                        }
                    }
                
                throw e
            }
        }
    }) as IAsDeployerCompat
```

**兼容性错误检测**:
```kotlin
private val Throwable.isCompatError: Boolean get() {
    return this is NoSuchMethodError
            || this is NoSuchFieldError
            || this is NoClassDefFoundError
            || this is IncompatibleClassChangeError
}
```

### 3.4 版本信息管理

**IDE 版本信息**:
```kotlin
data class IdeVersion(
    val name: String,           // "Android Studio Chipmunk | 2021.2.1 Patch 1"
    val type: String,           // "IA" (Android Studio)
    val mainVersion: String,    // "212.5712.43"
    val fullVersion: String? = null,
) {
    
    constructor(applicationInfo: ApplicationInfo) : this (
        applicationInfo.fullApplicationName,
        applicationInfo.build.productCode,
        applicationInfo.apiVersion.substringAfter('-'),
        applicationInfo.build.asStringWithoutProductCodeAndSnapshot()
    )
    
    operator fun compareTo(version: IdeVersion): Int {
        val a = mainVersion.split('.')
        val b = version.mainVersion.split('.')
        val length = min(a.size, b.size)
        for (i in 0 until length) {
            val aI = a[i].toInt()
            val bI = b[i].toInt()
            if (aI != bI) return aI - bI
        }
        return a.size - b.size
    }
}
```

---

## 四、部署管理器组件

### 4.1 DeployStateManager - 部署状态管理器

**核心功能**:
- 管理设备部署状态
- 检查设备是否可部署
- 更新部署状态

**状态定义**:
```kotlin
data class DeployState(
    val isReadyDeploy: Boolean,      // 是否可部署
    val isReadyIncCompile: Boolean,  // 是否可增量编译
    val device: IDevice?,            // 目标设备
)
```

### 4.2 DeployFileManager - 部署文件管理器

**核心功能**:
- 管理部署文件
- 生成部署数据
- 处理文件变更

**文件管理**:
```kotlin
fun getDeployData(isWarmUp: Boolean, isNeedPushResourceApk: Boolean): JuggDeployData {
    // 生成部署数据
}

fun commit(deployData: JuggDeployData) {
    // 提交部署文件
}

fun resetAfterReinstall() {
    // 重新安装后重置
}
```

### 4.3 DeployTargetManager - 设备目标管理器

**核心功能**:
- 管理目标设备
- 控制应用启动/停止
- 检查应用状态

**设备操作**:
```kotlin
fun stopApp(device: IDevice): Boolean
fun restartApp(device: IDevice): Boolean
fun startApp(device: IDevice): Boolean
fun isAppForeground(device: IDevice): Boolean
```

### 4.4 DeployHistoryManager - 部署历史管理器

**核心功能**:
- 管理部署历史
- 跟踪 Overlay ID
- 清理历史记录

**历史管理**:
```kotlin
var lastDeployOverlayIds: Map<String, String>  // 上次部署的 Overlay ID
fun updateHistoryOnAfterDeployed(deployedFiles: List<DeployItem>)
```

---

## 五、部署数据结构

### 5.1 DeployOptions - 部署选项

```kotlin
data class DeployOptions(
    val device: IDevice,
    val isInstall: Boolean,
    val isWarmUp: Boolean,
    val isLastDevice: Boolean,
    val isSkipExceptOverlayCheck: Boolean,
    val processHandler: ProcessHandler?,
    val indicator: ProgressIndicator?,
    val startTime: Long,
    val retryReason: String? = null,
    val retryDeployData: JuggDeployData? = null,
    val timeOutRetryTimes: Int = 0,
)
```

### 5.2 DeployTaskResult - 部署任务结果

```kotlin
data class DeployTaskResult(
    val isSuccess: Boolean,
    val costTime: Long,
    val isCanFallback: Boolean = false,
    val deployType: JuggDeployData.DeployType? = null,
    val failedReason: String? = null,
    val costTimeExceptCheck: Long = costTime,
)
```

### 5.3 LaunchResult - 启动结果

```kotlin
data class LaunchResult(
    val success: Boolean,
    val errorCode: Int,
    val errorMessage: String?,
    val overlayIds: Map<String, String>,
    var pushingAgentCostTime: Long = 0,
    var checkJvmtiCostTime: Long = 0,
)
```

---

## 六、部署性能优化

### 6.1 切片部署

**切片策略**:
```kotlin
val (firstSliceSize, sliceSize) = SliceDeployHelper(logger).get(IdeaDeviceAdb(device, logger))
val dataList = data.splitData(firstSliceSize, sliceSize)
```

**切片部署**:
```kotlin
dataList.forEachIndexed { i, splitData ->
    val isSliceSkipExceptOverlayCheck = isSkipExceptOverlayCheck || i != 0
    val launchContext = LaunchContext(device, deployHistoryManager.lastDeployOverlayIds, isSliceSkipExceptOverlayCheck)
    val task = JuggDeployTask(project, installPathProvider, androidDeployType, splitData)
    launchResult = task.run(launchContext)
}
```

### 6.2 时间监控

**性能监控点**:
```kotlin
TimeLogger.start("deploy_to_device")
// ... 部署逻辑
TimeLogger.end("deploy_to_device", logger)

TimeLogger.start("push_agent")
// ... Agent 推送
launchResult.pushingAgentCostTime = TimeLogger.end("push_agent", logger)

TimeLogger.start("check_jvmti")
// ... JVMTI 检查
launchResult.checkJvmtiCostTime = TimeLogger.end("check_jvmti", logger)
```

### 6.3 懒加载和缓存

**懒加载策略**:
- 仅在需要时加载部署数据
- 缓存部署状态
- 延迟初始化组件

---

## 七、设计亮点总结

### 7.1 架构设计

| 亮点 | 说明 |
|------|------|
| **分层架构** | 协调器 → 任务 → 部署器 → 兼容层 |
| **组件解耦** | 管理器组件职责明确，相互独立 |
| **插件化设计** | 支持自定义部署策略 |

### 7.2 兼容性设计

| 亮点 | 说明 |
|------|------|
| **多版本支持** | 支持 8 个 Android Studio 版本 |
| **动态代理** | 运行时适配不同版本 API |
| **降级策略** | 高版本降级到低版本 API |
| **错误恢复** | 自动检测和恢复兼容性问题 |

### 7.3 容错设计

| 亮点 | 说明 |
|------|------|
| **错误重试** | 多层重试机制 |
| **状态恢复** | 自动恢复部署状态 |
| **降级部署** | hot_reload → hot_fix → install |
| **超时处理** | 部署超时自动重试 |

### 7.4 性能优化

| 亮点 | 说明 |
|------|------|
| **切片部署** | 大文件切片部署 |
| **增量部署** | 仅部署变更文件 |
| **缓存优化** | 复用部署缓存 |
| **异步操作** | 异步检测和推送 |

---

## 八、部署流程完整总结

### 8.1 正常部署流程

```
1. 用户触发部署
2. JuggDeployerHelper 检查状态
3. 准备部署数据 (DeployFileManager)
4. 选择目标设备 (DeployTargetManager)
5. 检查部署历史 (DeployHistoryManager)
6. 执行部署任务 (JuggDeployTask)
7. 调用核心部署器 (JuggDeployer)
8. 通过兼容层部署 (AsDeployerCompat)
9. 更新部署历史
10. 返回部署结果
```

### 8.2 错误恢复流程

```
1. 部署失败
2. 分析错误类型
3. 选择重试策略
   - JVMTI 兼容性问题 → 兼容模式部署
   - 类修改错误 → 降级到 hot_fix
   - Agent 无响应 → 检测 JVMTI 兼容性
   - Overlay ID 不匹配 → 恢复部署状态
4. 执行重试部署
5. 返回重试结果
```

### 8.3 状态恢复流程

```
1. 检测部署状态异常
2. 尝试 Dry Deploy
3. 如果失败，重新安装 APK
4. 等待设备可部署
5. 重置部署文件
6. 返回恢复结果
```

---

**文档状态**: ✅ 已完成  
**部署模块状态**: ✅ 已完成核心部署机制分析  
**下一步**: 开始阶段 4 - 项目管理模块分析
