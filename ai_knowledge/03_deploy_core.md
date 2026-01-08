# Jugg 部署系统 - 核心部署机制

> 文档版本: v1.0  
> 创建时间: 2025-01-20  
> 涵盖模块: idea/deploy/run/*.kt (部分核心文件)

---

## 一、部署系统概览

### 1.1 部署架构

```
编译产物 (Dex/Res/Asset/...)
    ↓
JuggDeployTask (部署任务)
    ├─ AndroidDeployType (部署类型)
    ├─ JuggDeployData (部署数据)
    └─ LaunchContext (启动上下文)
    ↓
JuggDeployer (部署器)
    ├─ install() - 安装 APK
    ├─ codeSwap() - 代码热替换 (hot_reload)
    └─ fullSwap() - 重启 Activity (hot_fix)
    ↓
AsDeployerCompat (Android Studio 兼容层)
    ├─ AdbClient (ADB 通信)
    ├─ Installer (安装器)
    └─ UIService (UI 服务)
    ↓
设备 (Android Device)
```

### 1.2 核心组件

| 组件 | 职责 |
|------|------|
| **JuggDeployer** | 核心部署器，提供 install/codeSwap/fullSwap 三种部署方式 |
| **JuggDeployTask** | 部署任务，协调部署流程 |
| **JuggDeployData** | 部署数据，包含编译产物信息 |
| **OverlayUpdateBuilder** | Overlay 更新构建器 |
| **JuggDeploymentService** | 部署缓存服务 |
| **AsDeployerCompat** | Android Studio Deployer 兼容层 |
| **AdbLogWrapper** | ADB 日志包装器 |

---

## 二、JuggDeployer - 核心部署器

### 2.1 核心职责

**定义位置**: `JuggDeployer.kt`

| 职责 | 说明 |
|------|------|
| **APK 安装** | 安装 APK 到设备 |
| **代码热替换** | 无需重启的代码热替换 (hot_reload) |
| **Activity 重启** | 重启 Activity 的热修复 (hot_fix) |
| **Overlay 管理** | 管理 Overlay ID 和缓存 |
| **缓存验证** | 验证部署缓存的一致性 |

### 2.2 三种部署方式

#### 2.2.1 install() - APK 安装

**方法签名**:
```kotlin
fun install(
    packageName: String, 
    apks: List<String>, 
    options: InstallOptions, 
    argInstallMode: InstallMode
): Result
```

**部署流程**:
```kotlin
fun install(...): Result {
    val result = Result()
    
    // 1. 调整安装模式
    var installMode = argInstallMode
    if (installMode == InstallMode.DELTA) {
        installMode = InstallMode.DELTA_NO_SKIP
    }
    
    // 2. 执行安装
    try {
        result.skippedInstall = !AsDeployerCompat.install(
            adb, service, installer, logger,
            packageName, apks, options, installMode,
        )
    } catch (e: Exception) {
        // 设备未找到，重试 FULL 模式
        if (e.message?.contains("not found") == true) {
            if (installMode != InstallMode.FULL) {
                installMode = InstallMode.FULL
                Thread.sleep(2000)
                result.skippedInstall = !AsDeployerCompat.install(...)
            }
        } else {
            throw e
        }
    }
    
    // 3. 更新缓存
    val apkList = AsDeployerCompat.parseApks(apks)
    val appId = ApplicationDumper.getPackageName(apkList)
    val oid = OverlayId(apkList)
    deploymentService.storeEntry(adb.serial, appId, apkList, oid, logger)
    result.overlayId = oid.sha
    
    return result
}
```

**安装模式**:

| 模式 | 说明 |
|------|------|
| `FULL` | 完整安装，卸载旧版本后重新安装 |
| `DELTA` | 增量安装，仅安装变更的 APK |
| `DELTA_NO_SKIP` | 增量安装，不跳过未变更的 APK |

**Overlay ID**:
- **定义**: APK 文件的唯一标识（基于 SHA）
- **用途**: 验证设备上的 APK 与本地 APK 是否一致
- **isBaseInstall**: 是否为基础安装（非 Overlay 安装）

#### 2.2.2 codeSwap() - 代码热替换 (hot_reload)

**方法签名**:
```kotlin
fun codeSwap(
    classFiles: List<String>, 
    redefiners: Map<Int, ClassRedefiner>, 
    data: JuggDeployData
): Result
```

**特点**:
- **无需重启**: 应用无需重启，Activity 无需重启
- **代码热替换**: 使用 JVMTI 热替换类定义
- **调试器支持**: 支持调试器重定义类
- **限制**: 仅支持方法体修改，不支持添加/删除字段和方法

**实现**:
```kotlin
fun codeSwap(classFiles: List<String>, redefiners: Map<Int, ClassRedefiner>, data: JuggDeployData): Result {
    return optimisticSwap(classFiles, false, redefiners, data)
}
```

#### 2.2.3 fullSwap() - 重启 Activity (hot_fix)

**方法签名**:
```kotlin
fun fullSwap(
    classFiles: List<String>, 
    data: JuggDeployData
): Result
```

**特点**:
- **重启 Activity**: 重启当前 Activity
- **应用无需重启**: 应用进程无需重启
- **支持结构变更**: 支持添加/删除字段和方法
- **Overlay 机制**: 使用 Overlay 机制替换资源和代码

**实现**:
```kotlin
fun fullSwap(classFiles: List<String>, data: JuggDeployData): Result {
    return optimisticSwap(classFiles, true, ImmutableMap.of(), data)
}
```

### 2.3 optimisticSwap() - 统一部署实现

**核心流程**:
```kotlin
private fun optimisticSwap(
    argPaths: List<String>, 
    argRestart: Boolean, 
    redefiners: Map<Int, ClassRedefiner>, 
    data: JuggDeployData
): Result {
    // 1. 检查 Android 版本（需要 >= O）
    if (!adb.version.isGreaterOrEqualThan(AndroidVersion.VersionCodes.O)) {
        throw DeployerException.apiNotSupported()
    }
    
    // 2. 解析 APK 文件
    val newFiles = AsDeployerCompat.parseApks(argPaths)
    
    // 3. 获取应用信息
    val packageName = ApplicationDumper.getPackageName(newFiles)
    val pids = adb.getPids(packageName)
    var arch = adb.getArch(pids)
    
    // 4. 加载部署缓存
    val speculativeDump = deploymentService.loadEntry(deviceSerial, packageName, logger)
    
    // 5. 验证 Overlay ID
    val exceptOverlayId = exceptOverlayIds[packageName]
    if (!isSkipExceptOverlayCheck) {
        if (exceptOverlayId != speculativeDump?.overlayId?.sha) {
            throw DeployerException.overlayIdMismatch()
        }
    }
    
    // 6. 验证缓存
    val verifyDump = verifyCache(speculativeDump, dumper, logger)
    
    // 7. 构建 Overlay 更新
    val builder = OverlayUpdateBuilder()
    val overlayUpdate = builder.build(verifyDump, data)
    
    // 8. 执行部署
    val overlayId = AsDeployerCompat.optimisticSwap(
        installer, redefiners, packageName,
        argRestart, pids, arch, overlayUpdate,
        adb, logger,
        data.isPushOverlayOnly,
    )
    
    // 9. 更新缓存
    deploymentService.storeEntry(deviceSerial, packageName, newFiles, overlayId, logger)
    
    return Result().also {
        it.overlayId = overlayId.sha
    }
}
```

### 2.4 缓存验证

**目的**: 确保设备上的 APK 与本地缓存一致

**验证流程**:
```kotlin
private fun verifyCache(
    entry: DeploymentCacheDatabase.Entry?, 
    dumper: ApplicationDumper, 
    logger: AdbLogWrapper
): DeploymentCacheDatabase.Entry {
    if (entry == null) {
        throw DeployerException.remoteApkNotFound()
    }
    
    if (!entry.overlayId.isBaseInstall) {
        // 非基础安装，在 Agent 上验证
        logger.info("verifyCache on agent, skip")
        return entry
    }
    
    // 基础安装，验证 APK
    val cachedResults = entry.apks
    val actualResults = try {
        dumper.dump(entry.apks).apks
    } catch (e: Exception) {
        // 设备离线，重试
        if (e.message?.contains("device offline") == true) {
            Thread.sleep(2000)
            dumper.dump(entry.apks).apks
        } else {
            throw e
        }
    }
    
    // 验证 APK 数量
    if (cachedResults.size != actualResults.size) {
        throw DeployerException.overlayIdMismatch()
    }
    
    // 验证每个 APK
    cachedResults.sortWith(Comparator.comparing { apk: Apk -> apk.name })
    actualResults.sortWith(Comparator.comparing { apk: Apk -> apk.name })
    
    for (i in 0 until cachedResults.size) {
        val cached = cachedResults[i]
        val actual = actualResults[i]
        
        if (cached.name != actual.name) {
            throw DeployerException.overlayIdMismatch()
        } else if (cached.checksum != actual.checksum) {
            throw DeployerException.overlayIdMismatch()
        }
    }
    
    return entry
}
```

**验证场景**:
1. **基础安装**: 验证 APK 文件名和 Checksum
2. **Overlay 安装**: 在 Agent 上验证，跳过本地验证

### 2.5 Result 数据结构

```kotlin
class Result {
    var skippedInstall = false  // 是否跳过安装
    var needsRestart = false    // 是否需要重启
    var overlayId: String? = null  // Overlay ID
}
```

---

## 三、JuggDeployTask - 部署任务

### 3.1 核心职责

**定义位置**: `JuggDeployTask.kt`

| 职责 | 说明 |
|------|------|
| **部署协调** | 协调部署流程 |
| **多包支持** | 支持多个 APK 包的部署 |
| **错误处理** | 处理部署错误并返回结果 |
| **启动控制** | 控制应用启动和重启 |

### 3.2 部署流程

```kotlin
fun run(launchContext: LaunchContext): LaunchResult {
    val stopwatch = Stopwatch.createStarted()
    val device = launchContext.device
    val logger = AdbLogWrapper(logger)
    val adb = AdbClient(device, logger)
    val ideService = IdeService(project)
    val adbInstaller = AsDeployerCompat.getInstaller(installPathProvider.compute(), adb, logger)
    
    // 1. 创建部署器
    val deployer = JuggDeployer(
        adb, JuggDeploymentService, adbInstaller, ideService,
        launchContext.exceptOverlayIds,
        launchContext.isSkipExceptOverlayCheck,
        logger
    )
    
    val idsSkippedInstall: MutableList<String> = ArrayList()
    val overlayIds = mutableMapOf<String, String>()
    
    // 2. 按包分组
    val packages: Map<String, List<ApkInfo>> = data.apks.groupBy { it.applicationId }
    
    // 3. 逐包部署
    for ((applicationId, apkInfos) in packages) {
        try {
            launchContext.launchApp = shouldTaskLaunchApp()
            val apkFiles = apkInfos.flatMap { it.files }.map { it.apkFile }
            val result = perform(device, deployer, applicationId, apkFiles)
            
            if (result.skippedInstall) {
                idsSkippedInstall.add(applicationId)
            }
            if (result.needsRestart) {
                launchContext.killBeforeLaunch = true
                launchContext.launchApp = true
            }
            overlayIds[applicationId] = result.overlayId ?: ""
        } catch (e: DeployerException) {
            return LaunchResult(false, e.error.ordinal, e.message + " " + e.details, emptyMap())
        }
    }
    
    stopwatch.stop()
    val duration = stopwatch.elapsed(TimeUnit.MILLISECONDS)
    
    return LaunchResult(true, 0, null, overlayIds)
}
```

### 3.3 部署类型

**AndroidDeployType 枚举**:
```kotlin
enum class AndroidDeployType {
    INSTALL,  // 安装
    APPLY_CHANGES_AND_RESTART_ACTIVITY,  // 应用变更并重启 Activity
    APPLY_CHANGES,  // 应用变更
}
```

**部署类型对应关系**:

| 部署类型 | JuggDeployer 方法 | 是否重启 | 是否启动应用 |
|---------|------------------|---------|------------|
| `INSTALL` | `install()` | 否 | 是 |
| `APPLY_CHANGES_AND_RESTART_ACTIVITY` | `fullSwap()` | Activity 重启 | 是 |
| `APPLY_CHANGES` | `codeSwap()` | 否 | 否 |

### 3.4 perform() - 执行部署

```kotlin
private fun perform(
    device: IDevice, 
    deployer: JuggDeployer, 
    applicationId: String, 
    files: List<File>
): JuggDeployer.Result {
    when (type) {
        AndroidDeployType.INSTALL -> {
            // 1. 构建安装选项
            val options = InstallOptions.builder().setAllowDebuggable()
            if (device.supportsFeature(IDevice.HardwareFeature.EMBEDDED)) {
                options.setGrantAllPermissions()
            }
            if (device.version.isGreaterOrEqualThan(28)) {
                options.setInstallFullApk()
            }
            if (device.version.isGreaterOrEqualThan(AndroidVersion.VersionCodes.N)) {
                options.setDontKill()
            }
            options.setSkipVerification(device, applicationId)
            
            // 2. 确定安装模式
            var installMode = InstallMode.DELTA
            if (!StudioFlags.DELTA_INSTALL.get()) {
                installMode = InstallMode.FULL
            }
            
            // 3. 执行安装
            return deployer.install(applicationId, getPathsToInstall(files), options.build(), installMode)
        }
        
        AndroidDeployType.APPLY_CHANGES_AND_RESTART_ACTIVITY -> {
            // 执行 fullSwap
            return deployer.fullSwap(getPathsToInstall(files), data)
        }
        
        AndroidDeployType.APPLY_CHANGES -> {
            // 执行 codeSwap
            val fastRerunOnSwapFailure = false
            var debuggerRedefiners = emptyMap<Int, ClassRedefiner>()
            
            if (!data.isNeedRestartApp) {
                debuggerRedefiners = AsDeployerCompat.makeDebuggerRedefiners(
                    project, device, fastRerunOnSwapFailure && deployer.supportsNewPipeline()
                )
            }
            
            return deployer.codeSwap(getPathsToInstall(files), debuggerRedefiners, data)
        }
    }
}
```

### 3.5 LaunchContext - 启动上下文

```kotlin
class LaunchContext(
    val device: IDevice,
    val exceptOverlayIds: Map<String, String>,
    val isSkipExceptOverlayCheck: Boolean,
) {
    var launchApp: Boolean = false
    var killBeforeLaunch: Boolean = false
}
```

**字段说明**:

| 字段 | 类型 | 说明 |
|------|------|------|
| `device` | IDevice | 目标设备 |
| `exceptOverlayIds` | Map<String, String> | 期望的 Overlay ID |
| `isSkipExceptOverlayCheck` | Boolean | 是否跳过 Overlay ID 检查 |
| `launchApp` | Boolean | 是否启动应用 |
| `killBeforeLaunch` | Boolean | 启动前是否杀死应用 |

---

## 四、OverlayUpdateBuilder - Overlay 更新构建器

### 4.1 核心职责

**定义位置**: `OverlayUpdateBuilder.kt`

| 职责 | 说明 |
|------|------|
| **构建 Overlay 更新** | 构建 Overlay 更新数据 |
| **类变更处理** | 处理新增类和修改类 |
| **文件映射** | 映射 Overlay 文件到 APK |

### 4.2 构建流程

```kotlin
fun build(cacheEntry: DeploymentCacheDatabase.Entry?, data: JuggDeployData): JuggOverlayUpdate {
    if (cacheEntry == null) {
        throw DeployerException.remoteApkNotFound()
    }
    
    // 1. 处理类变更
    val newClasses = (data.newClasses + data.hotFixModifiedClasses).map {
        it.toIncompleteDexClass()
    }
    val modifiedClasses = data.hotReloadModifiedClasses.map {
        it.toIncompleteDexClass()
    }
    val dexOverlays = ChangedClasses(newClasses, modifiedClasses)
    
    // 2. 处理文件 Overlay
    val baseApk = cacheEntry.apks.find { it.name == "base.apk" } ?: cacheEntry.apks.first()
    val cacheEntryMap = cacheEntry.apks.associateBy { it.path }
    val overlayFiles = data.overlays.associate {
        val apk = if (it.apkPath == DeployItem.FLAG_CLASS || it.apkPath == DeployItem.FLAG_BASE_APK) {
            baseApk
        } else {
            cacheEntryMap[it.apkPath] ?: baseApk
        }
        it.toIncompleteOverlay(apk)
    }
    
    return JuggOverlayUpdate(cacheEntry, dexOverlays, overlayFiles)
}
```

**类变更分类**:

| 类型 | 说明 | 部署方式 |
|------|------|---------|
| `newClasses` | 新增的类 | hot_fix (fullSwap) |
| `hotFixModifiedClasses` | 需要重启的修改类 | hot_fix (fullSwap) |
| `hotReloadModifiedClasses` | 可热替换的修改类 | hot_reload (codeSwap) |

---

## 五、部署数据结构

### 5.1 JuggDeployData

```kotlin
data class JuggDeployData(
    val apks: List<ApkInfo>,
    val newClasses: List<DeployItem>,
    val hotReloadModifiedClasses: List<DeployItem>,
    val hotFixModifiedClasses: List<DeployItem>,
    val overlays: List<DeployItem>,
    val isNeedRestartApp: Boolean,
    val isPushOverlayOnly: Boolean,
)
```

**字段说明**:

| 字段 | 类型 | 说明 |
|------|------|------|
| `apks` | List<ApkInfo> | APK 信息列表 |
| `newClasses` | List<DeployItem> | 新增的类 |
| `hotReloadModifiedClasses` | List<DeployItem> | 可热替换的修改类 |
| `hotFixModifiedClasses` | List<DeployItem> | 需要重启的修改类 |
| `overlays` | List<DeployItem> | Overlay 文件列表 |
| `isNeedRestartApp` | Boolean | 是否需要重启应用 |
| `isPushOverlayOnly` | Boolean | 是否仅推送 Overlay |

### 5.2 DeployItem

```kotlin
data class DeployItem(
    val file: File,
    val relativeFile: File,
    val apkPath: String,
) {
    companion object {
        const val FLAG_CLASS = "FLAG_CLASS"
        const val FLAG_BASE_APK = "FLAG_BASE_APK"
    }
}
```

**apkPath 特殊值**:
- `FLAG_CLASS`: 表示 Class 文件
- `FLAG_BASE_APK`: 表示 Base APK

### 5.3 LaunchResult

```kotlin
data class LaunchResult(
    val success: Boolean,
    val errorCode: Int,
    val errorMessage: String?,
    val overlayIds: Map<String, String>,
)
```

---

## 六、设计亮点总结

### 6.1 部署策略

| 亮点 | 说明 |
|------|------|
| **三种部署方式** | install/codeSwap/fullSwap 满足不同场景 |
| **增量安装** | DELTA 模式仅安装变更的 APK |
| **Overlay 机制** | 使用 Overlay 替换资源和代码 |
| **缓存验证** | 验证设备 APK 与本地缓存一致性 |

### 6.2 容错设计

| 容错点 | 说明 |
|--------|------|
| **设备离线重试** | 设备离线时自动重试 |
| **安装模式降级** | DELTA 失败时降级到 FULL |
| **Overlay ID 检查** | 可选的 Overlay ID 检查 |
| **架构自动检测** | 自动检测设备架构 |

### 6.3 性能优化

| 优化点 | 说明 |
|--------|------|
| **增量安装** | 仅安装变更的 APK |
| **代码热替换** | 无需重启应用 |
| **缓存复用** | 复用部署缓存 |
| **并发部署** | 支持多包并发部署 |

---

## 七、部署流程总结

### 7.1 install 流程

```
1. 构建安装选项 (InstallOptions)
2. 确定安装模式 (DELTA/FULL)
3. 执行安装 (AsDeployerCompat.install)
4. 计算 Overlay ID
5. 更新部署缓存
```

### 7.2 codeSwap 流程 (hot_reload)

```
1. 检查 Android 版本 (>= O)
2. 解析 APK 文件
3. 获取应用进程信息 (PID/架构)
4. 加载部署缓存
5. 验证 Overlay ID
6. 验证缓存一致性
7. 构建 Overlay 更新 (仅修改的类)
8. 执行代码热替换 (JVMTI)
9. 更新部署缓存
```

### 7.3 fullSwap 流程 (hot_fix)

```
1. 检查 Android 版本 (>= O)
2. 解析 APK 文件
3. 获取应用进程信息 (PID/架构)
4. 加载部署缓存
5. 验证 Overlay ID
6. 验证缓存一致性
7. 构建 Overlay 更新 (新增类 + 修改类 + 资源)
8. 执行 Overlay 部署
9. 重启 Activity
10. 更新部署缓存
```

---

**文档状态**: ✅ 已完成  
**下一步**: 继续阅读其他部署相关文件，完成部署模块文档
