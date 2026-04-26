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
    private const val SKILLS_BUNDLE_ZIP_RESOURCE = "docs/skills/docs-skills.zip"
    private const val BUNDLED_HOOKS_DIR = "hooks"
    private const val BUNDLED_INSTALL_DOC_PATH = "install/agent_setup.md"
    private val HOOK_SCRIPT_FILES = setOf("start.py", "stop.py")

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
        val bundledSkillsHome = ensureBundledSkillsHome(userHome)
        installSkillToDir(
            sourceSkillDir = File(bundledSkillsHome, SKILL_NAME),
            targetSkillDir = File(skillRootForClient(client, userHome), SKILL_NAME),
        )
        internalDirsForClient(client, userHome)
            .filter { it.exists() }
            .forEach { internalHome ->
                installSkillToDir(
                    sourceSkillDir = File(bundledSkillsHome, SKILL_NAME),
                    targetSkillDir = File(internalHome, "skills/$SKILL_NAME"),
                )
            }
    }

    private fun installSkillToDir(sourceSkillDir: File, targetSkillDir: File) {
        copyDirectory(sourceDir = sourceSkillDir, targetDir = targetSkillDir)
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

    internal fun ensureBundledSkillsHome(userHome: File): File {
        val bundledSkillsHome = File(userHome, ".jugg/skills")
        extractBundledSkills(targetDir = bundledSkillsHome)
        return bundledSkillsHome
    }

    private fun extractBundledSkills(targetDir: File) {
        targetDir.deleteRecursively()
        targetDir.mkdirs()
        val stream = JuggSkillInstaller::class.java.classLoader.getResourceAsStream(SKILLS_BUNDLE_ZIP_RESOURCE)
            ?: throw FileNotFoundException("resource_not_found_$SKILLS_BUNDLE_ZIP_RESOURCE")
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
                    FileOutputStream(outFile).use { output -> zip.copyTo(output) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val bundledSkillDir = File(targetDir, SKILL_NAME)
        if (!bundledSkillDir.isDirectory) {
            throw FileNotFoundException("resource_missing_dir_${SKILL_NAME}")
        }
        val bundledHooksDir = File(targetDir, BUNDLED_HOOKS_DIR)
        if (!bundledHooksDir.isDirectory) {
            throw FileNotFoundException("resource_missing_dir_${BUNDLED_HOOKS_DIR}")
        }
        if (!File(targetDir, BUNDLED_INSTALL_DOC_PATH).isFile) {
            throw FileNotFoundException("resource_missing_file_${BUNDLED_INSTALL_DOC_PATH}")
        }
    }

    private fun copyDirectory(sourceDir: File, targetDir: File) {
        if (!sourceDir.isDirectory) {
            throw FileNotFoundException("source_dir_not_found_${sourceDir.path}")
        }
        targetDir.deleteRecursively()
        targetDir.mkdirs()
        val targetCanonicalPrefix = targetDir.canonicalPath + File.separator
        sourceDir.walkTopDown().forEach { source ->
            val relativePath = source.relativeTo(sourceDir).path
            if (relativePath.isEmpty()) {
                return@forEach
            }
            val outFile = File(targetDir, relativePath)
            if (!outFile.canonicalPath.startsWith(targetCanonicalPrefix)) {
                throw IOException("invalid_relative_path")
            }
            if (source.isDirectory) {
                outFile.mkdirs()
            } else {
                outFile.parentFile?.mkdirs()
                source.inputStream().use { input ->
                    FileOutputStream(outFile).use { output -> input.copyTo(output) }
                }
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
            val bundledScriptsDir = File(ensureBundledSkillsHome(userHome), "$SKILL_NAME/scripts")
            val binDir = File(userHome, ".jugg/bin")
            copyDirectory(sourceDir = bundledScriptsDir, targetDir = binDir)
            if (!isWindows()) {
                setExecutable(binDir)
                createSymlink(userHome, binDir)
            }
            logger.info("[Install Jugg CLI] installed to ${binDir.path}")
        }
    }

    /**
     * Makes bundled hook scripts available from ~/.jugg/skills/hooks.
     */
    fun installHooks(logger: Logger, userHome: File = File(System.getProperty("user.home"))): Result<Unit> {
        return runCatching {
            val bundledHooksDir = File(ensureBundledSkillsHome(userHome), BUNDLED_HOOKS_DIR)
            HOOK_SCRIPT_FILES.forEach { fileName ->
                val sourceFile = File(bundledHooksDir, fileName)
                if (!sourceFile.isFile) {
                    throw FileNotFoundException("source_file_not_found_${sourceFile.path}")
                }
            }
            if (!isWindows()) {
                File(bundledHooksDir, "start.py").takeIf { it.exists() }?.setExecutable(true, false)
                File(bundledHooksDir, "stop.py").takeIf { it.exists() }?.setExecutable(true, false)
            }
            logger.info("[Install Jugg Hooks] installed to ${bundledHooksDir.path}")
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
    val installHooks: Boolean = false,
) {
    val isEmpty: Boolean get() = clients.isEmpty() && !installCli && !installHooks
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
