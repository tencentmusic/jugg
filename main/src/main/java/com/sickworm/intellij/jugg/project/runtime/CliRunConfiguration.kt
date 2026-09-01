package com.sickworm.intellij.jugg.project.runtime

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.bean.SyncMode
import com.sickworm.intellij.jugg.project.info.JuggProjectInfo
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/**
 * Project-level build profile shared by IDEA and standalone runtimes.
 */
data class CliRunConfiguration(
    val schemaVersion: Int = SCHEMA_VERSION,
    val id: String,
    val name: String,
    val generatedBy: String,
    val generatedAt: Long,
    val moduleName: String,
    val variant: String,
    val buildTarget: BuildTarget,
    val compileCommand: String,
    val outputApkName: String,
    val isRemoteCompile: Boolean = false,
    val isSyncAllProjects: Boolean = false,
    val remoteSshUser: String = "",
    val remoteSshPassword: String = "",
    val remoteSshIp: String = "",
    val remoteSshPort: Int = 0,
    val localToRemoteIftConfigName: String = "",
    val localToRemoteSyncPath: String = "",
    val remoteSyncPath: String = "",
    val remoteToLocalIftConfigName: String = "",
    val remoteToLocalSyncPath: String = "",
    val httpProxyIp: String = "",
    val httpProxyPort: Int = 0,
    val syncMode: String = SyncMode.IFT.modeName,
    val environmentVariables: String = "",
    val remoteSyncExcludePatterns: String = "",
) {

    override fun toString(): String {
        val password = if (remoteSshPassword.isEmpty()) "no_password" else "has_password"
        return "CliRunConfiguration(id=$id, name=$name, generatedBy=$generatedBy, generatedAt=$generatedAt, " +
            "moduleName=$moduleName, variant=$variant, buildTarget=$buildTarget, isRemoteCompile=$isRemoteCompile, " +
            "remoteSshPassword=$password)"
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/**
 * Infers stable default profiles and records the effective fields of successful Gradle builds.
 */
object CliRunConfigurationGenerator {

    fun generate(
        projectInfo: JuggProjectInfo,
        recentSuccessfulBuild: CliRunConfiguration? = null,
        generatedAt: Long = System.currentTimeMillis(),
    ): CliRunConfiguration {
        recentSuccessfulBuild?.let { return it }
        return generateForModule(selectApplicationModule(projectInfo), generatedAt)
    }

    fun generateForModule(
        module: ModuleInfo,
        generatedAt: Long = System.currentTimeMillis(),
    ): CliRunConfiguration {
        val variant = module.buildVariant.ifBlank { ModuleInfo.DEFAULT_BUILD_VARIANT }
        val outputPrefix = runCatching {
            module.buildPathInfo.buildDir.relativeTo(module.projectRootDir).invariantSeparatorsPath.trimEnd('/') + "/"
        }.getOrElse {
            module.moduleStdPath.takeIf { path -> path.isNotEmpty() }?.plus("/build/") ?: "build/"
        }
        return generateForModuleIdentity(
            modulePath = module.moduleStdPath,
            moduleName = module.name,
            variant = variant,
            outputApkName = "${outputPrefix}outputs/apk/${variant.outputDirectory()}/*.apk",
            generatedAt = generatedAt,
        )
    }

    /** Generates the same stable profile when only a verified Gradle module identity is available. */
    fun generateForModuleIdentity(
        modulePath: String,
        moduleName: String,
        variant: String,
        outputApkName: String,
        generatedAt: Long = System.currentTimeMillis(),
    ): CliRunConfiguration {
        val moduleStdPath = modulePath.trim(':').replace(':', '/').trim('/')
        require(moduleStdPath.isNotEmpty()) { "Module path is empty" }
        require(variant.isNotBlank()) { "Variant is empty" }
        val task = ":${moduleStdPath.replace('/', ':')}:assemble${variant.upperCamel()}"
        return CliRunConfiguration(
            id = stableId(moduleStdPath, variant),
            name = "$moduleName $variant",
            generatedBy = "gradle-project-info",
            generatedAt = generatedAt,
            moduleName = moduleName,
            variant = variant,
            buildTarget = BuildTarget.APP,
            compileCommand = "./gradlew $task",
            outputApkName = outputApkName,
        )
    }

    fun fromCompileOptions(
        base: CliRunConfiguration,
        options: JuggGradleCompileOptions,
        projectInfo: JuggProjectInfo,
        generatedAt: Long = System.currentTimeMillis(),
    ): CliRunConfiguration {
        val identity = resolveBuildIdentity(projectInfo, options.compileCommand)
        return base.copy(
            generatedAt = generatedAt,
            moduleName = identity.first,
            variant = identity.second,
            buildTarget = options.buildTarget,
            compileCommand = options.compileCommand,
            outputApkName = options.outputApkName,
            isRemoteCompile = options.isRemoteCompile,
            isSyncAllProjects = options.isSyncAllProjects,
            remoteSshUser = options.remoteSshUser,
            remoteSshPassword = options.remoteSshPassword,
            remoteSshIp = options.remoteSshIp,
            remoteSshPort = options.remoteSshPort,
            localToRemoteIftConfigName = options.localToRemoteIftConfigName,
            localToRemoteSyncPath = options.localToRemoteSyncPath,
            remoteSyncPath = options.remoteSyncPath,
            remoteToLocalIftConfigName = options.remoteToLocalIftConfigName,
            remoteToLocalSyncPath = options.remoteToLocalSyncPath,
            httpProxyIp = options.httpProxyIp,
            httpProxyPort = options.httpProxyPort,
            syncMode = options.syncMode.modeName,
            environmentVariables = options.environmentVariables,
            remoteSyncExcludePatterns = options.remoteSyncExcludePatterns.joinToString(";"),
        )
    }

    fun resolveBuildIdentity(projectInfo: JuggProjectInfo, compileCommand: String): Pair<String, String> {
        val applicationModules = projectInfo.applicationModules()
        applicationModules.forEach { module ->
            encodedBuildVariant(compileCommand, module)?.let { variant ->
                return module.name to variant
            }
        }
        val fallback = selectApplicationModule(projectInfo)
        return fallback.name to fallback.buildVariant.ifBlank { ModuleInfo.DEFAULT_BUILD_VARIANT }
    }

    fun matchesBuildIdentity(compileCommand: String, module: ModuleInfo, variant: String): Boolean {
        return encodedBuildVariant(compileCommand, module) == variant
    }

    /**
     * True only when the command encodes a known variant of [module] that is not [activeVariant].
     * Unknown or custom tasks are not treated as a variant switch.
     */
    fun targetsDifferentBuildVariant(
        compileCommand: String,
        module: ModuleInfo,
        activeVariant: String,
    ): Boolean {
        return gradleTasks(compileCommand)
            .mapNotNull { variantInModuleTask(it, module) }
            .any { it != activeVariant }
    }

    fun encodedBuildVariant(compileCommand: String, module: ModuleInfo): String? {
        return gradleTasks(compileCommand).firstNotNullOfOrNull { variantInModuleTask(it, module) }
    }

    /** Returns the generated profile only when the command is still the exact generated command. */
    fun findGeneratedConfiguration(compileCommand: String, module: ModuleInfo): CliRunConfiguration? {
        return module.knownVariants()
            .asSequence()
            .map { variant -> generateForModule(module.copy(buildVariant = variant)) }
            .singleOrNull { it.compileCommand == compileCommand.trim() }
    }

    private fun selectApplicationModule(projectInfo: JuggProjectInfo): ModuleInfo {
        val modules = projectInfo.applicationModules()
        return modules.firstOrNull { it.name == "app" || it.moduleStdPath == "app" }
            ?: modules.minWithOrNull(compareBy<ModuleInfo> { it.moduleStdPath }.thenBy { it.name })
            ?: throw IllegalStateException("No application module found in Gradle project info")
    }

    private fun JuggProjectInfo.applicationModules(): List<ModuleInfo> {
        return modules.values.filter { it.moduleType == ModuleInfo.Type.Application && !it.isAndroidTestModule }
    }

    private fun ModuleInfo.gradleModulePath(): String {
        val path = moduleStdPath.replace('/', ':').replace(File.separatorChar, ':').trim(':')
        return if (path.isEmpty()) "" else ":$path:"
    }

    private fun gradleTasks(compileCommand: String): List<String> {
        return compileCommand.split(Regex("\\s+")).filter { it.isNotBlank() }
    }

    private fun ModuleInfo.knownVariants(): List<String> {
        return (listOf(buildVariant) + variants.map { it.name })
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun variantInModuleTask(task: String, module: ModuleInfo): String? {
        val modulePath = module.gradleModulePath()
        if (modulePath.isEmpty() || !task.startsWith(modulePath)) {
            return null
        }
        val remainder = task.removePrefix(modulePath)
        if (remainder.isEmpty() || remainder.contains(':')) {
            return null
        }
        return module.knownVariants()
            .map { it to it.upperCamel() }
            .sortedByDescending { it.second.length }
            .firstOrNull { remainder.endsWith(it.second) }
            ?.first
    }

    private fun stableId(modulePath: String, variant: String): String {
        return UUID.nameUUIDFromBytes("gradle-project-info|$modulePath|$variant".toByteArray(Charsets.UTF_8)).toString()
    }

    private fun String.upperCamel(): String {
        return replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private fun String.outputDirectory(): String {
        val match = Regex("^(.*?)(Debug|Release)$").matchEntire(this) ?: return this
        val flavor = match.groupValues[1]
        val buildType = match.groupValues[2].lowercase()
        return if (flavor.isEmpty()) buildType else "$flavor/$buildType"
    }
}

/** Serializes the versioned run-configuration and current-pointer schemas. */
class CliRunConfigurationSerializer {

    fun serialize(configuration: CliRunConfiguration): String = gson.toJson(configuration)

    fun deserialize(json: String): CliRunConfiguration {
        return gson.fromJson(json, CliRunConfiguration::class.java).also {
            require(it.schemaVersion == CliRunConfiguration.SCHEMA_VERSION) { "Unsupported run configuration schema: ${it.schemaVersion}" }
            UUID.fromString(it.id)
        }
    }

    fun serializePointer(configId: String): String {
        UUID.fromString(configId)
        return gson.toJson(CurrentPointer(CliRunConfiguration.SCHEMA_VERSION, configId))
    }

    fun deserializePointer(json: String): String {
        val pointer = gson.fromJson(json, CurrentPointer::class.java)
        require(pointer.schemaVersion == CliRunConfiguration.SCHEMA_VERSION) { "Unsupported run configuration pointer schema: ${pointer.schemaVersion}" }
        UUID.fromString(pointer.configId)
        return pointer.configId
    }

    private data class CurrentPointer(val schemaVersion: Int, val configId: String)

    companion object {
        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    }
}

/**
 * Persists independent project profiles and the current pointer with atomic file replacement.
 */
class CliRunConfigurationStore(
    val pathManager: JuggPathManager,
    private val serializer: CliRunConfigurationSerializer = CliRunConfigurationSerializer(),
) {

    @Synchronized
    fun save(configuration: CliRunConfiguration) {
        UUID.fromString(configuration.id)
        writeAtomically(File(pathManager.runConfigurationsDir, "${configuration.id}.json"), serializer.serialize(configuration))
    }

    @Synchronized
    fun load(id: String): CliRunConfiguration? {
        UUID.fromString(id)
        val file = File(pathManager.runConfigurationsDir, "$id.json")
        return if (file.isFile) serializer.deserialize(file.readText(Charsets.UTF_8)) else null
    }

    @Synchronized
    fun loadAll(): List<CliRunConfiguration> {
        return pathManager.runConfigurationsDir.listFiles { file -> file.isFile && file.extension == "json" }
            ?.sortedBy { it.name }
            ?.mapNotNull { runCatching { serializer.deserialize(it.readText(Charsets.UTF_8)) }.getOrNull() }
            ?: emptyList()
    }

    @Synchronized
    fun select(configId: String) {
        writeAtomically(pathManager.currentRunConfigurationFile, serializer.serializePointer(configId))
    }

    @Synchronized
    fun loadCurrent(): CliRunConfiguration? {
        val pointerFile = pathManager.currentRunConfigurationFile
        if (!pointerFile.isFile) {
            return null
        }
        return runCatching { load(serializer.deserializePointer(pointerFile.readText(Charsets.UTF_8))) }.getOrNull()
    }

    private fun writeAtomically(target: File, content: String) {
        target.parentFile?.mkdirs()
        setOwnerOnlyPermissions(target.parentFile, isDirectory = true)
        val temp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
        temp.writeText(content, Charsets.UTF_8)
        setOwnerOnlyPermissions(temp, isDirectory = false)
        try {
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            setOwnerOnlyPermissions(target, isDirectory = false)
        } finally {
            temp.delete()
        }
    }

    private fun setOwnerOnlyPermissions(file: File?, isDirectory: Boolean) {
        if (file == null || !file.exists()) {
            return
        }
        runCatching {
            val permissions = if (isDirectory) "rwx------" else "rw-------"
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(permissions))
        }
    }
}

fun CliRunConfiguration.toCompileOptions(pathManager: JuggPathManager): JuggGradleCompileOptions {
    return JuggGradleCompileOptions(
        projectRootPath = pathManager.projectDir.absolutePath,
        localClasspathStoragePath = pathManager.localClasspathStoragePathManager,
        initGradleFilePath = pathManager.initGradleFilePath.path,
        compileCommand = compileCommand,
        outputApkName = outputApkName,
        isRemoteCompile = isRemoteCompile,
        isSyncAllProjects = isSyncAllProjects,
        remoteSshUser = remoteSshUser,
        remoteSshPassword = remoteSshPassword,
        remoteSshIp = remoteSshIp,
        remoteSshPort = remoteSshPort,
        localToRemoteIftConfigName = localToRemoteIftConfigName,
        localToRemoteSyncPath = localToRemoteSyncPath,
        remoteSyncPath = remoteSyncPath,
        remoteToLocalIftConfigName = remoteToLocalIftConfigName,
        remoteToLocalSyncPath = remoteToLocalSyncPath,
        httpProxyIp = httpProxyIp,
        httpProxyPort = httpProxyPort,
        syncMode = SyncMode.values().find { it.modeName == syncMode } ?: SyncMode.IFT,
        environmentVariables = environmentVariables,
        buildTarget = buildTarget,
        remoteSyncExcludePatterns = remoteSyncExcludePatterns.split(';').map { it.trim() }.filter { it.isNotEmpty() },
    )
}
