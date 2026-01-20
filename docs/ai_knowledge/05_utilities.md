# Jugg 技术文档 - 辅助模块

> 创建时间: 2025-01-20
> 模块: `main/apk/`, `main/aapt2/`, `main/git/`, `main/logger/`, `main/rpc/`, `main/server/`, `main/platform/`
> 文件数: ~25 个

---

## 一、模块概述

辅助模块提供了 Jugg 运行所需的基础设施和工具类，包括：

| 模块 | 职责 | 文件数 |
|------|------|--------|
| `apk/` | APK 文件读写、修改、签名 | 7 个 |
| `aapt2/` | AAPT2 工具调用 | 3 个 |
| `git/` | Git 集成和文件变化检测 | 6 个 |
| `logger/` | 日志系统 | 5 个 |
| `rpc/` | RPC 通信 | 2 个 |
| `server/` | 服务器和远程编译 | 4 个 |
| `platform/` | 平台抽象层 | 2 个 |

---

## 二、apk - APK 文件操作

### 2.1 核心数据结构

#### 2.1.1 ApkInfo - APK 信息

**定义位置**: `apk/ApkInfo.kt`

```kotlin
data class ApkInfo(
    val files: List<ApkFileUnit>,
    val applicationId: String
) {
    val baseApk: ApkFileUnit? get() = files.find { it.isBaseApk }
}

data class ApkFileUnit(
    val applicationId: String, 
    val moduleName: String, 
    val apkFile: File
) {
    val isBaseApk get() = moduleName.isEmpty()
    val isFeatureApk get() = moduleName.isNotEmpty()
    val resourcePackage get() = if (isBaseApk) applicationId else "$applicationId.$moduleName"
    
    fun getUniquePath(basePath: String): String {
        return if (isBaseApk) {
            basePath
        } else {
            "${basePath}_${getUniqueKey(apkFile.path)}"
        }
    }
    
    companion object {
        fun getUniqueKey(apkPath: String): String {
            return File(apkPath).name + "_" + apkPath.md5.substring(0, 8)
        }
    }
}
```

**设计亮点**:
- 支持 **Dynamic Feature Module**（动态特性模块）
- 通过 `moduleName` 区分 Base APK 和 Feature APK
- `resourcePackage` 自动处理 Feature APK 的包名（`applicationId.moduleName`）

### 2.2 APK 读取

#### 2.2.1 ApkReader - APK 读取器

**定义位置**: `apk/ApkReader.kt`

```kotlin
class ApkReader(
    private val apkFile: File,
    private val logger: Logger
) {
    fun getPackageName(): String {
        return getManifest().packageName()
    }

    fun getManifest(): ManifestActivityInfo {
        ZipFile(apkFile).use { zipApkFile ->
            val androidManifestEntry = zipApkFile.getEntry("AndroidManifest.xml")
            val androidManifestInput = zipApkFile.getInputStream(androidManifestEntry)
            val manifest = BinaryXmlParser.parseBinaryFromStream(androidManifestInput)
            return manifest
        }
    }

    fun getDefaultActivity(): String? {
        return DefaultApkActivityLocator(logger).computeDefaultActivityFromApks(getManifest())
    }

    fun parse(): ApkResInfo? {
        val aapt2Invoker = Aapt2DaemonInvoker(logger)
        val result = aapt2Invoker.invoke("dump resources ${apkFile.absolutePath}")
        if (!result.isSuccess) {
            logger.warn(result.errorOutput)
            return null
        }
        return doParse(result.output)
    }
}
```

**功能**:
1. **读取包名**: 从二进制 AndroidManifest.xml 解析包名
2. **读取 Manifest**: 使用 `BinaryXmlParser` 解析二进制 XML
3. **查找默认 Activity**: 查找带有 `MAIN` action 和 `LAUNCHER` category 的 Activity
4. **解析资源**: 使用 AAPT2 解析 APK 中的资源信息

#### 2.2.2 ApkInfoReader - APK 信息读取器

**定义位置**: `apk/ApkInfoReader.kt`

```kotlin
class ApkInfoReader(private val logger: Logger) {
    fun getArch(apks: List<Apk>): String {
        var is32Bit = true
        apks.forEach {
            val has64Bit = it.apkEntries.any { (name, _) -> name.startsWith("lib/arm64-v8a") }
            val has32Bit = it.apkEntries.any { (name, _) -> name.startsWith("lib/armeabi-v7a") }
            if (is32Bit) {
                is32Bit = !has64Bit && has32Bit
            }
        }
        return if (is32Bit) "ARCH_32_BIT" else "ARCH_64_BIT"
    }

    fun createApkInfo(apks: List<File>): List<ApkInfo> {
        val apkFileUnits = mutableListOf<ApkFileUnit>()
        apks.forEach { apkFile ->
            val manifestInfo = ApkReader(apkFile, logger).getManifest()
            val apkFileUnit = ApkFileUnit(
                applicationId = manifestInfo.packageName(),
                moduleName = manifestInfo.featureSplit() ?: "",
                apkFile,
            )
            apkFileUnits.add(apkFileUnit)
        }
        return apkFileUnits
            .groupBy { it.applicationId }
            .map { ApkInfo(it.value, it.key) }
            .sortedBy { apks.indexOf(it.files.first().apkFile) }
    }
}
```

**功能**:
1. **架构检测**: 自动检测 APK 是 32 位还是 64 位
2. **批量解析**: 支持解析多个 APK（Base + Feature）
3. **自动分组**: 按 `applicationId` 分组，支持多 App 场景

### 2.3 APK 修改

#### 2.3.1 ApkFileModifier - APK 文件修改器

**定义位置**: `apk/ApkFileModifier.kt`

```kotlin
class ApkFileModifier(
    private val apkFile: File,
    private val signConfig: SigningConfig,
    private val androidHome: File,
    private val logger: Logger,
    private val envArray: List<String>? = null,
) {
    private val insertFiles = mutableListOf<Pair<String, ByteArray>>()

    fun addFile(path: String, content: ByteArray): ApkFileModifier {
        insertFiles.add(path to content)
        return this
    }

    fun insertAndResign() {
        var tmpApkFile = updateFiles(apkFile)
        tmpApkFile = alignApk(tmpApkFile)
        tmpApkFile = resignApk(tmpApkFile)
        replaceOldApk(tmpApkFile, apkFile)
    }

    fun updateDirectly() {
        val tmpApkFile = updateFiles(apkFile)
        replaceOldApk(tmpApkFile, apkFile)
    }
}
```

**核心流程**:

```
insertAndResign()
    ↓
1. updateFiles()
    ├── insertFileJvm14() (JDK 14+, 使用 FileSystems API, 1-2s)
    └── insertFileUnderJvm14() (JDK < 14, 使用标准 ZIP API, 10-60s)
    ↓
2. alignApk()
    └── zipalign -f 4 input.apk output.apk
    ↓
3. resignApk()
    └── apksigner sign --ks keystore.jks input.apk
    ↓
4. replaceOldApk()
    └── 替换原 APK
```

**性能优化**:

```kotlin
private fun updateFiles(apkFile: File): File {
    val jvmVersion = Runtime.version().version()
    val tmpApkFile = if (jvmVersion[0] >= 14) {
        insertFileJvm14(apkFile)  // 使用 FileSystems API，快 90%
    } else {
        insertFileUnderJvm14(apkFile)  // 使用标准 ZIP API
    }
    return tmpApkFile
}

private fun insertFileJvm14(apkFileToUpdate: File): File {
    val zipProperties = mapOf("create" to "false", "compressionMethod" to "STORED")
    val zipDisk: URI = URI.create("jar:" + apkFileToUpdate.toURI().toString())
    FileSystems.newFileSystem(zipDisk, zipProperties).use { zipFileSystem ->
        insertFiles.forEach { (path, content) ->
            val pathInZipFile: Path = zipFileSystem.getPath(path)
            if (pathInZipFile.exists()) {
                Files.delete(pathInZipFile)
            }
            if (pathInZipFile.parent != null && !pathInZipFile.parent.exists()) {
                Files.createDirectories(pathInZipFile.parent)
            }
            Files.copy(content.inputStream(), pathInZipFile)
        }
    }
    return apkFileToUpdate
}
```

**设计亮点**:
- **JDK 版本适配**: JDK 14+ 使用 FileSystems API，性能提升 90%
- **自动重签名**: 支持自动查找可用的 JDK 进行签名
- **链式调用**: 支持 `addFile().addFile().insertAndResign()`

#### 2.3.2 ResourceApkModifier - 资源 APK 修改器

**定义位置**: `apk/ResourceApkModifier.kt`

```kotlin
class ResourceApkModifier(
    private val originApkPath: String,
    private val resourceApkFile: File,
    logger: Logger,
) {
    fun createResourceApk(overlays: List<DeployItem>) {
        resourceApkFile.delete()
        resourceApkFile.parentFile.mkdirs()
        resourceApkFile.createNewFile()
        ZipOutputStream(resourceApkFile.outputStream()).use { os ->
            val overlay = overlays.first()
            val entry = ZipEntry(overlay.name)
            os.putNextEntry(entry)
            os.write(overlay.content)
            os.closeEntry()
        }
        if (overlays.size > 1) {
            incrementalUpdateResourceApk(overlays.subList(1, overlays.size))
        }
    }

    fun incrementalUpdateResourceApk(overlays: List<DeployItem>) {
        val apkFileModifier = ApkFileModifier(resourceApkFile, SigningConfig.EMPTY, File(""), logger)
        overlays.forEach { overlay ->
            apkFileModifier.addFile(overlay.name, overlay.content)
        }
        apkFileModifier.updateDirectly()
    }

    fun toDeployItems(): List<DeployItem> {
        val content = resourceApkFile.readBytes()
        val crc32 = CRC32().let {
            it.update(content)
            it.value
        }
        val deployItem = DeployItem(
            BuildConfig.RESOURCE_APK_NAME,
            CompileOutput.Type.Asset,
            crc32,
            content,
            originApkPath,
        )
        return listOf(deployItem)
    }
}
```

**用途**: 创建一个独立的资源 APK，用于存储增量资源文件，避免修改原 APK。

### 2.4 辅助工具

#### 2.4.1 DefaultApkActivityLocator - 默认 Activity 定位器

**定义位置**: `apk/DefaultApkActivityLocator.kt`

从 Android Plugin 复制的逻辑，用于查找默认启动 Activity：

```kotlin
class DefaultApkActivityLocator(val logger: Logger) {
    fun computeDefaultActivityFromApks(manifest: ManifestActivityInfo): String? {
        val activities = manifest.activities()
        val defaultActivityName = computeDefaultActivity(activities)
        return defaultActivityName
    }

    private fun computeDefaultActivity(activities: List<NodeActivity>): String? {
        val launchableActivities = getLaunchableActivities(activities)
        if (launchableActivities.isEmpty()) {
            return null
        } else if (launchableActivities.size == 1) {
            return launchableActivities[0].realActivityQname
        }

        // 优先选择带有 CATEGORY_DEFAULT 的 Launcher
        val defaultLauncher = findDefaultLauncher(launchableActivities)
        return defaultLauncher?.realActivityQname ?: launchableActivities[0].realActivityQname
    }

    private fun containsLauncherIntent(activity: NodeActivity): Boolean {
        return activity.hasAction("android.intent.action.MAIN") &&
                (activity.hasCategory("android.intent.category.LAUNCHER") ||
                 activity.hasCategory("android.intent.category.LEANBACK_LAUNCHER"))
    }
}
```

#### 2.4.2 BuildToolsVersionComparator - Build Tools 版本比较器

**定义位置**: `apk/BuildToolsVersionComparator.kt`

```kotlin
class BuildToolsVersionComparator(
    private val versionString: String,
): Comparable<BuildToolsVersionComparator> {
    override fun compareTo(other: BuildToolsVersionComparator): Int {
        val splitRegex = Regex("\\.|_rc|-rc")
        val myVersion = this.versionString.substringAfter("android-")
        val myVersions = myVersion.split(splitRegex)

        val otherVersion = other.versionString.substringAfter("android-")
        val otherVersions = otherVersion.split(splitRegex)

        myVersions.forEachIndexed { index, subVersion ->
            val myVersionInt = subVersion.toIntOrNull() ?: -1
            val otherVersionInt = otherVersions.getOrNull(index)?.toIntOrNull() ?: -1
            if (myVersionInt != otherVersionInt) {
                return myVersionInt - otherVersionInt
            }
        }

        return myVersions.size - otherVersions.size
    }
}
```

**用途**: 比较 Build Tools 版本（如 `33.0.0` vs `33.0.1-rc1`），用于选择最新版本的 `zipalign` 和 `apksigner`。

---

## 三、aapt2 - AAPT2 工具调用

### 3.1 核心类

#### 3.1.1 Aapt2DaemonInvoker - AAPT2 守护进程调用器

**定义位置**: `aapt2/Aapt2DaemonInvoker.kt`

```kotlin
class Aapt2DaemonInvoker(
    parentLogger: Logger,
    private val aapt2: File = getEmbeddedAapt2(),
) {
    private var process: Process? = null
    private var outputReader: OutputReader? = null

    @Synchronized
    private fun init() {
        val process = Runtime.getRuntime().exec("$aapt2 daemon")
        val output = readLine(process.inputStream)
        if (output != "Ready") {
            throw JuggInternalException.startAapt2DaemonFailed()
        }
        this.process = process
        outputReader = OutputReader(process.inputStream, process.errorStream, logger)
        outputReader?.init()
    }

    @Synchronized
    fun invoke(params: String): Aapt2Result {
        if (process?.isAlive != true) {
            init()
        }
        process.outputStream.write("${params.replace(" ", "\n")}\n\n".toByteArray())
        process.outputStream.flush()
        return outputReader?.read() ?: Aapt2Result("", "")
    }

    @Synchronized
    fun release() {
        process?.destroy()
        outputReader?.release()
    }

    companion object {
        fun getEmbeddedAapt2(): File {
            val version = "2.19.14"
            return if (isMac) {
                copyResource("/tools/darwin/aapt2-inclink-$version")
            } else if (isLinux) {
                copyResource("/tools/linux/aapt2-inclink-$version")
            } else if (isWindows) {
                copyResource("/tools/windows/aapt2-inclink-$version.exe")
            } else {
                throw JuggException.unsupportedOs()
            }
        }
    }
}
```

**设计亮点**:
- **守护进程模式**: 启动一次 AAPT2 守护进程，多次调用，避免重复启动开销
- **跨平台支持**: 内嵌了 macOS、Linux、Windows 三个平台的 AAPT2 二进制文件
- **自定义版本**: 使用 `aapt2-inclink` 自定义构建版本，支持增量链接

### 3.2 数据结构

#### 3.2.1 ApkResInfo - APK 资源信息

**定义位置**: `aapt2/ApkResInfo.kt`

```kotlin
data class ApkResInfo(
    val packageName: String,
    val id: Int,
    val groupList: List<ResGroup>
)

data class ResGroup(
    val type: String,       // e.g. "drawable", "layout"
    val id: Int,            // e.g. 0x7f02
    val entryCount: Int,
    val itemList: List<ResId>
)

data class ResId(
    val type: String,       // e.g. "resource"
    val id: Int,            // e.g. 0x7f020001
    val name: String,       // e.g. "ic_launcher"
    val resList: List<ResItem>
)

data class ResItem(
    val prefix: String,
    val type: String,
    val filePath: String,
    val fileType: String,
)
```

**用途**: 存储 `aapt2 dump resources` 的解析结果，用于资源分析和增量编译。

#### 3.2.2 Aapt2Result - AAPT2 调用结果

**定义位置**: `aapt2/Aapt2Result.kt`

```kotlin
class Aapt2Result(
    val output: String,
    val errorOutput: String,
) {
    val isSuccess: Boolean get() = !errorOutput.contains("error: ")
}
```

---

## 四、git - Git 集成

### 4.1 核心接口

#### 4.1.1 IGitManager - Git 管理器接口

**定义位置**: `git/IGitManager.kt`

```kotlin
interface IGitManager {
    val rootDir: File
    val hasInitGit: Boolean
    val name: String?
    val userName: String?

    fun getUncommittedFiles(): List<File>
    fun getChangedFiles(oldCommit: String, newCommit: String): List<File>
    fun getLastCommitHash(): String?
    fun filterChangedFiles(commitHash: String, files: List<File>): List<File>
    fun getLastCommitFileContent(commitId: String, file: File, outputFile: File): Boolean
}

interface IGitManagerEx : IGitManager {
    fun init()
    fun deleteGit()
    fun addAllAndCommit(message: String)
    fun getCurrentBranchCommitSize(): Int
}
```

### 4.2 实现类

#### 4.2.1 GitManager - Git 管理器

**定义位置**: `git/GitManager.kt`

```kotlin
class GitManager(override val rootDir: File): IGitManagerEx {
    private val targetGitDir: File get() = getGitDir(rootDir)
    private val isWorkTree: Boolean get() = File(targetGitDir, "commondir").exists()

    private val gitDir: File get() = if (isWorkTree) {
        File(targetGitDir, File(targetGitDir, "commondir").readText().trim())
    } else {
        targetGitDir
    }

    private val repository: Repository? get() {
        return try {
            if (isWorkTree) {
                WorktreeRepositoryBuilder()
                    .setGitDir(gitDir)
                    .setWorktreeGitDir(targetGitDir)
                    .setWorkTree(rootDir)
                    .setMustExist(true)
                    .build()
            } else {
                RepositoryBuilder()
                    .setGitDir(gitDir)
                    .setWorkTree(rootDir)
                    .setMustExist(true)
                    .build()
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun getUncommittedFiles(): List<File> {
        getGit().use { git ->
            val status = diffIndex(git.repository, "HEAD")
            val uncommittedFiles = status.untracked.toList() + 
                                   status.modified.toList() + 
                                   status.removed.toList() + 
                                   status.added.toList()
            return uncommittedFiles.toSet().map {
                File(rootDir, it)
            }
        }
    }

    override fun getChangedFiles(oldCommit: String, newCommit: String): List<File> {
        getGit().use { git ->
            val oldCommitTree = getCanonicalTreeParser(git, oldCommit)
            val newCommitTree = getCanonicalTreeParser(git, newCommit)
            val diffResult = git.diff()
                .setShowNameAndStatusOnly(true)
                .setOldTree(oldCommitTree)
                .setNewTree(newCommitTree)
                .call()
            return diffResult.map { File(rootDir, it.newPath) }
        }
    }

    override fun filterChangedFiles(commitHash: String, files: List<File>): List<File> {
        val diff = IndexDiff(repository, commitHash, FileTreeIterator(repository))
        diff.setIgnoreSubmoduleMode(SubmoduleWalk.IgnoreSubmoduleMode.ALL)
        diff.setFilter(PathFilterGroup.createFromStrings(files.map { file ->
            file.relativeToOrSelf(rootDir).path.replace('\\', '/')
        }))
        diff.diff()
        val status = Status(diff)
        return status.uncommittedChanges.map {
            File(rootDir, it)
        }
    }

    companion object {
        fun createGitManagerAndTrySearchParent(dir: File): IGitManager {
            var rootDir: File? = dir
            while (rootDir != null) {
                val gitManager = GitManager(rootDir)
                if (gitManager.hasInitGit) {
                    return gitManager
                }
                rootDir = rootDir.parentFile
            }
            return GitManager(dir)
        }
    }
}
```

**设计亮点**:
- **Worktree 支持**: 支持 Git Worktree（`.git/worktrees/xxx`）
- **自动查找**: `createGitManagerAndTrySearchParent` 自动向上查找 Git 根目录
- **JGit 集成**: 使用 JGit 库，无需依赖系统 Git 命令

### 4.3 辅助类

#### 4.3.1 WorktreeRepositoryBuilder - Worktree 仓库构建器

**定义位置**: `git/WorktreeRepositoryBuilder.kt`

用于构建 Git Worktree 仓库，支持 `.git/worktrees/xxx` 结构。

#### 4.3.2 FileMatcher - 文件匹配器

**定义位置**: `git/FileMatcher.kt`

用于匹配 `.gitignore` 规则，过滤不需要编译的文件。

---

## 五、logger - 日志系统

### 5.1 核心类

#### 5.1.1 JuggLogger - Jugg 日志器

**定义位置**: `logger/JuggLogger.kt`

```kotlin
object JuggLogger {
    private val loggerMap = mutableMapOf<String, FileLogger>()
    private val listenerMap = mutableMapOf<String, Logger>()

    fun register(instanceKey: String, logDir: File) {
        val fileLogger = FileLogger(logDir)
        loggerMap[instanceKey] = fileLogger
    }

    fun unregister(instanceKey: String) {
        loggerMap.remove(instanceKey)?.release()
        listenerMap.remove(instanceKey)
    }

    fun listenProjectLog(instanceKey: String, listener: Logger) {
        listenerMap[instanceKey] = listener
    }

    fun getInstance(instanceKey: String, name: String): Logger {
        val fileLogger = loggerMap[instanceKey] ?: throw IllegalStateException("...")
        val listener = listenerMap[instanceKey]
        return FileLoggerWrapper(name, fileLogger, listener)
    }

    fun recreateLogFileIfDeleted(project: Project) {
        val instanceKey = project.name
        loggerMap[instanceKey]?.recreateLogFileIfDeleted()
    }
}
```

**设计亮点**:
- **多实例支持**: 支持多个项目同时运行，每个项目独立的日志文件
- **双重输出**: 同时输出到文件和 IDE 控制台
- **自动恢复**: 日志文件被删除后自动重建

#### 5.1.2 FileLogger - 文件日志器

**定义位置**: `logger/FileLogger.kt`

```kotlin
class FileLogger(private val logDir: File) {
    private val logFile: File
    private var writer: BufferedWriter? = null

    init {
        logDir.mkdirs()
        logFile = File(logDir, "jugg_${System.currentTimeMillis()}.log")
        logFile.createNewFile()
        writer = BufferedWriter(FileWriter(logFile, true))
        
        if (isCreateLastLogLinkFile) {
            createLastLogLinkFile()
        }
    }

    fun log(level: String, tag: String, message: String) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(Date())
        val logMessage = "[$time] [$level] [$tag] $message\n"
        writer?.write(logMessage)
        writer?.flush()
    }

    fun release() {
        writer?.close()
    }

    fun recreateLogFileIfDeleted() {
        if (!logFile.exists()) {
            logFile.createNewFile()
            writer = BufferedWriter(FileWriter(logFile, true))
        }
    }

    private fun createLastLogLinkFile() {
        val lastLogFile = File(logDir, "last.log")
        if (lastLogFile.exists()) {
            lastLogFile.delete()
        }
        // 创建软链接或复制文件
        try {
            Files.createSymbolicLink(lastLogFile.toPath(), logFile.toPath())
        } catch (e: Exception) {
            logFile.copyTo(lastLogFile, overwrite = true)
        }
    }

    companion object {
        var isCreateLastLogLinkFile = true
    }
}
```

#### 5.1.3 FileLoggerWrapper - 文件日志器包装器

**定义位置**: `logger/FileLoggerWrapper.kt`

```kotlin
class FileLoggerWrapper(
    private val name: String,
    private val fileLogger: FileLogger,
    private val listener: Logger?
) : Logger() {
    override fun debug(message: String?) {
        fileLogger.log("DEBUG", name, message ?: "")
        listener?.debug(message)
    }

    override fun info(message: String?) {
        fileLogger.log("INFO", name, message ?: "")
        listener?.info(message)
    }

    override fun warn(message: String?, t: Throwable?) {
        fileLogger.log("WARN", name, message ?: "")
        listener?.warn(message, t)
    }

    override fun error(message: String?, t: Throwable?, vararg details: String?) {
        fileLogger.log("ERROR", name, message ?: "")
        listener?.error(message, t, *details)
    }
}
```

#### 5.1.4 TimeLogger - 时间日志器

**定义位置**: `logger/TimeLogger.kt`

```kotlin
object TimeLogger {
    private val timeMap = mutableMapOf<String, Long>()

    fun start(tag: String) {
        timeMap[tag] = System.currentTimeMillis()
    }

    fun end(tag: String, logger: Logger? = null): Long {
        val startTime = timeMap.remove(tag) ?: return 0
        val costTime = System.currentTimeMillis() - startTime
        logger?.debug("[$tag] cost $costTime ms")
        return costTime
    }
}
```

**用途**: 用于性能分析，记录各个阶段的耗时。

#### 5.1.5 LogDispatcher - 日志分发器

**定义位置**: `logger/LogDispatcher.kt`

用于将日志分发到多个目标（文件、控制台、远程服务器等）。

---

## 六、rpc - RPC 通信

### 6.1 核心类

#### 6.1.1 RpcRequest - RPC 请求

**定义位置**: `rpc/RpcRequest.kt`

```kotlin
data class RpcRequest(
    val cmd: RpcCommand,
    val params: Map<String, String> = emptyMap(),
)

enum class RpcCommand {
    ECHO,
    RUN,
}
```

#### 6.1.2 RpcLocalServer - RPC 本地服务器

**定义位置**: `rpc/RpcLocalServer.kt`

```kotlin
class RpcLocalServer(
    private val port: Int,
    private val rpcCaller: (RpcRequest) -> RpcResponse,
    private val logger: Logger,
) {
    private var server: ServerSocket? = null
    private var isRunning = false

    fun start() {
        isRunning = true
        server = ServerSocket(port)
        logger.info("RPC server started on port $port")

        Thread {
            while (isRunning) {
                try {
                    val client = server?.accept() ?: break
                    Thread {
                        handleClient(client)
                    }.start()
                } catch (e: Exception) {
                    if (isRunning) {
                        logger.warn("Accept client failed", e)
                    }
                }
            }
        }.start()
    }

    fun stop() {
        isRunning = false
        server?.close()
        logger.info("RPC server stopped")
    }

    private fun handleClient(client: Socket) {
        try {
            val input = BufferedReader(InputStreamReader(client.getInputStream()))
            val output = PrintWriter(client.getOutputStream(), true)

            val requestJson = input.readLine()
            val request = Gson().fromJson(requestJson, RpcRequest::class.java)
            val response = rpcCaller(request)
            val responseJson = Gson().toJson(response)
            output.println(responseJson)
        } catch (e: Exception) {
            logger.warn("Handle client failed", e)
        } finally {
            client.close()
        }
    }
}
```

**用途**: 提供本地 RPC 服务器，用于外部工具（如 CI/CD 脚本）调用 Jugg 功能。

---

## 七、server - 服务器和远程编译

### 7.1 核心类

#### 7.1.1 JuggServer - Jugg 服务器

**定义位置**: `server/JuggServer.kt`

```kotlin
class JuggServer(
    private val projectName: String,
    private val pathManager: JuggPathManager,
    private val coroutineScope: CoroutineScope,
    private val logger: Logger,
) {
    private var rpcServer: RpcLocalServer? = null

    fun start() {
        val port = findAvailablePort()
        rpcServer = RpcLocalServer(port, ::handleRpcRequest, logger)
        rpcServer?.start()
        
        // 保存端口到文件
        val portFile = File(pathManager.juggRootDir, ".rpc_port")
        portFile.writeText(port.toString())
    }

    fun stop() {
        rpcServer?.stop()
    }

    fun onCompile() {
        // 通知编译开始
    }

    private fun handleRpcRequest(request: RpcRequest): RpcResponse {
        return when (request.cmd) {
            RpcCommand.ECHO -> RpcResponse(RpcResult.OK, "pong")
            RpcCommand.RUN -> handleRunRequest(request)
        }
    }

    private fun findAvailablePort(): Int {
        var port = 8888
        while (port < 9999) {
            try {
                ServerSocket(port).use { return port }
            } catch (e: IOException) {
                port++
            }
        }
        throw IllegalStateException("No available port")
    }
}
```

#### 7.1.2 JuggRemoteCompileApplier - 远程编译应用器

**定义位置**: `server/JuggRemoteCompileApplier.kt`

用于应用远程编译配置，支持 SSH 和 IFT 同步。

#### 7.1.3 JuggServerChooser - 服务器选择器

**定义位置**: `server/JuggServerChooser.kt`

用于选择远程编译服务器。

---

## 八、platform - 平台抽象层

### 8.1 核心接口

#### 8.1.1 IPlatformApi - 平台 API 接口

**定义位置**: `platform/IPlatformApi.kt`

```kotlin
interface IPlatformApi {
    fun showDialog(title: String, content: String, ...): Boolean
    fun showChangeConfirmDialog(diffResult: DependencyDiffResult?, ...): ConfirmResult
    fun showUserAndPasswordInputDialog(...): String?
    fun allAvailableJavaHomes(): List<String>
    fun getGradleJdkPath(project: Project, logger: Logger): String?
    fun getAndroidHomePath(logger: Logger): String?
    fun getIdeVersion(): String
    fun isHasRelaunchActivityIssues(device: IDeviceAdb, logger: Logger): Boolean
    fun call(rpcRequest: RpcRequest): RpcResponse
}
```

#### 8.1.2 PlatformApi - 平台 API 单例

**定义位置**: `platform/PlatformApi.kt`

```kotlin
object PlatformApi {
    lateinit var impl: IPlatformApi

    fun showDialog(title: String, content: String, ...): Boolean {
        return impl.showDialog(title, content, ...)
    }

    fun allAvailableJavaHomes(): List<String> {
        return impl.allAvailableJavaHomes()
    }

    // ... 其他方法委托给 impl
}
```

**设计亮点**:
- **依赖注入**: 通过 `impl` 属性注入具体实现
- **IDE 实现**: `IdeaPlatformApi` 提供 IDE 环境的实现
- **命令行实现**: `CmdPlatformApi` 提供命令行环境的实现

---

## 九、总结

### 9.1 关键技术点

1. **APK 操作**:
   - JDK 14+ FileSystems API 性能优化（快 90%）
   - 支持 Dynamic Feature Module
   - 自动重签名和对齐

2. **AAPT2 集成**:
   - 守护进程模式，避免重复启动
   - 跨平台支持（macOS、Linux、Windows）
   - 自定义构建版本（aapt2-inclink）

3. **Git 集成**:
   - JGit 库，无需系统 Git 命令
   - 支持 Git Worktree
   - 自动查找 Git 根目录

4. **日志系统**:
   - 多实例支持
   - 双重输出（文件 + 控制台）
   - 自动恢复

5. **平台抽象**:
   - 依赖注入设计
   - IDE 和命令行双重实现
   - 解耦核心逻辑和平台 API

### 9.2 模块依赖

```
main (核心逻辑)
  ├── apk (APK 操作)
  │   └── aapt2 (AAPT2 调用)
  ├── git (Git 集成)
  ├── logger (日志系统)
  ├── rpc (RPC 通信)
  ├── server (服务器)
  └── platform (平台抽象)
      ├── IdeaPlatformApi (IDE 实现)
      └── CmdPlatformApi (命令行实现)
```

### 9.3 扩展点

- **自定义 AAPT2**: 替换 `getEmbeddedAapt2()` 返回的文件
- **自定义日志输出**: 实现 `Logger` 接口
- **自定义 RPC 命令**: 在 `RpcCommand` 枚举中添加新命令
- **自定义平台 API**: 实现 `IPlatformApi` 接口

---

## 附录：文件清单

### apk

| 文件 | 说明 |
|------|------|
| `ApkInfo.kt` | APK 信息数据类 |
| `ApkInfoReader.kt` | APK 信息读取器 |
| `ApkReader.kt` | APK 读取器 |
| `ApkFileModifier.kt` | APK 文件修改器 |
| `ResourceApkModifier.kt` | 资源 APK 修改器 |
| `DefaultApkActivityLocator.kt` | 默认 Activity 定位器 |
| `BuildToolsVersionComparator.kt` | Build Tools 版本比较器 |

### aapt2

| 文件 | 说明 |
|------|------|
| `ApkResInfo.kt` | APK 资源信息数据类 |
| `Aapt2Result.kt` | AAPT2 调用结果 |
| `Aapt2DaemonInvoker.kt` | AAPT2 守护进程调用器 |

### git

| 文件 | 说明 |
|------|------|
| `IGitManager.kt` | Git 管理器接口 |
| `GitManager.kt` | Git 管理器实现 |
| `WorktreeRepositoryBuilder.kt` | Worktree 仓库构建器 |
| `WorktreeFileRepository.kt` | Worktree 文件仓库 |
| `FileMatcher.kt` | 文件匹配器 |
| `IFileMatcher.kt` | 文件匹配器接口 |

### logger

| 文件 | 说明 |
|------|------|
| `JuggLogger.kt` | Jugg 日志器 |
| `FileLogger.kt` | 文件日志器 |
| `FileLoggerWrapper.kt` | 文件日志器包装器 |
| `TimeLogger.kt` | 时间日志器 |
| `LogDispatcher.kt` | 日志分发器 |

### rpc

| 文件 | 说明 |
|------|------|
| `RpcRequest.kt` | RPC 请求 |
| `RpcLocalServer.kt` | RPC 本地服务器 |

### server

| 文件 | 说明 |
|------|------|
| `JuggServer.kt` | Jugg 服务器 |
| `JuggRemoteCompileApplier.kt` | 远程编译应用器 |
| `JuggServerChooser.kt` | 服务器选择器 |
| `RunConfigurationTemplateExt.kt` | 运行配置模板扩展 |

### platform

| 文件 | 说明 |
|------|------|
| `IPlatformApi.kt` | 平台 API 接口 |
| `PlatformApi.kt` | 平台 API 单例 |
