package com.sickworm.intellij.jugg.ide.bean

import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.gradle.compile.isChild
import com.sickworm.intellij.jugg.gradle.script.camelCompat
import com.sickworm.intellij.jugg.project.JuggException
import com.sickworm.intellij.jugg.project.LocalClasspathStoragePathManager
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

/**
 * Infers the androidTest build variant used to query recent library Test APK build history.
 */
fun inferLibraryTestApkHistoryBuildVariant(
    options: JuggGradleCompileOptions,
    modules: Map<String, ModuleInfo>,
): String? {
    if (options.buildTarget != BuildTarget.ANDROID_TEST) {
        return null
    }
    val requestedVariant = inferRequestedApplicationAndroidTestVariant(modules, options.requestedGradleTasks())
    if (requestedVariant.hasMatch) {
        return requestedVariant.variant
    }
    return inferFallbackAndroidTestVariant(modules)
}

fun JuggGradleCompileOptions.requestedGradleTasks(): Set<String> {
    return compileCommand.split(Regex("\\s+"))
        .map { it.trim() }
        .filter { it.isGradleTaskToken() }
        .toSet()
}

/** Returns compile options that bypass Gradle task output reuse for a confirmed fallback build. */
fun JuggGradleCompileOptions.withGradleCacheRefresh(): JuggGradleCompileOptions {
    val arguments = listOf("--no-build-cache", "--rerun-tasks")
    val existingArguments = compileCommand.split(Regex("\\s+"))
    val missingArguments = arguments.filterNot(existingArguments::contains)
    if (missingArguments.isEmpty()) {
        return this
    }
    return copy(compileCommand = "${compileCommand.trimEnd()} ${missingArguments.joinToString(" ")}")
}

fun parseRemoteSyncExcludePatterns(rawPatterns: String): List<String> {
    return rawPatterns.split(Regex("[;,\\r\\n]+")).asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { normalizeRemoteSyncExcludePattern(it) }
        .toList()
}

/** Returns user-configurable default rsync exclude patterns. */
fun getDefaultRemoteSyncExcludePatterns(): List<String> = listOf(
    "local.properties",
    ".idea/",
    "*.iml",
    ".git/objects/",
    ".git/modules/",
    ".cxx/",
)

private fun normalizeRemoteSyncExcludePattern(pattern: String): String {
    val normalized = pattern.replace('\\', '/')
    val isWindowsAbsolute = normalized.length >= 2 && normalized[1] == ':'
    val hasParentSegment = normalized.split('/').any { it == ".." }
    val hasShellQuote = normalized.any { it == '\'' || it == '"' }
    if (isWindowsAbsolute || hasParentSegment || hasShellQuote) {
        throw JuggException.runConfigInvalid(
            "Run configuration argument [Remote sync exclude patterns] contains invalid pattern: $pattern\n" +
                    "Windows absolute paths, parent paths, and quotes are not supported.",
        )
    }
    return normalized
}

private fun inferRequestedApplicationAndroidTestVariant(
    modules: Map<String, ModuleInfo>,
    requestedTasks: Set<String>,
): RequestedVariantInference {
    val applicationModules = modules.values.filter {
        it.moduleType == ModuleInfo.Type.Application && !it.isAndroidTestModule
    }
    val matchedVariants = requestedTasks.asSequence()
        .mapNotNull { parseRequestedGradleTask(it) }
        .flatMap { task ->
            val matchedModules = task.modulePath?.let { modulePath ->
                applicationModules.filter { it.matchesGradleModulePath(modulePath) }
            } ?: applicationModules
            matchedModules.mapNotNull { module ->
                module.findVariantForTask(task.taskName)?.let { module.name to it }
            }.asSequence()
        }
        .distinct()
        .toList()
    return RequestedVariantInference(
        hasMatch = matchedVariants.isNotEmpty(),
        variant = matchedVariants.singleOrNull()?.second,
    )
}

private data class RequestedVariantInference(
    val hasMatch: Boolean,
    val variant: String?,
)

private fun inferFallbackAndroidTestVariant(modules: Map<String, ModuleInfo>): String? {
    val variants = modules.values
        .filter { it.isAndroidTestModule }
        .map { it.buildVariant }
        .filter { it.isNotBlank() }
        .distinct()
    return variants.singleOrNull()
}

private data class RequestedGradleTask(
    val modulePath: String?,
    val taskName: String,
)

private fun parseRequestedGradleTask(task: String): RequestedGradleTask? {
    val parts = task.trim().trim(':').split(':').filter { it.isNotBlank() }
    if (parts.isEmpty()) {
        return null
    }
    return RequestedGradleTask(
        modulePath = parts.dropLast(1).joinToString(".").takeIf { it.isNotBlank() },
        taskName = parts.last(),
    )
}

private fun String.isGradleTaskToken(): Boolean {
    if (isBlank() || startsWith("-") || contains("=") || contains("/")) {
        return false
    }
    val executableNames = setOf("gradle", "gradlew", "gradle.bat", "gradlew.bat")
    return trim().trim('"', '\'') !in executableNames
}

private fun ModuleInfo.matchesGradleModulePath(modulePath: String): Boolean {
    return name == modulePath || gradleModuleName == modulePath
}

private fun ModuleInfo.findVariantForTask(taskName: String): String? {
    val variantNames = variants.map { it.name }.ifEmpty { listOf(buildVariant) }
        .filter { it.isNotBlank() }
        .distinct()
        .sortedByDescending { it.camelCompat.length }
    return variantNames.firstOrNull { variant ->
        taskName.hasAndroidTaskPrefix() && taskName.contains(variant.camelCompat)
    }?.let { "${it}AndroidTest" }
}

private fun String.hasAndroidTaskPrefix(): Boolean {
    return listOf("assemble", "process", "package", "compile", "bundle", "install").any { startsWith(it) }
}

/**
 * Wrapper of [JuggRunConfigurationOptions], which is used for compilation.
 */
data class JuggGradleCompileOptions(
    /**
     * Local project root path.
     */
    val projectRootPath: String,
    /**
     * Location where stores apk and build files.
     * e.g. build/jugg/classpath
     */
    val localClasspathStoragePath: LocalClasspathStoragePathManager,
    /**
     * Gradle initial script, use to read project info.
     * e.g. $projectRootPath/.gradle/jugg/readProjectInfo.gradle.kts
     */
    val initGradleFilePath: String,
    /**
     * e.g. ./gradlew :app:assembleDebug
     */
    val compileCommand: String,
    /**
     * e.g. app/build/outputs/apk/debug/\*.apk
     * e.g2. app/build/outputs/apk/debug/\*.apk; dynamic_feature/build/outputs/apk/debug/\*.apk
     */
    val outputApkName: String,
    /**
     * whether to use remote server to compile.
     */
    val isRemoteCompile: Boolean,
    /**
     * Whether to sync all files in [localToRemoteSyncPath].
     */
    val isSyncAllProjects: Boolean,
    val remoteSshUser: String,
    val remoteSshPassword: String,
    val remoteSshIp: String,
    val remoteSshPort: Int,
    /**
     * IFT config name for syncing files from local to remote.
     * No meaning if syncMode != IFT.
     */
    val localToRemoteIftConfigName: String,
    /**
     * Root directory to specify syncing files from local to remote.
     * The path must be the parent of [projectRootPath].
     * when syncMode:
     *  IFT -> The path match config of [localToRemoteIftConfigName]. Works with [isSyncAllProjects].
     *  RSYNC -> Works with [isSyncAllProjects].
     *  RSYNC_SIMPLE -> No meaning. RSYNC_SIMPLE will only sync [projectRootPath] and this field must be the parent directory of it.
     */
    val localToRemoteSyncPath: String,
    /**
     * Remote root directory to receive synced files from local.
     * Optional value. If empty, will use default value: $HOME/remote
     * e.g. /root/remote
     */
    val remoteSyncPath: String,
    /**
     * IFT config name for syncing files from remote to local.
     * No meaning if syncMode != IFT.
     */
    val remoteToLocalIftConfigName: String,
    /**
     * Root directory to specify syncing files from remote to local.
     * when syncMode:
     *  IFT -> The path match config of [remoteToLocalIftConfigName].
     *  RSYNC -> Any directory is fine.
     *  RSYNC_SIMPLE -> No meaning. Will store files directly to [localClasspathStoragePath]
     */
    val remoteToLocalSyncPath: String,
    val httpProxyIp: String,
    val httpProxyPort: Int,
    /**
     * Sync mode for remote compile.
     * when syncMode:
     *  IFT -> A specific sync tool.
     *  RSYNC -> Built-in sync tool of Linux & macOS.
     *  RSYNC_SIMPLE -> Built-in sync tool of Linux & macOS, but has simple configuration which will only sync [projectRootPath].
     */
    val syncMode: SyncMode,
    /**
     * Environment variables to set on remote SSH session.
     * Format: VAR=value; VAR1=value1
     */
    val environmentVariables: String,
    /**
     * Compile and launch strategy for this run session.
     * APP (default) compiles only the app variant and starts with am start.
     * ANDROID_TEST compiles app + androidTest variants and starts with am instrument.
     */
    val buildTarget: BuildTarget = BuildTarget.APP,
    /**
     * Gradle tasks replayed to build recent library Test APKs for AndroidTest full builds.
     */
    val libraryTestApkGradleTasks: List<String> = emptyList(),
    /**
     * Output APK patterns matching [libraryTestApkGradleTasks].
     */
    val libraryTestApkOutputPatterns: List<String> = emptyList(),
    /**
     * Rsync glob patterns skipped during local-to-remote source sync.
     */
    val remoteSyncExcludePatterns: List<String> = emptyList(),
    /** Whether [remoteSyncExcludePatterns] replaces the default exclude patterns. */
    val isRemoteSyncExcludePatternsCustomized: Boolean = false,
    /** Whether APK files absent from the current Gradle result should be removed from the local cache. */
    val isCleanupFetchedApks: Boolean = true,
) {

    /** Rsync exclude patterns after applying the default or customized state. */
    val effectiveRemoteSyncExcludePatterns: List<String> get() =
        if (isRemoteSyncExcludePatternsCustomized) {
            remoteSyncExcludePatterns
        } else {
            getDefaultRemoteSyncExcludePatterns()
        }

    private val projectSyncRelativePath get() = if (syncMode.isRsyncSimple) {
        File(projectRootPath).name
    } else {
        File(projectRootPath)
            .relativeTo(File(localToRemoteSyncPath)).path
            .replace(" ", "\\ ")
    }

    private val projectSyncRootRelativePath: String get() = projectSyncRelativePath.substringBefore(File.separatorChar)

    /** remote home directory */
    private val remoteHomePath = if (remoteSshUser == "root") "/root" else "/data/home/$remoteSshUser"

    /** project storage directory */
    val finalRemoteSyncPath = run {
        var finalPath = remoteSyncPath.ifEmpty { "$remoteHomePath/remote" }
        if (!finalPath.startsWith("/")) {
            // relative path
            finalPath = "$remoteHomePath/$finalPath"
        }
        if (finalPath.endsWith("/")) {
            finalPath = finalPath.substring(0, finalPath.length - 1) // must remove last '/' to standardize sync path
        }
        finalPath
    }

    /** local iFt path, used for syncing files to remote by iFt */
    val localSyncIftPath get() = if (isSyncAllProjects) {
        localToRemoteIftConfigName
    } else {
        "$localToRemoteIftConfigName/$projectSyncRootRelativePath"
    }
    val localSyncRsyncPath get() = if (syncMode.isRsyncSimple) {
        "$projectRootPath/"
    } else if (isSyncAllProjects) {
        "$localToRemoteSyncPath/"
    } else {
        "$localToRemoteSyncPath/$projectSyncRootRelativePath/"
    }

    /** remote project sync path, used for syncing files to remote by iFt, and fetching classpath */
    val remoteSyncRootPath get() = if (isSyncAllProjects) {
        finalRemoteSyncPath
    } else {
        "$finalRemoteSyncPath/$projectSyncRootRelativePath"
    }
    val remoteSyncRootRsyncPath get() = if (syncMode.isRsyncSimple) {
        "$remoteSshUser@$remoteSshIp:$finalRemoteSyncPath/$projectSyncRootRelativePath"
    } else {
        "$remoteSshUser@$remoteSshIp:$remoteSyncRootPath"
    }

    /** Use to locates the path of build/jugg */
    val remoteProjectSyncRelativePath get() = if (syncMode.isRsyncSimple) {
        ""
    } else {
        remoteProjectPath.substringAfter(remoteSyncRootPath)
    }

    /** remote project root path, used for compilation */
    val remoteProjectPath get() = "$finalRemoteSyncPath/$projectSyncRelativePath"
    val remoteProjectRsyncPath get() = "$remoteSshUser@$remoteSshIp:$remoteProjectPath/" // rsync_simple use the same path

    /** remote iFt path, used for fetching apk output to local */
    val remoteToLocalProjectIftPath get() = "$remoteToLocalIftConfigName/$projectSyncRelativePath"
    val remoteToLocalProjectRsyncPath get() = if (syncMode.isRsyncSimple) {
        "${localClasspathStoragePath.apkDir.absolutePath}/"
    } else {
        "$remoteToLocalSyncPath/$projectSyncRelativePath/"
    }

    /** remote iFt path, used for fetching classpath output to local */
    val remoteToLocalRootIftPath get() = if (isSyncAllProjects) {
        "$remoteToLocalIftConfigName/jugg_all_classpath"
    } else {
        "$remoteToLocalIftConfigName/$projectSyncRootRelativePath"
    }
    val remoteToLocalRootRsyncPath get() = if (syncMode.isRsyncSimple) {
        "${localClasspathStoragePath.classpathDir.absolutePath}/"
    } else if (isSyncAllProjects) {
        "$remoteToLocalSyncPath/jugg_all_classpath/"
    } else {
        "$remoteToLocalSyncPath/$projectSyncRootRelativePath/"
    }

    /** local apk path, used for get apk output */
    val remoteToLocalProjectSyncPath: String get() = if (syncMode.isRsyncSimple) {
        localClasspathStoragePath.apkDir.absolutePath
    } else {
        "$remoteToLocalSyncPath/$projectSyncRelativePath"
    }

    /** local classpath path, used for get classpath output */
    val remoteToLocalSyncClasspathPath: String get() = if (syncMode.isRsyncSimple) {
        "${localClasspathStoragePath.classpathDir.absolutePath}/$projectSyncRelativePath"
    } else if (isSyncAllProjects) {
        "$remoteToLocalSyncPath/jugg_all_classpath/${File(finalRemoteSyncPath).name}/$projectSyncRelativePath"
    } else {
        "$remoteToLocalSyncPath/$projectSyncRootRelativePath/$projectSyncRelativePath"
    }

    val remoteInitGradleFilePath: String get() {
        val relativePath = File(initGradleFilePath).relativeTo(File(projectRootPath)).path
        return File(remoteProjectPath, relativePath).path
    }

    fun checkConfig() {
        var errorDetails = ""

        if (compileCommand.isEmpty()) {
            errorDetails += "Run configuration argument [Compile command] is empty\n"
        }
        if (outputApkName.isEmpty()) {
            errorDetails += "Run configuration argument [Output apk name] is empty\n"
        }
        if (isRemoteCompile) {
            if (remoteSshUser.isEmpty()) {
                errorDetails += "Run configuration argument [SSH user] is empty\n"
            }
            if (remoteSshIp.isEmpty()) {
                errorDetails += "Run configuration argument [SSH host] is empty\n"
            }
            if (remoteSshPort <= 0) {
                errorDetails += "Run configuration argument [SSH port] is invalid\n"
            }

            if (!syncMode.isRsync) {
                if (localToRemoteIftConfigName.isEmpty()) {
                    errorDetails += "Run configuration argument [Local to remote IFT config] name is empty\n"
                }
                if (remoteToLocalIftConfigName.isEmpty()) {
                    errorDetails += "Run configuration argument [Remote to local IFT config] name is empty\n"
                }
            }

            if (!syncMode.isRsyncSimple) {
                if (localToRemoteSyncPath.isEmpty()) {
                    errorDetails += "Run configuration argument [Local to remote sync path] is empty\n"
                }
                if (remoteToLocalSyncPath.isEmpty()) {
                    errorDetails += "Run configuration argument [Remote to local sync path] is empty\n"
                }
                if (!File(projectRootPath).isChild(File(localToRemoteSyncPath)) &&
                    (projectRootPath != localToRemoteSyncPath)) {
                    errorDetails += "Run configuration argument [Local to remote IFT sync path]($localToRemoteSyncPath) " +
                            "must be the parent of project path($projectRootPath)\n"
                }
            }
        }

        if (errorDetails.isNotEmpty()) {
            throw JuggException.runConfigInvalid(errorDetails)
        }
    }

    fun toSafeString(): String {
        val string = toString()
        val replacePasswordDesc = if (remoteSshPassword.isNotEmpty()) "(has_password)" else "(no_password)"
        return string.replace("remoteSshPassword=$remoteSshPassword", "remoteSshPassword=$replacePasswordDesc")
    }
}
