@file:Suppress("SameParameterValue")

package com.sickworm.intellij.jugg.ide.logic

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * Installs Jugg skill for supported clients without relying on shell scripts.
 */
object JuggSkillInstaller {

    private const val SKILL_NAME = "jugg-android-dev-loop"
    private const val SKILL_ZIP_RESOURCE = "docs/skills/jugg-android-dev-loop.zip"

    /**
     * Installs skill for selected clients by extracting bundled skill files.
     */
    fun install(projectDir: File, selectedClients: Set<InstallClient>, logger: Logger): InstallSummary {
        return install(projectDir, selectedClients, logger, File(System.getProperty("user.home")))
    }

    fun install(projectDir: File, selectedClients: Set<InstallClient>, logger: Logger, userHome: File): InstallSummary {
        if (selectedClients.isEmpty()) {
            return InstallSummary(emptyList())
        }

        val results = selectedClients.map { client ->
            installForClient(projectDir, client, logger, userHome)
        }.sortedBy { it.agent }
        return InstallSummary(results)
    }

    private fun installForClient(projectDir: File, client: InstallClient, logger: Logger, userHome: File): InstallAgentResult {
        val reasons = mutableListOf<String>()
        val skillStatus = runCatching {
            installSkill(client, userHome)
            "ok"
        }.getOrElse { error ->
            reasons.add("SKILL:${error.safeReason()}")
            "fail"
        }
        logger.info("[Install Jugg Skills] projectDir=${projectDir.path}, agent=${client.cliName}, skill=$skillStatus")
        return InstallAgentResult(client.cliName, skillStatus, reasons.distinct())
    }

    private fun installSkill(client: InstallClient, userHome: File) {
        installSkillToDir(File(skillRootForClient(client, userHome), SKILL_NAME))
        internalDirsForClient(client, userHome)
            .filter { it.exists() }
            .forEach { internalHome -> installSkillToDir(File(internalHome, "skills/$SKILL_NAME")) }
    }

    private fun installSkillToDir(skillDir: File) {
        if (skillDir.exists()) {
            skillDir.deleteRecursively()
        }
        skillDir.mkdirs()
        unzipSkillResource(skillDir)
    }

    /**
     * Returns the skill directories that will be written for the given client.
     * The primary directory is always included; internal directories are only
     * included when they already exist on disk.
     */
    fun getInstallDirs(client: InstallClient, userHome: File): List<File> {
        val primary = File(skillRootDirNoCreate(client, userHome), SKILL_NAME)
        val internals = internalDirsForClient(client, userHome)
            .filter { it.exists() }
            .map { File(it, "skills/$SKILL_NAME") }
        return listOf(primary) + internals
    }

    private fun skillRootForClient(client: InstallClient, userHome: File): File {
        return skillRootDirNoCreate(client, userHome).also { it.mkdirs() }
    }

    private fun skillRootDirNoCreate(client: InstallClient, userHome: File): File {
        return when (client) {
            InstallClient.CODEX -> File(codexHomeDir(userHome), "skills")
            InstallClient.CLAUDE -> File(claudeHomeDir(userHome), "skills")
            InstallClient.GEMINI -> File(geminiHomeDir(userHome), "skills")
            InstallClient.CODEBUDDY -> File(userHome, ".codebuddy/skills")
            InstallClient.CURSOR -> File(userHome, ".cursor/skills")
        }
    }

    private fun internalDirsForClient(client: InstallClient, userHome: File): List<File> {
        return when (client) {
            InstallClient.CODEX -> listOf(File(userHome, ".codex-internal"))
            InstallClient.CLAUDE -> listOf(File(userHome, ".claude-internal"))
            InstallClient.GEMINI -> listOf(File(userHome, ".gemini-internal"))
            InstallClient.CODEBUDDY -> emptyList()
            InstallClient.CURSOR -> emptyList()
        }
    }

    private fun unzipSkillResource(targetDir: File) {
        val stream = JuggSkillInstaller::class.java.classLoader.getResourceAsStream(SKILL_ZIP_RESOURCE)
            ?: throw FileNotFoundException("resource_not_found_$SKILL_ZIP_RESOURCE")
        ZipInputStream(stream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                val canonicalParent = targetDir.canonicalPath + File.separator
                if (!outFile.canonicalPath.startsWith(canonicalParent)) {
                    throw IOException("invalid_zip_entry_path")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { output ->
                        zip.copyTo(output)
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun codexHomeDir(userHome: File): File {
        val envHome = System.getenv("CODEX_HOME")
        return if (!envHome.isNullOrBlank()) File(envHome) else File(userHome, ".codex")
    }

    private fun claudeHomeDir(userHome: File): File {
        val dotClaude = File(userHome, ".claude")
        val configClaude = File(userHome, ".config/claude")
        return when {
            dotClaude.exists() -> dotClaude
            configClaude.exists() -> configClaude
            else -> dotClaude
        }
    }

    private fun geminiHomeDir(userHome: File): File {
        val envHome = System.getenv("GEMINI_HOME")
        return if (!envHome.isNullOrBlank()) File(envHome) else File(userHome, ".gemini")
    }

    private fun Throwable.safeReason(): String {
        return (message ?: javaClass.simpleName).replace(Regex("\\s+"), "_")
    }

    /**
     * Installs the jugg CLI to ~/.jugg/bin by extracting the bundled scripts/ directory.
     * On macOS/Linux, also sets executable permissions and creates a symlink in ~/.local/bin.
     * Returns Result.success on success, Result.failure on error.
     */
    fun installCli(logger: Logger, userHome: File = File(System.getProperty("user.home"))): Result<Unit> {
        return runCatching {
            val binDir = File(userHome, ".jugg/bin")
            extractScriptsToBinDir(binDir)
            if (!isWindows()) {
                setExecutable(binDir)
                createSymlink(userHome, binDir)
            }
            logger.info("[Install Jugg CLI] installed to ${binDir.path}")
        }
    }

    private fun extractScriptsToBinDir(binDir: File) {
        binDir.deleteRecursively()
        binDir.mkdirs()
        val stream = JuggSkillInstaller::class.java.classLoader.getResourceAsStream(SKILL_ZIP_RESOURCE)
            ?: throw FileNotFoundException("resource_not_found_$SKILL_ZIP_RESOURCE")
        val scriptsPrefix = "scripts/"
        ZipInputStream(stream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.startsWith(scriptsPrefix) && !entry.isDirectory) {
                    val relativePath = entry.name.removePrefix(scriptsPrefix)
                    val outFile = File(binDir, relativePath)
                    val canonicalParent = binDir.canonicalPath + File.separator
                    if (!outFile.canonicalPath.startsWith(canonicalParent)) {
                        throw IOException("invalid_zip_entry_path")
                    }
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { zip.copyTo(it) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    private fun setExecutable(binDir: File) {
        File(binDir, "jugg").takeIf { it.exists() }?.setExecutable(true, false)
        File(binDir, "jugg.py").takeIf { it.exists() }?.setExecutable(true, false)
    }

    private fun createSymlink(userHome: File, binDir: File) {
        val localBin = File(userHome, ".local/bin").also { it.mkdirs() }
        val symlinkFile = File(localBin, "jugg")
        val target = File(binDir, "jugg.py").toPath()
        // Remove existing symlink or file, then create new symlink
        if (symlinkFile.exists() || Files.isSymbolicLink(symlinkFile.toPath())) {
            symlinkFile.delete()
        }
        Files.createSymbolicLink(symlinkFile.toPath(), target)
    }

    private fun isWindows() = System.getProperty("os.name")?.lowercase()?.contains("windows") == true
}

enum class InstallClient(val cliName: String) {
    CODEX("codex"),
    CLAUDE("claude"),
    GEMINI("gemini"),
    CODEBUDDY("codebuddy"),
    CURSOR("cursor"),
}

/** Holds the full selection from the install dialog. */
data class InstallOptions(
    val clients: Set<InstallClient>,
    val installCli: Boolean,
) {
    val isEmpty: Boolean get() = clients.isEmpty() && !installCli
}

data class InstallAgentResult(
    val agent: String,
    val skillStatus: String?,
    val reasons: List<String>,
)

data class InstallSummary(
    val results: List<InstallAgentResult>,
) {
    val isAllSuccess: Boolean
        get() = results.isNotEmpty() && results.all { it.skillStatus == "ok" }

    fun toDisplayText(): String {
        if (results.isEmpty()) {
            return "No client selected."
        }
        return results.joinToString("\n") { result ->
            val reasonText = if (result.reasons.isEmpty()) {
                ""
            } else {
                " | reasons=${result.reasons.joinToString(",")}"
            }
            "agent=${result.agent} skill=${result.skillStatus ?: "missing"}$reasonText"
        }
    }
}
