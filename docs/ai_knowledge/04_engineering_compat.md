# Jugg 技术文档 - 兼容层与辅助模块

---

## 一、模块概述

兼容层和辅助模块是 Jugg 实现跨版本兼容和独立运行的关键组件：

| 模块 | 职责 | 文件数 |
|------|------|--------|
| `deploy_compat/` | Android Studio 版本兼容层 | 15 个 Kotlin 文件 |
| `platform_compat/` | IntelliJ/Android SDK API Mock | 19 个 Java/Kotlin 文件 |
| `cmd_line/` | 命令行独立运行支持 | 14 个 Kotlin 文件 |
| `custom_compilers/` | 自定义编译器示例 | 4 个 Kotlin 文件 |

---

## 二、deploy_compat - Android Studio 版本兼容层

### 2.1 设计目标

Android Studio 从 Chipmunk (2021.2) 到 Panda 跨越了多个大版本，期间 Android Gradle Plugin (AGP) 和 IntelliJ Platform API 发生了大量变化。`deploy_compat` 模块通过**继承链 + Proxy 模式**实现了对多个版本的兼容。

### 2.2 版本支持列表

| Android Studio 版本 | 代码名 | Build Version | 兼容类 |
|---------------------|--------|---------------|--------|
| Otter 2 FD | Otter | 252.27397.103 | `OtterAsDeployerFeatureCompat` |
| Narwhal FD | Narwhal | 251.27812.49 | `NarwhalAsDeployerFeatureCompat` |
| Narwhal | Narwhal | 251.23774.16 | `NarwhalAsDeployerCompat` |
| Meerkat | Meerkat | 243.22562.218 | `MeerkatAsDeployerCompat` |
| Iguana | Iguana | 232.10227.8 | `IguanaAsDeployerCompat` |
| Hedgehog | Hedgehog | 231.9225.16 | `HedgehogAsDeployerCompat` |
| Giraffe | Giraffe | 223.8836.35 | `GiraffeAsDeployerCompat` |
| Chipmunk | Chipmunk | 212.5712.43 | `ChipmunkAsDeployerCompat` |

### 2.3 核心接口 - IAsDeployerCompat

**定义位置**: `deploy_compat/interface/src/main/java/.../IAsDeployerCompat.kt`

```kotlin
interface IAsDeployerCompat {
    // APK 提供者
    fun getApkProvider(project: Project, config: AndroidRunConfiguration): ApkProvider
    
    // 设备管理
    fun getSelectedDevices(project: Project): List<IDevice>?
    fun getConnectedDevices(project: Project): List<IDevice>?
    
    // 安装器
    fun getInstaller(installersFolder: String, adb: AdbClient, logger: ILogger): AdbInstaller
    fun install(adb: AdbClient, service: UIService, installer: Installer, ...): Boolean
    
    // 热修复
    fun makeDebuggerRedefiners(project: Project, device: IDevice, fallback: Boolean): Map<Int, ClassRedefiner>
    fun optimisticSwap(installer: Installer, redefiners: Map<Int, ClassRedefiner>, ...): OverlayId
    
    // 部署状态
    fun getIdeDeployStateResult(project: Project, device: IDevice?, packageName: String?): IdeDeployState
    fun getDeploymentService(project: Project): DeploymentService
    
    // APK 解析
    fun parseApks(paths: List<String>): List<Apk>
    
    // 运行配置
    fun setAllowSelectDevice(runConfiguration: RunConfigurationBase<*>)
    fun getSuggestRunConfigurations(...): List<SuggestRunConfiguration>
    
    // 模块信息
    fun getIdeModuleInfo(project: Project, module: Module, logger: Logger, isSafeMode: Boolean): IdeModuleInfo?
    
    companion object {
        const val ANDROID_11_API = 30
        const val ANDROID_8_API = 26
        var MIN_DEVICE_API = ANDROID_11_API
        
        fun updateMinApi(isEnableCompatDeploy: Boolean) {
            MIN_DEVICE_API = if (isEnableCompatDeploy) ANDROID_8_API else ANDROID_11_API
        }
    }
}
```

### 2.4 继承链设计

```
ChipmunkAsDeployerCompat (基础实现)
    ↓
GiraffeAsDeployerCompat (覆盖 getSelectedDevices, install)
    ↓
HedgehogAsDeployerCompat (覆盖 getDeploymentService, setAllowSelectDevice)
    ↓
IguanaAsDeployerCompat (覆盖 install, parseApks, setAllowSelectDevice)
    ↓
MeerkatAsDeployerCompat (覆盖 getSelectedDevices)
    ↓
NarwhalAsDeployerCompat (覆盖 install)
    ↓
NarwhalAsDeployerFeatureCompat (覆盖 getSuggestRunConfigurations, getIdeModuleInfo)
    ↓
OtterAsDeployerFeatureCompat (覆盖 getIdeModuleInfo)
```

**设计优势**:
- 每个版本只需覆盖变化的方法
- 新版本自动继承旧版本的兼容逻辑
- 降低维护成本

### 2.5 关键 API 变化适配

#### 2.5.1 getSelectedDevices - 设备获取

**Chipmunk ~ Giraffe**:
```kotlin
// 通过 DeployTargetContext 获取设备
val deployTarget = deployTargetContext.currentDeployTargetProvider.getDeployTarget(project)
getModuleManager(project).modules.forEach { module ->
    val facet = AndroidFacet.getInstance(module) ?: return@forEach
    val deviceFutures = deployTarget.getDevices(facet) ?: return@forEach
    val devices = deviceFutures.ifReady
    if (!devices.isNullOrEmpty()) return devices
}
```

**Giraffe+**:
```kotlin
// 直接从 DeployTarget 获取设备
val deployTarget = deployTargetContext.currentDeployTargetProvider.getDeployTarget(project)
val deviceFutures = deployTarget.getDevices(project)
val devices = deviceFutures.ifReady
if (!devices.isNullOrEmpty()) return devices
```

**Meerkat+**:
```kotlin
// 使用 launchDevices 方法
val deployTarget = deployTargetContext.currentDeployTargetProvider.getDeployTarget(project)
val deviceFutures = deployTarget.launchDevices(project)
val devices = deviceFutures.ifReady
if (!devices.isNullOrEmpty()) return devices
```

#### 2.5.2 install - APK 安装

**Chipmunk ~ Giraffe**:
```kotlin
val apkInstaller = ApkInstaller(adb, service, installer, logger)
return apkInstaller.install(packageName, apks, options, installMode, metrics.deployMetrics)
```

**Iguana+**:
```kotlin
val apkInstaller = ApkInstaller(adb, service, installer, logger)
val app = App.fromPaths(packageName, apks.map { Path.of(it) })
return apkInstaller.install(app, options, installMode, metrics.deployMetrics)
```

**Narwhal+**:
```kotlin
val apkInstaller = ApkInstaller(adb, service, installer, logger)
val deployOptions = DeployerOption.Builder().setMaxDeltaInstallPatchSize(0).build()
val app = App.fromPaths(packageName, apks.map { Path.of(it) })
return apkInstaller.install(app, deployOptions, options, installMode, metrics.deployMetrics)
```

**NarwhalFeature+**:
```kotlin
val apkInstaller = ApkInstaller(adb, service, installer, logger)
val deployOptions = DeployerOption.Builder().setMaxDeltaInstallPatchSize(0).build()
val app = App.fromPaths(packageName, apks.map { Path.of(it) })
val deploymentPlan = DeploymentPlan(adb.device, app)
return apkInstaller.install(deploymentPlan, deployOptions, options, installMode, metrics.deployMetrics)
```

#### 2.5.3 parseApks - APK 解析

**Chipmunk ~ Hedgehog**:
```kotlin
return ApkParser().parsePaths(paths)
```

**Iguana+**:
```kotlin
return ApkParser.parsePaths(paths)
```

#### 2.5.4 setAllowSelectDevice - 设备选择

**Chipmunk ~ Giraffe**:
```kotlin
runConfiguration.putUserData(DeviceAndSnapshotComboBoxAction.DEPLOYS_TO_LOCAL_DEVICE, true)
```

**Hedgehog+**:
```kotlin
runConfiguration.putUserData(DeployableToDevice.KEY, true)
```

#### 2.5.5 getDeploymentService - 部署服务

**Chipmunk ~ Giraffe**:
```kotlin
return DeploymentService.getInstance(project)
```

**Hedgehog+**:
```kotlin
return DeploymentService.getInstance()
```

### 2.6 反射兼容处理

#### 2.6.1 findClient - 查找客户端

```kotlin
private fun findClientCompat(device: IDevice, packageName: String): List<Client> {
    return try {
        DeploymentApplicationService.getInstance().findClient(device, packageName)
    } catch (e: IncompatibleClassChangeError) {
        // 方法签名变化，使用反射
        val clazz = Class.forName("com.android.tools.idea.run.DeploymentApplicationService")
        val method = clazz.getDeclaredMethod("getInstance")
        val instance = method.invoke(null)
        val findClientMethod = clazz.getDeclaredMethod("findClient", IDevice::class.java, String::class.java)
        @Suppress("UNCHECKED_CAST")
        findClientMethod.invoke(instance, device, packageName) as List<Client>
    }
}
```

#### 2.6.2 ModuleManager.getInstance - 模块管理器

```kotlin
fun getModuleManager(project: Project): ModuleManager {
    // ModuleManager 在 Giraffe 后改为 Kotlin 实现
    val companionField = try {
        ModuleManager::class.java.getDeclaredField("Companion")
    } catch (e: NoSuchFieldException) {
        null
    }

    return if (companionField == null) {
        // Giraffe 之前
        val getInstanceMethod = ModuleManager::class.java.getDeclaredMethod("getInstance", Project::class.java)
        getInstanceMethod.invoke(null, project) as ModuleManager
    } else {
        // Giraffe 之后
        val companion = companionField.get(null)
        val getInstanceMethod = companion.javaClass.getDeclaredMethod("getInstance", Project::class.java)
        getInstanceMethod.invoke(companion, project) as ModuleManager
    }
}
```

### 2.7 辅助类

#### 2.7.1 GradleVariableHelper - Gradle 变量解析

**定义位置**: `deploy_compat/v_chipmunk/.../GradleVariableHelper.kt`

用于安全读取 Gradle 变量，支持 Safe Mode（捕获异常）：

```kotlin
class GradleVariableHelper(private val isSafeMode: Boolean) {
    val brokenFields = mutableListOf<String>()

    fun readVariable(propertyName: String, model: GradleBuildModel, 
                     propertyGetter: () -> ResolvedPropertyModel, 
                     isValid: String.() -> Boolean): String? {
        try {
            val property = propertyGetter()
            return readVariable(model, property, isValid)
        } catch (e: Throwable) {
            if (!isSafeMode) throw e
            brokenFields.add(propertyName)
            return null
        }
    }

    private fun readVariable(model: GradleBuildModel, property: ResolvedPropertyModel, 
                            isValid: String.() -> Boolean): String? {
        val value = property.valueAsString()?.trim() ?: return null
        return readVariable(value, model, isValid)
    }

    private fun readVariable(value: String, model: GradleBuildModel, 
                            isValid: String.() -> Boolean): String? {
        // 直接验证
        if (value.isValid()) return value

        // 处理 " as " 语法
        var fixedValue = value
        if (fixedValue.contains(" as ")) {
            val index = value.indexOf(" as ")
            fixedValue = value.substring(0, index)
        }

        // 从 declaredProperties 查找
        val declaredProperty = model.declaredProperties.find { it.name == fixedValue }
        declaredProperty?.valueAsString()?.let {
            if (it.isValid()) return it
        }
        return null
    }
}
```

#### 2.7.2 OptimisticApkUpdater - 乐观 APK 更新器

**定义位置**: `deploy_compat/v_chipmunk/.../OptimisticApkUpdater.kt`

用于推送 Overlay 文件到设备（仅推送，不重定义类）：

```kotlin
class OptimisticApkUpdater(
    private val installer: Installer,
    private val redefiners: Map<Int, ClassRedefiner>,
) {
    fun pushOverlays(packageId: String?, pids: List<Int?>, arch: Deploy.Arch?, 
                     overlayUpdate: JuggOverlayUpdate): UpdateResult {
        val cachedDump = overlayUpdate.cachedDump
        val dexOverlays = overlayUpdate.dexOverlays
        val fileOverlays = overlayUpdate.fileOverlays
        val overlayIdBuilder = OverlayId.builder(cachedDump.overlayId)
        val request = Deploy.OverlayInstallRequest.newBuilder()
            .setPackageName(packageId)
            .setArch(arch)
            .setExpectedOverlayId(if (expectedOverlayId.isBaseInstall) "" else expectedOverlayId.sha)

        // 添加新增类
        dexOverlays.newClasses.forEach { clazz ->
            val file = String.format(Locale.US, "%s.dex", clazz.name)
            overlayIdBuilder.addOverlayFile(file, clazz.checksum)
            val overlayFile = OverlayFile.newBuilder()
                .setPath(file)
                .setContent(ByteString.copyFrom(clazz.code))
                .build()
            request.addOverlayFiles(overlayFile)
        }

        // 添加修改类
        dexOverlays.modifiedClasses.forEach { clazz ->
            val file = String.format(Locale.US, "%s.dex", clazz.name)
            overlayIdBuilder.addOverlayFile(file, clazz.checksum)
            val overlayFile = OverlayFile.newBuilder()
                .setPath(file)
                .setContent(ByteString.copyFrom(clazz.code))
                .build()
            request.addOverlayFiles(overlayFile)
        }

        // 添加资源文件
        fileOverlays.entries.forEach { entry ->
            overlayIdBuilder.addOverlayFile(entry.key.qualifiedPath, entry.key.checksum)
            val overlayFile = OverlayFile.newBuilder()
                .setPath(entry.key.qualifiedPath)
                .setContent(entry.value)
                .build()
            request.addOverlayFiles(overlayFile)
        }

        val overlayId = overlayIdBuilder.build()
        request.setOverlayId(overlayId.sha)

        // 使用反射调用 Installer.overlayInstall
        val method = Installer::class.java.getMethod("overlayInstall", Deploy.OverlayInstallRequest::class.java)
        val response = method.invoke(installer, request.build()) as Deploy.OverlayInstallResponse

        if (response.status != Deploy.OverlayInstallResponse.Status.OK) {
            throw IllegalStateException("OptimisticApkUpdater failed, status: ${response.status}")
        }
        return UpdateResult(overlayId, isSuccess = true)
    }
}
```

### 2.8 数据类

#### 2.8.1 IdeDeployState - IDE 部署状态

```kotlin
data class IdeDeployState(
    val state: State,
    val message: String,
) {
    enum class State {
        OK,                        // 正常
        NO_ANDROID_CONFIGURATION,  // 无 Android 配置
        NO_DEPLOYMENT_PROVIDER,    // 无部署提供者
        NO_DEVICE,                 // 无设备
        INVALID_DEVICE,            // 设备无效
        NO_DEPLOYABLE_APP,         // 无可部署应用
        INTERNAL_ERROR,            // 内部错误
    }
}
```

#### 2.8.2 SuggestRunConfiguration - 运行配置建议

```kotlin
data class SuggestRunConfiguration(
    val moduleName: String,
    val compileCommand: String,
    val outputApkPath: String,
    val runConfigName: String = "$RUN_CONFIG_PREFIX$moduleName",
) {
    companion object {
        private const val RUN_CONFIG_PREFIX = "jugg:"
        
        fun getModuleNameByRunConfigName(runConfigName: String): String {
            return runConfigName.substringAfter(RUN_CONFIG_PREFIX)
        }
        
        val DEFAULT: SuggestRunConfiguration
            get() = SuggestRunConfiguration(
                moduleName = "app",
                compileCommand = "./gradlew :app:assembleDebug",
                outputApkPath = "app/build/outputs/apk/debug/*.apk",
                runConfigName = "jugg:default"
            )
    }
}
```

#### 2.8.3 AndroidRunConfig - Android 运行配置

```kotlin
class AndroidRunConfig(
    val moduleName: String,
    val variants: List<Variant>,
    val signingConfigList: List<SigningConfig>,
)

class Variant(
    val name: String,
    val signingConfigName: String?,
)

class SigningConfig(
    val moduleName: String,
    val configName: String,
    val keystore: File?,
    val storePassword: String?,
    val keyAlias: String?,
) {
    val isInvalid: Boolean get() {
        return keystore == null || !keystore.exists() || storePassword == null
    }
}
```

---

## 三、platform_compat - 平台 API Mock

### 3.1 设计目标

`platform_compat` 模块提供了 IntelliJ Platform 和 Android SDK 的最小化 Mock 实现，用于：
1. **命令行模式**: 使 `main` 模块可以在命令行环境独立运行
2. **解耦依赖**: 避免 `main` 模块直接依赖 IntelliJ Platform API
3. **测试支持**: 为单元测试提供轻量级的 Mock 实现

### 3.2 Mock 类列表

#### 3.2.1 IntelliJ Platform API

| Mock 类 | 原始类 | 说明 |
|---------|--------|------|
| `Disposable.kt` | `com.intellij.openapi.Disposable` | 可释放接口 |
| `Disposer.java` | `com.intellij.openapi.util.Disposer` | 释放器 |
| `Project.java` | `com.intellij.openapi.project.Project` | 项目接口 |
| `Logger.java` | `com.intellij.openapi.diagnostic.Logger` | 日志器 |
| `DefaultLogger.java` | `com.intellij.openapi.diagnostic.DefaultLogger` | 默认日志器 |
| `PathManager.java` | `com.intellij.openapi.application.PathManager` | 路径管理器 |
| `PropertiesComponent.java` | `com.intellij.ide.util.PropertiesComponent` | 属性组件 |
| `ProgressIndicator.java` | `com.intellij.openapi.progress.ProgressIndicator` | 进度指示器 |
| `DumbProgressIndicator.java` | `com.intellij.openapi.progress.DumbProgressIndicator` | 哑进度指示器 |
| `Key.java` | `com.intellij.openapi.util.Key` | 键类型 |
| `UrlClassLoader.kt` | `com.intellij.util.lang.UrlClassLoader` | URL 类加载器 |

#### 3.2.2 Android SDK API

| Mock 类 | 原始类 | 说明 |
|---------|--------|------|
| `IDevice.java` | `com.android.ddmlib.IDevice` | 设备接口 |
| `IShellEnabledDevice.java` | `com.android.ddmlib.IShellEnabledDevice` | Shell 设备接口 |
| `ByteString.java` | `com.android.tools.idea.protobuf.ByteString` | 字节字符串 |
| `ZipUtils.java` | `com.android.tools.deployer.ZipUtils` | ZIP 工具 |
| `Apk.java` | `com.android.tools.deployer.model.Apk` | APK 模型 |
| `ApkEntry.java` | `com.android.tools.deployer.model.ApkEntry` | APK 条目 |
| `DexClass.java` | `com.android.tools.deployer.model.DexClass` | DEX 类 |

#### 3.2.3 Log4j API

| Mock 类 | 原始类 | 说明 |
|---------|--------|------|
| `Level.java` | `org.apache.log4j.Level` | 日志级别 |
| `Priority.java` | `org.apache.log4j.Priority` | 日志优先级 |

### 3.3 关键实现

#### 3.3.1 Logger - 日志器

```java
public abstract class Logger {
    public static Logger getInstance(String category) {
        return new Logger() {
            @Override
            public boolean isDebugEnabled() {
                return true;
            }

            @Override
            public void debug(String var1) {
                System.out.println(var1);
            }

            @Override
            public void info(String var1) {
                System.out.println(var1);
            }

            @Override
            public void warn(String var1, Throwable var2) {
                System.out.println(var1);
                if (var2 != null) {
                    var2.printStackTrace();
                }
            }

            @Override
            public void error(String var1, Throwable var2, String... var3) {
                System.out.println(var1);
                if (var2 != null) {
                    var2.printStackTrace();
                }
            }

            @Override
            public void setLevel(Level var1) {
            }
        };
    }
}
```

#### 3.3.2 PathManager - 路径管理器

```java
public class PathManager {
    public static String getSystemPath() {
        String userHome = System.getProperty("user.home");
        return new File(userHome + File.separator + ".jugg").getAbsolutePath();
    }
}
```

#### 3.3.3 PropertiesComponent - 属性组件

```java
public class PropertiesComponent {
    private static final PropertiesComponent instance = new PropertiesComponent();
    private final Map<String, String> valueMap = new HashMap<>();

    public static PropertiesComponent getInstance() {
        return instance;
    }

    public String getValue(String key, String defaultValue) {
        String result = valueMap.get(key);
        if (result != null) {
            return result;
        }
        return defaultValue;
    }

    public void setValue(String key, String value, String defaultValue) {
        if (value == null || value.equals(defaultValue)) {
            valueMap.remove(key);
        } else {
            valueMap.put(key, value);
        }
    }
}
```

#### 3.3.4 Disposer - 释放器

```java
public class Disposer {
    private final static Map<Disposable, Set<Disposable>> childrenMap = new HashMap<>();

    public static void register(Disposable parent, Disposable child) throws Exception {
        synchronized (childrenMap) {
            Set<Disposable> childrenList = childrenMap.computeIfAbsent(parent, k -> new HashSet<>());
            childrenList.add(child);
        }
    }

    public static void dispose(Disposable disposable) {
        synchronized (childrenMap) {
            Set<Disposable> childrenList = childrenMap.remove(disposable);
            if (childrenList != null) {
                for (Disposable child : childrenList) {
                    dispose(child);
                }
            }
            disposable.dispose();
        }
    }
}
```

---

## 四、cmd_line - 命令行支持

### 4.1 设计目标

`cmd_line` 模块提供了独立于 IDE 的命令行工具，支持：
1. **基础构建**: 执行 Gradle 全量编译并备份依赖
2. **增量编译**: 基于基础构建结果进行增量编译

### 4.2 命令列表

| 命令 | 说明 | 主类 |
|------|------|------|
| `buildGradleBase` | 基础构建 | `BuildGradleBaseCommand` |
| `buildIncrementalApk` | 增量编译 | `BuildIncrementalApkCommand` |

### 4.3 命令行入口

**定义位置**: `cmd_line/src/main/java/.../CmdLine.kt`

```kotlin
fun main(args: Array<String>) {
    val result = CmdLine().run(args)
    if (!result) {
        exitProcess(-1)
    }
    exitProcess(0)
}

class CmdLine {
    companion object {
        init {
            PlatformApi.impl = CmdPlatformApi()
        }
    }

    fun run(args: Array<String>): Boolean {
        println("Welcome to Jugg cmdline! args:${args.toList()}")

        val cmd = args.find { it.startsWith("cmd=") }?.substringAfter("cmd=")
        val result = when (cmd) {
            Command.BUILD_INCREMENTAL_APK.value -> {
                CmdLineLogger.stdLogger.info("Going to run cmd: buildIncrementalApk.")
                BuildIncrementalApkCommand.run(args)
            }
            Command.BUILD_GRADLE_BASE.value -> {
                CmdLineLogger.stdLogger.info("Going to run cmd: buildGradleBase.")
                BuildGradleBaseCommand.run(args)
            }
            null -> {
                CmdLineLogger.stdLogger.warn("No cmd specified, exit.")
                false
            }
            else -> {
                CmdLineLogger.stdLogger.warn("unknown cmd:$cmd")
                false
            }
        }
        println("Jugg cmdline exit. result: $result")
        return result
    }

    enum class Command(val value: String) {
        BUILD_INCREMENTAL_APK("buildIncrementalApk"),
        BUILD_GRADLE_BASE("buildGradleBase"),
    }
}
```

### 4.4 基础构建命令

**定义位置**: `cmd_line/src/main/java/.../base/BuildGradleBaseCommand.kt`

#### 4.4.1 参数定义

```kotlin
data class Params(
    val baseBuildProjectDir: File,      // 项目根目录
    val gradleCompileTask: String,      // Gradle 编译任务 (e.g. assembleDebug)
    val gradleOutputApkPath: String,    // APK 输出路径 (支持通配符)
    val outputApkDir: File?,            // APK 输出目录
    val logLevel: Level,                // 日志级别
)
```

#### 4.4.2 执行流程

```
BuildGradleBaseCommand.run()
    ↓
1. prepare()
    └── GradleScriptWriter.writeInitGradleFile()  // 写入 init.gradle
    ↓
2. gradleCompile()
    ├── LocalGradleCompileClient.login()
    ├── LocalGradleCompileClient.compileAndFetchResult()
    └── ApkInfoReader.createApkInfo()
    ↓
3. initAfterGradleCompile()
    ├── LibrariesBackupHelper.backup()            // 备份依赖库
    ├── ClasspathBackupHelper.fetch()             // 备份 classpath
    ├── DeployHistoryManager.reInitAfterFullCompiled()
    ├── DeployDataDatabase.init()
    ├── SourceFileManager.init()
    └── ProjectInfoSerializer.save()
```

#### 4.4.3 依赖备份

**LibrariesBackupHelper**:

```kotlin
class LibrariesBackupHelper(
    private val pathManager: JuggPathManager,
    private val projectInfo: JuggProjectInfo,
    private val logger: Logger,
) {
    fun backup(): JuggProjectInfo {
        val backupDir = pathManager.localClasspathStoragePathManager.librariesBackupDir
        backupDir.deleteRecursively()
        backupDir.mkdirs()
        
        val copiedModules = projectInfo.modules.mapValues { (_, moduleInfo) ->
            moduleInfo.copy(
                libraryDependencies = moduleInfo.libraryDependencies.backup(),
                runtimeLibraryDependencies = moduleInfo.runtimeLibraryDependencies.backup(),
                annotationProcessorDependencies = moduleInfo.annotationProcessorDependencies.backup(),
                kaptDependencies = moduleInfo.kaptDependencies.backup(),
                kotlinPlugins = moduleInfo.kotlinPlugins?.backupFile(),
                kotlinExtensions = moduleInfo.kotlinExtensions?.backupFile(),
                coreLibraryDesugaring = moduleInfo.coreLibraryDesugaring?.backup(),
                kspDependencies = moduleInfo.kspDependencies?.backup(),
            )
        }
        return JuggProjectInfo(copiedModules)
    }

    private fun backup(file: File): File {
        if (file.isChild(pathManager.projectDir)) {
            return file  // 项目内文件不需要备份
        }

        val relativePath = if (file.path.startsWith(File.separator)) {
            file.path.substring(1)
        } else {
            file.path
        }.replace(".gradle", "_gradle")
        
        val destFile = File(pathManager.localClasspathStoragePathManager.librariesBackupDir, relativePath)
        if (destFile.exists()) {
            return destFile
        }

        destFile.parentFile.mkdirs()
        Files.copy(file.toPath(), destFile.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
        return destFile
    }
}
```

### 4.5 增量编译命令

**定义位置**: `cmd_line/src/main/java/.../incremental/BuildIncrementalApkCommand.kt`

#### 4.5.1 参数定义

```kotlin
data class Params(
    val baseBuildJuggRootDir: File,     // 基础构建的 Jugg 根目录
    val sourceProjectDir: File,         // 源码项目目录
    val outputApkDir: File,             // APK 输出目录
    val changedFiles: List<File>,       // 变化的文件列表
    val customCompilerJars: List<File>, // 自定义编译器 JAR
    val logLevel: Level,                // 日志级别
)
```

#### 4.5.2 执行流程

```
BuildIncrementalApkCommand.run()
    ↓
1. checkDirty()
    └── 检查 .dirty 标记文件，防止重复使用
    ↓
2. contextManager.init()
    ├── createCompileContext()
    │   ├── 加载项目信息
    │   ├── 转换路径 (base → source)
    │   └── 创建 BaseCompileContext
    └── fileChangesHandler.init()
    ↓
3. getCompilerHelper()
    ├── JuggCompiler.init()
    └── CustomCompilerManager.init()
    ↓
4. getChangedFiles()
    └── fileChangesHandler.filter()
    ↓
5. compile()
    ├── IncrementalCompilerHelper.compile()
    └── IncrementalCompilerHelper.mergeDex()
    ↓
6. updateApk()
    ├── IncrementalDeployHelper.updateApk()
    └── 复制 APK 到输出目录
```

#### 4.5.3 上下文管理器

**CmdLineContextManager**:

```kotlin
class CmdLineContextManager(
    private val pathManager: JuggPathManager,
    coroutineScope: CoroutineScope,
    private val logger: Logger,
) {
    fun init(): ICompileContext {
        compileContext = createCompileContext()
        return compileContext
    }

    private fun createCompileContext(): ICompileContext {
        val androidHome = File(System.getenv("ANDROID_HOME")
            ?: throw IncrementalException("Environment variable ANDROID_HOME is not set."))

        val compileContextDb = CompileContextDb(...)
        val compileContextInfo = compileContextDb.getCompileBuildPathInfoFromDb()
            ?: throw IncrementalException("Can't get compile history.")

        val historyProjectDir = deployHistoryManager.historyProjectDir
            ?: throw IncrementalException("Can't found history project dir.")

        // 路径转换函数
        fun File.convertSourceBaseDir(): File {
            if (!this.isChild(historyProjectDir)) return this
            return changeBaseDir(historyProjectDir, pathManager.projectDir)
        }

        fun File.convertBuildBaseDir(): File {
            if (!this.isChild(historyJuggRootDir)) return this
            return changeBaseDir(historyJuggRootDir, pathManager.juggRootDir)
        }

        // 转换模块信息
        val modules = getProjectInfo().modules.mapValues { (_, baseModule) ->
            baseModule.copy(
                moduleRootDir = baseModule.moduleRootDir.convertSourceBaseDir(),
                projectRootDir = baseModule.projectRootDir.convertSourceBaseDir(),
                sourceDirs = baseModule.sourceDirs.convertSourceBaseDir(),
                resourceDirs = baseModule.resourceDirs.convertSourceBaseDir(),
                buildPathInfo = baseModule.buildPathInfo.copy(
                    projectRootDir = baseModule.buildPathInfo.projectRootDir.convertBuildBaseDir(),
                    moduleRootDir = baseModule.buildPathInfo.moduleRootDir.convertBuildBaseDir(),
                ),
                // ...
            )
        }

        return BaseCompileContext(
            logger = logger,
            androidHome = androidHome,
            modules = modules,
            projectDir = pathManager.projectDir,
            apkInfos = compileContextInfo.apkInfos,
            scene = ICompileContext.Scene.INCREMENTAL_APK,
            // ...
        )
    }
}
```

### 4.6 命令行日志器

**定义位置**: `cmd_line/src/main/java/.../logger/CmdLineLogger.kt`

```kotlin
object CmdLineLogger {
    val stdLogger = object : DefaultLogger("cmd") {
        var logLevel = Level.DEBUG

        override fun debug(message: String?) {
            if (Level.DEBUG.isGreaterOrEqual(logLevel)) {
                println("[D] $message")
            }
        }

        override fun info(message: String?) {
            if (Level.INFO.isGreaterOrEqual(logLevel)) {
                println("$message")
            }
        }

        override fun warn(message: String?, t: Throwable?) {
            if (Level.WARN.isGreaterOrEqual(logLevel)) {
                System.err.println("[W] $message")
                t?.printStackTrace(System.err)
            }
        }

        override fun error(message: String?, t: Throwable?, vararg details: String?) {
            if (Level.ERROR.isGreaterOrEqual(logLevel)) {
                System.err.println("[E] $message")
                t?.printStackTrace(System.err)
            }
        }
    }

    fun init(name: String, logDir: File, level: Level): Logger {
        stdLogger.setLevel(level)
        FileLogger.isCreateLastLogLinkFile = false

        JuggLogger.register(name, logDir)
        JuggLogger.listenProjectLog(name, stdLogger)
        return JuggLogger.getInstance(name, name)
    }

    fun release(name: String) {
        JuggLogger.unregister(name)
    }
}
```

### 4.7 命令行平台 API

**定义位置**: `cmd_line/src/main/java/.../CmdPlatformApi.kt`

```kotlin
class CmdPlatformApi : IPlatformApi {
    override fun allAvailableJavaHomes(): List<String> {
        val javaHomeProp = System.getProperty("java.home")
        val javaHomeEnv = System.getenv("JAVA_HOME")
        val result = mutableListOf<String>()
        if (javaHomeProp != null) {
            result.add(javaHomeProp)
        }
        if (javaHomeEnv != null) {
            result.add(javaHomeEnv)
        }
        return result.distinct()
    }

    override fun getGradleJdkPath(project: Project, logger: Logger): String? {
        return allAvailableJavaHomes().firstOrNull()
    }

    // 其他方法抛出 "Cmd line not support" 异常
}
```

---

## 五、custom_compilers - 自定义编译器示例

### 5.1 设计目标

`custom_compilers` 模块提供了自定义编译器的示例实现，展示了如何扩展 Jugg 的编译流程。

### 5.2 自定义编译器接口

**定义位置**: `main/src/main/java/.../compiler/custom/ICompilerCreator.kt`

```kotlin
interface ICompilerCreator {
    fun create(context: ICompileContext, parent: Disposable): ICompiler
}
```

### 5.3 示例 1: ExampleAssembleCustomCompiler

**功能**: 检测到源码变化时，自动执行 `generateDebugSources` 任务

```kotlin
class ExampleAssembleCustomCompiler(context: ICompileContext, parent: Disposable) 
    : BaseCompiler(context, parent) {

    @AutoService(ICompilerCreator::class)
    class Creator : ICompilerCreator {
        override fun create(context: ICompileContext, parent: Disposable): ICompiler {
            return ExampleAssembleCustomCompiler(context, parent)
        }
    }

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        if (context.projectDir.name != "MyApplicationIntellij") {
            return CompileResult(task, emptyList(), emptyList())
        }

        val sourceFiles = task.files.filter { 
            it.type == CompileFile.Type.Java || it.type == CompileFile.Type.Kotlin 
        }
        if (sourceFiles.isEmpty()) {
            return CompileResult(task, emptyList(), emptyList())
        }

        logger.info("Detect source file change, start generateDebugSources...")
        val cmd = SimpleSshCommand(
            "cd ${context.projectDir} && ./gradlew :app:generateDebugSources", 
            logger, 
            outputFilter = { _, isError -> isError }
        )
        val result = CmdExecutor(logger).invoke(cmd, context.cmdCompileEnv)
        if (result == 0) {
            logger.info("Generate debug source success.")
        } else {
            logger.warn("Generate debug source failed. See log for more details.")
        }
        
        return CompileResult(task, sourceFiles.map { 
            Result.failure(CompileError(it, listOf(-1L to "assemble failed"))) 
        }, emptyList())
    }

    override fun dispose() {
    }
}
```

### 5.4 示例 2: ExampleHookInitCustomCompiler

**功能**: 使用 ASM 在所有类的构造函数中注入 `System.out.println` 调用

```kotlin
class ExampleHookInitCustomCompiler(private val context: ICompileContext) : ICompiler {

    @AutoService(ICompilerCreator::class)
    class Creator : ICompilerCreator {
        override fun create(context: ICompileContext, parent: Disposable): ICompiler {
            return ExampleHookInitCustomCompiler(context)
        }
    }

    override val supportedTypes: List<CompileFile.Type> = CompileFile.Type.entries
    override val order: Int = CompileOrder.afterSource.first

    override fun compile(task: CompileTask): CompileResult {
        task.files.forEach { file ->
            if (file.type != CompileFile.Type.Class) {
                return@forEach
            }
            instrument(file.file.absolutePath)
        }
        return CompileResult(task, emptyList(), emptyList())
    }

    fun instrument(classPath: String) {
        var fileInputStream: FileInputStream? = null
        var fileOutputStream: FileOutputStream? = null
        try {
            val file = File(classPath)
            fileInputStream = FileInputStream(file)
            val data = instrument(FileInputStream(file))
            fileInputStream.close()
            fileOutputStream = FileOutputStream(file)
            fileOutputStream.write(data, 0, data.size)
            fileOutputStream.close()
            println("compile ok :$classPath")
        } finally {
            fileInputStream?.close()
            fileOutputStream?.close()
        }
    }

    private fun instrument(input: InputStream): ByteArray {
        val classReader = ClassReader(input)
        val classWriter = ClassWriter(classReader, ClassWriter.COMPUTE_MAXS)
        val classVisitor = InitInstrumentClassVisitor(classWriter)

        classReader.accept(classVisitor, ClassReader.EXPAND_FRAMES)
        return classWriter.toByteArray()
    }

    private class InitInstrumentClassVisitor(classWriter: ClassWriter) 
        : ClassVisitor(Opcodes.ASM9, classWriter) {

        private var className: String? = null

        override fun visit(version: Int, access: Int, name: String, 
                          signature: String?, superName: String?, 
                          interfaces: Array<out String>?) {
            className = name.replace('/', '.')
            super.visit(version, access, name, signature, superName, interfaces)
        }

        override fun visitMethod(access: Int, name: String, descriptor: String, 
                                signature: String?, exceptions: Array<out String>?): MethodVisitor? {
            val methodVisitor = super.visitMethod(access, name, descriptor, signature, exceptions)

            // 只注入 <init> 方法
            if ("<init>" == name) {
                return InitMethodVisitor(methodVisitor, className ?: "")
            }

            return methodVisitor
        }
    }

    private class InitMethodVisitor(methodVisitor: MethodVisitor, private val className: String) 
        : MethodVisitor(Opcodes.ASM9, methodVisitor) {

        override fun visitInsn(opcode: Int) {
            // 在每个 RETURN 指令前注入 println 调用
            if (opcode == Opcodes.RETURN) {
                injectPrintlnCall()
            }
            super.visitInsn(opcode)
        }

        private fun injectPrintlnCall() {
            // 调用 WhatShouldIToast().message() 获取消息
            val whatShouldIToast = Class.forName("com.sickworm.intellij.jugg.compiler.demo.WhatShouldIToast")
                .newInstance() as Any
            val messageMethod = whatShouldIToast.javaClass.getMethod("message")
            val message = messageMethod.invoke(whatShouldIToast) as String

            // 注入消息
            val fullMessage = "$message [from class: $className]"

            mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;")
            mv.visitLdcInsn(fullMessage)
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", 
                              "(Ljava/lang/String;)V", false)
        }
    }

    override fun dispose() {
        context.logger.debug("[ExampleHookInitCustomCompiler] I'm disposed!")
    }
}
```

### 5.5 示例 3: ExampleDelayCustomCompiler

**功能**: 延迟 1 秒，用于测试编译流程

```kotlin
class ExampleDelayCustomCompiler(private val context: ICompileContext) : ICompiler {

    @AutoService(ICompilerCreator::class)
    class Creator : ICompilerCreator {
        override fun create(context: ICompileContext, parent: Disposable): ICompiler {
            return ExampleDelayCustomCompiler(context)
        }
    }

    override val supportedTypes: List<CompileFile.Type> = CompileFile.Type.entries

    override fun compile(task: CompileTask): CompileResult {
        if (context.projectDir.name != "MyApplicationIntellij") {
            return CompileResult(task, emptyList(), emptyList())
        }
        context.logger.info("[ExampleDelayCustomCompiler] I'm in!")
        Thread.sleep(1000)
        context.logger.info("[ExampleDelayCustomCompiler] I'm done!")
        return CompileResult(task, emptyList(), emptyList())
    }

    override fun dispose() {
        context.logger.debug("[ExampleDelayCustomCompiler] I'm disposed!")
    }
}
```

### 5.6 自定义编译器加载

**定义位置**: `main/src/main/java/.../compiler/custom/CustomCompilerManager.kt`

```kotlin
class CustomCompilerManager(
    private val projectDir: File,
    private val customCompilerDir: File,
    private val juggServer: JuggServer,
    private val logger: Logger,
) {
    fun getCustomCompilers(context: ICompileContext, parent: Disposable): List<ICompiler> {
        val customCompilerJars = getCustomCompilerJars()
        if (customCompilerJars.isEmpty()) {
            return emptyList()
        }

        val classLoader = URLClassLoader(
            customCompilerJars.map { it.toURI().toURL() }.toTypedArray(),
            this.javaClass.classLoader
        )

        val serviceLoader = ServiceLoader.load(ICompilerCreator::class.java, classLoader)
        return serviceLoader.map { it.create(context, parent) }
    }

    private fun getCustomCompilerJars(): List<File> {
        if (!customCompilerDir.exists()) {
            return emptyList()
        }

        return customCompilerDir.listFiles()
            ?.filter { it.extension == "jar" }
            ?: emptyList()
    }
}
```

---

## 六、总结

### 6.1 关键技术点

1. **版本兼容**: 通过继承链 + Proxy 模式实现多版本 Android Studio 兼容
2. **反射兼容**: 使用反射处理 API 签名变化
3. **平台 Mock**: 提供最小化 Mock 实现，支持命令行模式
4. **命令行工具**: 独立于 IDE 的命令行工具，支持 CI/CD 集成
5. **自定义编译器**: 通过 SPI 机制支持插件化扩展

### 6.2 模块依赖

```
idea (IDE 插件层)
  ↓
main (核心逻辑层)
  ↓
deploy_compat (版本兼容层)
  ↓
platform_compat (平台 Mock 层)

cmd_line (命令行工具)
  ↓
main (核心逻辑层)
  ↓
platform_compat (平台 Mock 层)

custom_compilers (自定义编译器)
  ↓
main (核心逻辑层)
```

### 6.3 扩展点

- **新版本兼容**: 继承最新版本的 `*AsDeployerCompat`，覆盖变化的方法
- **自定义编译器**: 实现 `ICompilerCreator` 接口，使用 `@AutoService` 注解
- **命令行命令**: 在 `CmdLine.Command` 枚举中添加新命令

---

## 附录：文件清单

### deploy_compat

| 文件 | 说明 |
|------|------|
| `IAsDeployerCompat.kt` | 兼容层接口 |
| `SuggestRunConfiguration.kt` | 运行配置建议 |
| `JuggOverlayUpdate.kt` | Overlay 更新数据 |
| `IdeDeployState.kt` | IDE 部署状态 |
| `AndroidRunConfig.kt` | Android 运行配置 |
| `ChipmunkAsDeployerCompat.kt` | Chipmunk 版本兼容 |
| `GradleVariableHelper.kt` | Gradle 变量解析助手 |
| `OptimisticApkUpdater.kt` | 乐观 APK 更新器 |
| `GiraffeAsDeployerCompat.kt` | Giraffe 版本兼容 |
| `HedgehogAsDeployerCompat.kt` | Hedgehog 版本兼容 |
| `IguanaAsDeployerCompat.kt` | Iguana 版本兼容 |
| `MeerkatAsDeployerCompat.kt` | Meerkat 版本兼容 |
| `NarwhalAsDeployerCompat.kt` | Narwhal 版本兼容 |
| `NarwhalAsDeployerFeatureCompat.kt` | Narwhal FD 版本兼容 |
| `OtterAsDeployerFeatureCompat.kt` | Otter 2 FD 版本兼容 |

### platform_compat

| 文件 | 说明 |
|------|------|
| `Disposable.kt` | 可释放接口 |
| `UrlClassLoader.kt` | URL 类加载器 |
| `PathManager.java` | 路径管理器 |
| `Project.java` | 项目接口 |
| `DefaultLogger.java` | 默认日志器 |
| `Logger.java` | 日志器抽象 |
| `Key.java` | 键类型 |
| `Disposer.java` | 释放器 |
| `DumbProgressIndicator.java` | 哑进度指示器 |
| `ProgressIndicator.java` | 进度指示器接口 |
| `PropertiesComponent.java` | 属性组件 |
| `IDevice.java` | 设备接口 |
| `IShellEnabledDevice.java` | Shell 设备接口 |
| `ByteString.java` | 字节字符串 |
| `ZipUtils.java` | ZIP 工具 |
| `DexClass.java` | DEX 类 |
| `Apk.java` | APK 模型 |
| `ApkEntry.java` | APK 条目 |
| `Priority.java` | 日志优先级 |
| `Level.java` | 日志级别 |

### cmd_line

| 文件 | 说明 |
|------|------|
| `CmdLine.kt` | 命令行入口 |
| `CmdPlatformApi.kt` | 命令行平台 API |
| `base/ParamsParser.kt` | 基础构建参数解析器 |
| `base/Params.kt` | 基础构建参数 |
| `base/BuildGradleBaseCommand.kt` | 基础构建命令 |
| `base/BaseBuildException.kt` | 基础构建异常 |
| `base/LibrariesBackupHelper.kt` | 库备份助手 |
| `incremental/ParamsParser.kt` | 增量编译参数解析器 |
| `incremental/Params.kt` | 增量编译参数 |
| `incremental/BuildIncrementalApkCommand.kt` | 增量编译命令 |
| `incremental/CmdLineContextManager.kt` | 命令行上下文管理器 |
| `incremental/CompileRangeOptions.kt` | 编译范围选项 |
| `incremental/IncrementalException.kt` | 增量编译异常 |
| `logger/CmdLineLogger.kt` | 命令行日志器 |

### custom_compilers

| 文件 | 说明 |
|------|------|
| `WhatShouldIToast.kt` | Toast 消息示例 |
| `ExampleAssembleCustomCompiler.kt` | Assemble 自定义编译器 |
| `ExampleHookInitCustomCompiler.kt` | Hook Init 自定义编译器 |
| `ExampleDelayCustomCompiler.kt` | 延迟自定义编译器 |
