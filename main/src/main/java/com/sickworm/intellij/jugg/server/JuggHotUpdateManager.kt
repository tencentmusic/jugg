package com.sickworm.intellij.jugg.server

import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.runtime.HotUpdateLoadManifest
import com.sickworm.intellij.jugg.project.runtime.StandaloneHotUpdateManifest
import com.sickworm.intellij.jugg.project.runtime.withGlobalResourceLock
import com.sickworm.intellij.jugg.server.protocols.HotUpdateData
import com.sickworm.intellij.jugg.server.protocols.JarFileInfo
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Downloads, validates, publishes, and cleans shared Jugg hot-update files. */
class JuggHotUpdateManager(
    private val juggServer: JuggServer,
    private val loadBaseBuildTime: String,
    val hotUpdateDir: File,
    logger: Logger,
) {
    val storageDir: File = File(hotUpdateDir, "jars")
    val loadManifestFile: File = File(hotUpdateDir, "load_manifest.json")
    val standaloneLoadManifestFile: File = File(hotUpdateDir, "standalone_load_manifest.json")
    val standalonePreviousManifestFile: File = File(hotUpdateDir, "standalone_previous_load_manifest.json")
    val candidatesDir: File = File(hotUpdateDir, "candidates")
    val hotUpdateDataFile: File = File(hotUpdateDir, "hot_update_data.json")
    private val globalRootDir = checkNotNull(hotUpdateDir.absoluteFile.parentFile)
    private val hotUpdateFlag = File(hotUpdateDir, "first_update_flag")
    private val installUpdateFlag = File(hotUpdateDir, "install_update_flag")
    private val logger = logger.getInstance("JuggHotUpdateManager")

    /** Stages network artifacts without the global resource lock, then commits if shared metadata is unchanged. */
    fun prepareUpdate(data: HotUpdateData): JuggHotUpdateResult {
        require(data.isNeedUpdate) { "Hot update data does not require an update" }
        if (!data.isNeedReinstall) require(loadBaseBuildTime.isNotBlank()) { "Runtime build time is required for compatible hot update" }
        logger.debug("Prepare hot update start: targetVersion=${data.targetVersion}, isNeedReinstall=${data.isNeedReinstall}, storageDir=$storageDir")
        val stageDir = File(hotUpdateDir, ".prepare-${UUID.randomUUID()}")
        val preparedJars = createPreparedJars(data.jarFileInfos.orEmpty() + data.standaloneJarFileInfos.orEmpty(), stageDir)
        try {
            val baseData = snapshotCachedJars(preparedJars.values)
            preparedJars.values.filterNot { it.stageFile.isFile }.forEach(::downloadJar)
            val bundle = if (data.isNeedReinstall) downloadBundleCandidate(data, stageDir) else null
            return commitPreparedUpdate(data, baseData, preparedJars, bundle)
        } finally {
            stageDir.deleteRecursively()
        }
    }

    private fun createPreparedJars(infos: List<JarFileInfo>, stageDir: File): Map<String, PreparedHotUpdateJar> {
        val prepared = linkedMapOf<String, PreparedHotUpdateJar>()
        infos.forEach { info ->
            validateUniqueName(info.uniqueName)
            val existing = prepared[info.uniqueName]
            check(existing == null || existing.info.md5 == info.md5) {
                "Hot update JAR name has conflicting checksums: ${info.uniqueName}"
            }
            if (existing == null) {
                prepared[info.uniqueName] = PreparedHotUpdateJar(info, stageDir.resolve("jars/${info.uniqueName}"))
            }
        }
        return prepared
    }

    private fun snapshotCachedJars(preparedJars: Collection<PreparedHotUpdateJar>): HotUpdateData? {
        return withGlobalResourceLock("Snapshot hot update cache", globalRootDir) {
            val baseData = readHotUpdateData()
            preparedJars.forEach { prepared ->
                val target = storageDir.resolve(prepared.info.uniqueName)
                if (!target.exists()) return@forEach
                check(target.isFile && target.md5() == prepared.info.md5) {
                    "Immutable hot update JAR conflicts with existing file: ${prepared.info.uniqueName}"
                }
                prepared.stageFile.parentFile.mkdirs()
                target.copyTo(prepared.stageFile, overwrite = true)
                logger.debug("Reuse verified hot update jar: ${prepared.info.uniqueName}")
            }
            baseData
        }
    }

    private fun downloadJar(prepared: PreparedHotUpdateJar) {
        val info = prepared.info
        logger.debug("Download hot update jar start: ${info.uniqueName}, url=${info.url}")
        try {
            juggServer.downloadFile(info.url, prepared.stageFile)
            check(prepared.stageFile.isFile && prepared.stageFile.length() > 0L) { "Downloaded hot update jar is empty: ${info.uniqueName}" }
            val actualMd5 = prepared.stageFile.md5()
            check(actualMd5 == info.md5) { "MD5 check failed for ${info.uniqueName}, expect=${info.md5}, actual=$actualMd5" }
            logger.debug("Download hot update jar finished: ${info.uniqueName}")
        } catch (e: Exception) {
            logger.debug("Download hot update jar failed: ${info.uniqueName}", e)
            throw e
        }
    }

    private fun commitPreparedUpdate(
        data: HotUpdateData,
        baseData: HotUpdateData?,
        preparedJars: Map<String, PreparedHotUpdateJar>,
        preparedBundle: PreparedBundleCandidate?,
    ): JuggHotUpdateResult {
        return withGlobalResourceLock("Commit hot update", globalRootDir) {
            check(readHotUpdateData() == baseData) { "Hot update was superseded by another runtime" }
            storageDir.mkdirs()
            val publishedJars = preparedJars.mapValues { publishPreparedJar(it.value) }
            val jarFiles = data.jarFileInfos.orEmpty().map { publishedJars.getValue(it.uniqueName) }
            val standaloneJarFiles = data.standaloneJarFileInfos.orEmpty().map { publishedJars.getValue(it.uniqueName) }
            publishUpdateManifests(data, jarFiles, standaloneJarFiles)
            val bundleCandidate = preparedBundle?.let(::publishBundleCandidate)
            writeJsonAtomically(hotUpdateDataFile, data)
            cleanupPreparedUpdateJars(jarFiles, standaloneJarFiles)
            logger.debug("Prepare hot update success: targetVersion=${data.targetVersion}")
            JuggHotUpdateResult(baseData?.targetVersion, jarFiles, standaloneJarFiles, bundleCandidate)
        }
    }

    private fun publishPreparedJar(prepared: PreparedHotUpdateJar): File {
        val target = storageDir.resolve(prepared.info.uniqueName)
        if (target.exists()) {
            check(target.isFile && target.md5() == prepared.info.md5) {
                "Immutable hot update JAR conflicts with existing file: ${prepared.info.uniqueName}"
            }
            return target
        }
        check(prepared.stageFile.isFile && prepared.stageFile.md5() == prepared.info.md5) {
            "Prepared hot update JAR failed verification: ${prepared.info.uniqueName}"
        }
        replaceFile(prepared.stageFile, target)
        return target
    }

    private fun publishUpdateManifests(data: HotUpdateData, jarFiles: List<File>, standaloneJarFiles: List<File>) {
        if (data.isNeedReinstall) {
            logger.debug("Skip load manifest for reinstall hot update")
            return
        }
        logger.debug("Publish hot update load manifest: $loadManifestFile")
        publishLoadManifest(HotUpdateLoadManifest(loadBaseBuildTime, jarFiles.map(File::getName)))
        if (standaloneJarFiles.isNotEmpty()) publishStandaloneLoadManifest(data, standaloneJarFiles)
    }

    private fun cleanupPreparedUpdateJars(jarFiles: List<File>, standaloneJarFiles: List<File>) {
        val referencedJarNames = resolveLoadManifest(loadBaseBuildTime)?.jarFileNames.orEmpty().toMutableSet()
        referencedJarNames.addAll(jarFiles.map(File::getName))
        referencedJarNames.addAll(resolveStandaloneLoadManifest()?.jarFileNames.orEmpty())
        referencedJarNames.addAll(resolveStandalonePreviousManifest()?.jarFileNames.orEmpty())
        referencedJarNames.addAll(standaloneJarFiles.map(File::getName))
        cleanupExpiredJarsLocked(referencedJarNames)
            .forEach { logger.debug("Delete expired hot update jar: ${it.absolutePath}") }
    }

    fun publishEmbeddedIfNeeded(embeddedLibDir: File): Boolean {
        if (!hotUpdateDir.exists() || loadBaseBuildTime.isEmpty() || !embeddedLibDir.isDirectory) return false
        return withGlobalResourceLock("Publish embedded hot update", globalRootDir) {
            if (resolveLoadManifest(loadBaseBuildTime) != null) return@withGlobalResourceLock false
            val embeddedJars = embeddedLibDir.listFiles().orEmpty().filter { it.isFile && it.extension == "jar" }.sortedBy { it.name }
            if (embeddedJars.isEmpty()) return@withGlobalResourceLock false
            storageDir.mkdirs()
            val targetNames = embeddedJars.map { source ->
                val targetName = "${source.nameWithoutExtension}-${source.sha256()}.jar"
                val target = storageDir.resolve(targetName)
                if (target.isFile) {
                    check(target.sha256() == source.sha256()) { "Immutable embedded JAR conflicts with existing file: $targetName" }
                    return@map targetName
                }
                val temp = File(storageDir, "$targetName.${UUID.randomUUID()}.tmp")
                try {
                    source.copyTo(temp, overwrite = true)
                    replaceFile(temp, target)
                } finally {
                    temp.delete()
                }
                targetName
            }
            publishLoadManifest(HotUpdateLoadManifest(loadBaseBuildTime, targetNames))
            true
        }
    }

    fun cleanupExpiredJars(referencedJarNames: Set<String>, nowMillis: Long = System.currentTimeMillis()): List<File> {
        return withGlobalResourceLock("Cleanup hot update jars", globalRootDir) {
            val allReferenced = referencedJarNames.toMutableSet()
            allReferenced.addAll(resolveLoadManifest(loadBaseBuildTime)?.jarFileNames.orEmpty())
            allReferenced.addAll(resolveStandaloneLoadManifest()?.jarFileNames.orEmpty())
            allReferenced.addAll(resolveStandalonePreviousManifest()?.jarFileNames.orEmpty())
            readHotUpdateData()?.let { data ->
                allReferenced.addAll(data.jarFileInfos.orEmpty().map(JarFileInfo::uniqueName))
                allReferenced.addAll(data.standaloneJarFileInfos.orEmpty().map(JarFileInfo::uniqueName))
            }
            cleanupExpiredJarsLocked(allReferenced, nowMillis)
        }
    }

    internal fun resolveLoadManifest(embeddedBuildTime: String): HotUpdateLoadManifest? {
        if (embeddedBuildTime.isEmpty() || !loadManifestFile.isFile) return null
        return runCatching { Gson().fromJson(loadManifestFile.readText(), HotUpdateLoadManifest::class.java) }.getOrNull()
            ?.takeIf { it.baseEmbeddedBuildTime == embeddedBuildTime && it.jarFileNames.isNotEmpty() }
    }

    internal fun publishLoadManifest(manifest: HotUpdateLoadManifest) {
        writeJsonAtomically(loadManifestFile, manifest)
    }

    internal fun resolveStandaloneLoadManifest(): StandaloneHotUpdateManifest? = readStandaloneManifest(standaloneLoadManifestFile)

    internal fun resolveStandalonePreviousManifest(): StandaloneHotUpdateManifest? = readStandaloneManifest(standalonePreviousManifestFile)

    fun activateReinstallCandidate(activePluginBuildId: String): Boolean {
        return withGlobalResourceLock("Activate standalone reinstall candidate", globalRootDir) {
            val data = readHotUpdateData() ?: return@withGlobalResourceLock false
            if (!data.isNeedReinstall || data.releaseBuildId != activePluginBuildId) return@withGlobalResourceLock false
            val jarFiles = data.standaloneJarFileInfos.orEmpty().map { info ->
                validateUniqueName(info.uniqueName)
                storageDir.resolve(info.uniqueName).takeIf { it.isFile && it.md5() == info.md5 }
                    ?: return@withGlobalResourceLock false
            }
            if (jarFiles.isEmpty()) return@withGlobalResourceLock false
            publishStandaloneLoadManifest(data, jarFiles)
            candidatesDir.resolve(activePluginBuildId).deleteRecursively()
            true
        }
    }

    fun hasHotUpdateNotification(): Boolean {
        return withGlobalResourceLock("Check hot update notification", globalRootDir) { hotUpdateFlag.exists() }
    }

    fun consumeHotUpdateNotification(): HotUpdateData? {
        return withGlobalResourceLock("Consume hot update notification", globalRootDir) {
            if (!hotUpdateFlag.exists()) return@withGlobalResourceLock null
            val data = readHotUpdateData() ?: return@withGlobalResourceLock null
            hotUpdateFlag.delete()
            data
        }
    }

    fun readInstallUpdateNotification(): HotUpdateData? {
        return withGlobalResourceLock("Read installed update notification", globalRootDir) {
            if (installUpdateFlag.exists()) readHotUpdateData() else null
        }
    }

    fun clearInstallUpdateNotification(expectedData: HotUpdateData): Boolean {
        return withGlobalResourceLock("Clear installed update notification", globalRootDir) {
            if (readHotUpdateData() != expectedData) return@withGlobalResourceLock false
            installUpdateFlag.delete()
        }
    }

    fun publishUpdateNotification(expectedData: HotUpdateData): Boolean {
        return withGlobalResourceLock("Publish update notification", globalRootDir) {
            if (readHotUpdateData() != expectedData) return@withGlobalResourceLock false
            if (expectedData.isNeedReinstall) {
                installUpdateFlag.createNewFile()
                hotUpdateFlag.delete()
            } else {
                installUpdateFlag.delete()
                hotUpdateFlag.createNewFile()
            }
            true
        }
    }

    private fun publishStandaloneLoadManifest(data: HotUpdateData, jarFiles: List<File>) {
        val buildId = requireNotNull(data.releaseBuildId) { "releaseBuildId is required for standalone hot update" }
        val current = resolveStandaloneLoadManifest()
        if (current != null && current.releaseBuildId != buildId) writeJsonAtomically(standalonePreviousManifestFile, current)
        val manifest = StandaloneHotUpdateManifest(
            schemaVersion = 1,
            runtimeApiVersion = 1,
            bootstrapApiVersion = 1,
            targetVersion = data.targetVersion,
            releaseBuildId = buildId,
            releaseChannel = data.releaseChannel ?: "stable",
            toolingReleaseBuildId = current?.toolingReleaseBuildId ?: loadBaseBuildTime,
            managedBy = "idea",
            jarFileNames = jarFiles.map(File::getName),
            jarSha256 = jarFiles.associate { it.name to it.sha256() },
        )
        writeJsonAtomically(standaloneLoadManifestFile, manifest)
    }

    private fun downloadBundleCandidate(data: HotUpdateData, stageDir: File): PreparedBundleCandidate? {
        val bundleInfo = data.standaloneBundleFileInfo ?: return null
        val buildId = requireNotNull(data.releaseBuildId) { "releaseBuildId is required for standalone Bundle candidate" }
        validateBuildId(buildId)
        validateUniqueName(bundleInfo.uniqueName)
        val stageFile = stageDir.resolve("standalone.zip")
        juggServer.downloadFile(bundleInfo.url, stageFile)
        check(stageFile.isFile && stageFile.md5() == bundleInfo.md5) { "Standalone Bundle candidate failed verification" }
        return PreparedBundleCandidate(buildId, bundleInfo, stageFile)
    }

    private fun publishBundleCandidate(prepared: PreparedBundleCandidate): File {
        val buildId = prepared.buildId
        candidatesDir.listFiles().orEmpty().filter { it.isDirectory && it.name != buildId }.forEach(File::deleteRecursively)
        val candidateDir = candidatesDir.resolve(buildId)
        val target = candidateDir.resolve("standalone.zip")
        candidateDir.mkdirs()
        try {
            check(prepared.stageFile.isFile && prepared.stageFile.md5() == prepared.info.md5) {
                "Standalone Bundle candidate failed verification"
            }
            replaceFile(prepared.stageFile, target)
            return target
        } catch (error: Throwable) {
            candidateDir.deleteRecursively()
            throw error
        }
    }

    private fun readStandaloneManifest(file: File): StandaloneHotUpdateManifest? {
        if (!file.isFile) return null
        return runCatching { Gson().fromJson(file.readText(), StandaloneHotUpdateManifest::class.java) }.getOrNull()
            ?.takeIf { it.jarFileNames.isNotEmpty() && it.releaseBuildId.isNotBlank() }
    }

    private fun readHotUpdateData(): HotUpdateData? {
        if (!hotUpdateDataFile.isFile) return null
        return try {
            Gson().fromJson(hotUpdateDataFile.readText(), HotUpdateData::class.java)
        } catch (e: Exception) {
            logger.debug("Read current hot update data failed", e)
            null
        }
    }

    private fun cleanupExpiredJarsLocked(referencedJarNames: Set<String>, nowMillis: Long = System.currentTimeMillis()): List<File> {
        val expiredBeforeMillis = nowMillis - JAR_RETENTION_MILLIS
        return storageDir.listFiles().orEmpty()
            .filter { it.isFile && it.extension == "jar" }
            .filter { it.name !in referencedJarNames && it.lastModified() < expiredBeforeMillis }
            .filter { it.delete() }
    }

    private fun writeJsonAtomically(target: File, value: Any) {
        target.parentFile?.mkdirs()
        val tempFile = File(target.parentFile, "${target.name}.${System.nanoTime()}.tmp")
        try {
            tempFile.writeText(Gson().toJson(value))
            replaceFile(tempFile, target)
        } finally {
            tempFile.delete()
        }
    }

    private fun replaceFile(source: File, target: File) {
        target.parentFile?.mkdirs()
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun File.md5(): String {
        val digest = MessageDigest.getInstance("MD5")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun File.sha256(): String = digest("SHA-256")

    private fun File.digest(algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                digest.update(buffer, 0, size)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun validateUniqueName(name: String) {
        require(name.isNotBlank() && name == File(name).name && !name.contains('/') && !name.contains('\\') &&
                !name.contains("..") && name.none(Char::isISOControl)) { "Invalid hot update file name: $name" }
    }

    private fun validateBuildId(buildId: String) {
        require(buildId.isNotBlank() && buildId.none(Char::isISOControl) && !buildId.contains('/') &&
                !buildId.contains('\\') && !buildId.contains("..")) { "Invalid releaseBuildId: $buildId" }
    }

    private companion object {
        const val JAR_RETENTION_MILLIS = 90L * 24 * 60 * 60 * 1000
    }
}

/** Describes verified hot-update jars prepared for host-specific installation or restart handling. */
data class JuggHotUpdateResult(
    val previousVersion: String?,
    val jarFiles: List<File>,
    val standaloneJarFiles: List<File> = emptyList(),
    val standaloneBundleCandidate: File? = null,
)

private data class PreparedHotUpdateJar(val info: JarFileInfo, val stageFile: File)

private data class PreparedBundleCandidate(val buildId: String, val info: JarFileInfo, val stageFile: File)
