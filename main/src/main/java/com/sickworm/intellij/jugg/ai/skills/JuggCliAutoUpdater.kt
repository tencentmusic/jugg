package com.sickworm.intellij.jugg.ai.skills

import com.sickworm.intellij.jugg.ai.skills.agents.InstallAgents
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Auto-updates the Jugg CLI (~/.jugg/bin) and installed skill directories
 * when the plugin bundles a newer skill version. Skips if bin dir does not exist.
 * Version is tracked via ~/.jugg/skills/jugg-android-dev-loop/SKILL.md.
 */
object JuggCliAutoUpdater {

    private const val SKILL_ZIP_RESOURCE = "docs/skills/jugg-android-dev-loop.zip"
    private const val SKILL_MD_ENTRY = "SKILL.md"
    private const val SCRIPTS_PREFIX = "scripts/"
    private const val SKILL_NAME = "jugg-android-dev-loop"
    private val SKILL_VERSION_REGEX = Regex("""^version:\s*([^\s]+)""", RegexOption.MULTILINE)
    private var isNeedCheckAndUpdate = false

    fun resetForTest() {
        isNeedCheckAndUpdate = false
    }

    @Synchronized
    fun checkAndUpdate(logger: Logger, userHome: File = File(System.getProperty("user.home"))) {
        if (isNeedCheckAndUpdate) {
            logger.debug("Already auto-update check")
            return
        }
        isNeedCheckAndUpdate = true

        val binDir = File(userHome, ".jugg/bin")
        if (!binDir.exists()) {
            logger.info("Jugg CLI bin dir not found, skipping auto-update")
            return
        }
        val juggSkillDir = File(userHome, ".jugg/skills/$SKILL_NAME")
        val bundledVersion = readVersionFromZip()
        if (bundledVersion == null) {
            logger.warn("Failed to read bundled skill version, skipping auto-update")
            return
        }
        val localVersion = readVersionFromLocal(juggSkillDir) ?: "0.0.0"
        if (compareVersion(bundledVersion, localVersion) <= 0) {
            logger.info("Jugg CLI is up to date (local=$localVersion, bundled=$bundledVersion)")
            return
        }
        logger.info("Updating Jugg CLI: $localVersion -> $bundledVersion")
        updateBinDir(binDir)
        setExecutable(binDir)
        updateJuggSkillDir(juggSkillDir)

        val installedClients = detectInstalledClients(userHome)
        if (installedClients.isNotEmpty()) {
            JuggSkillInstaller.install(File("."), installedClients, logger, userHome)
        }
        logger.info("Jugg CLI auto-update completed")
    }

    /** Reads version from SKILL.md in the bundled zip resource. */
    fun readVersionFromZip(): String? {
        val stream = JuggCliAutoUpdater::class.java.classLoader
            .getResourceAsStream(SKILL_ZIP_RESOURCE) ?: return null
        return ZipInputStream(stream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == SKILL_MD_ENTRY) {
                    return@use extractSkillVersion(zip.readBytes().toString(Charsets.UTF_8))
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
            null
        }
    }

    /** Reads version from SKILL.md in the local skill dir. */
    fun readVersionFromLocal(skillDir: File): String? {
        val skillMd = File(skillDir, SKILL_MD_ENTRY)
        if (!skillMd.exists()) return null
        return extractSkillVersion(skillMd.readText())
    }

    private fun extractSkillVersion(content: String): String? {
        return SKILL_VERSION_REGEX.find(content)?.groupValues?.get(1)
    }

    /**
     * Detects which AI clients already have the skill installed,
     * by checking existence of their skill directories.
     */
    fun detectInstalledClients(userHome: File): Set<InstallClient> {
        return InstallClient.values().filter { client ->
            skillDirsForClient(client, userHome).any { it.exists() }
        }.toSet()
    }

    private fun skillDirsForClient(client: InstallClient, userHome: File): List<File> {
        val agent = InstallAgents.resolveAgentInstaller(client)
        val dirs = mutableListOf(
            File(agent.resolvePrimarySkillRoot(userHome), SKILL_NAME)
        )
        dirs.addAll(
            agent.resolveInternalSkillHomes(userHome)
                .map { internalHome -> File(internalHome, "skills/$SKILL_NAME") }
        )
        return dirs
    }

    private fun setExecutable(binDir: File) {
        File(binDir, "jugg").takeIf { it.exists() }?.setExecutable(true, false)
        File(binDir, "jugg.py").takeIf { it.exists() }?.setExecutable(true, false)
    }

    /**
     * Overwrites binDir with scripts/ content from the bundled zip.
     */
    private fun updateBinDir(binDir: File) {
        binDir.deleteRecursively()
        binDir.mkdirs()
        val stream = JuggCliAutoUpdater::class.java.classLoader
            .getResourceAsStream(SKILL_ZIP_RESOURCE)
            ?: throw IOException("Bundled zip resource not found: $SKILL_ZIP_RESOURCE")
        ZipInputStream(stream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.startsWith(SCRIPTS_PREFIX) && !entry.isDirectory) {
                    val outFile = File(binDir, entry.name.removePrefix(SCRIPTS_PREFIX))
                    val canonicalParent = binDir.canonicalPath + File.separator
                    if (!outFile.canonicalPath.startsWith(canonicalParent)) {
                        throw IOException("Invalid zip entry path: ${entry.name}")
                    }
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { zip.copyTo(it) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    /** Overwrites ~/.jugg/skills/jugg-android-dev-loop/ with full skill content from bundled zip. */
    private fun updateJuggSkillDir(skillDir: File) {
        skillDir.deleteRecursively()
        skillDir.mkdirs()
        val stream = JuggCliAutoUpdater::class.java.classLoader
            .getResourceAsStream(SKILL_ZIP_RESOURCE)
            ?: throw IOException("Bundled zip resource not found: $SKILL_ZIP_RESOURCE")
        ZipInputStream(stream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val outFile = File(skillDir, entry.name)
                val canonicalParent = skillDir.canonicalPath + File.separator
                if (!outFile.canonicalPath.startsWith(canonicalParent)) {
                    throw IOException("Invalid zip entry path: ${entry.name}")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { zip.copyTo(it) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun compareVersion(a: String, b: String): Int {
        val cleanA = a.replace("-SNAPSHOT", "")
        val cleanB = b.replace("-SNAPSHOT", "")

        val (versionA, suffixA) = parseVersionAndSuffix(cleanA)
        val (versionB, suffixB) = parseVersionAndSuffix(cleanB)

        val versionResult = compareVersionNumbers(versionA, versionB)
        if (versionResult != 0) {
            return versionResult
        }
        return compareSuffix(suffixA, suffixB)
    }

    private fun parseVersionAndSuffix(version: String): Pair<String, String> {
        val parts = version.split("-", limit = 2)
        return if (parts.size == 2) {
            parts[0] to parts[1]
        } else {
            parts[0] to ""
        }
    }

    private fun compareVersionNumbers(a: String, b: String): Int {
        val partsA = a.split(".").map { it.toIntOrNull() ?: 0 }
        val partsB = b.split(".").map { it.toIntOrNull() ?: 0 }
        val maxLength = maxOf(partsA.size, partsB.size)
        for (index in 0 until maxLength) {
            val partA = if (index < partsA.size) partsA[index] else 0
            val partB = if (index < partsB.size) partsB[index] else 0
            if (partA != partB) {
                return partA.compareTo(partB)
            }
        }
        return 0
    }

    private fun compareSuffix(a: String, b: String): Int {
        val priorityA = suffixPriority(a)
        val priorityB = suffixPriority(b)
        if (priorityA != priorityB) {
            return priorityA.compareTo(priorityB)
        }
        return if (a.startsWith("rc") && b.startsWith("rc")) {
            val rcNumA = a.substring(2).toIntOrNull() ?: 0
            val rcNumB = b.substring(2).toIntOrNull() ?: 0
            rcNumA.compareTo(rcNumB)
        } else {
            a.compareTo(b)
        }
    }

    private fun suffixPriority(suffix: String): Int {
        return when {
            suffix.isEmpty() -> 3
            suffix.startsWith("rc") -> 2
            suffix.startsWith("feature") -> 1
            else -> 0
        }
    }
}
