@file:Suppress("SameParameterValue")

package com.sickworm.intellij.jugg.ai.skills

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.ai.skills.agents.CodexAgentInstaller
import com.sickworm.intellij.jugg.ai.skills.agents.IAgentInstaller
import com.sickworm.intellij.jugg.ai.skills.agents.InstallAgents
import com.sickworm.intellij.jugg.project.runtime.withGlobalResourceLock
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipInputStream

/**
 * Installs Jugg skill for supported clients without relying on shell scripts.
 */
object JuggSkillInstaller {

    private const val SKILL_NAME = "jugg-android-dev-loop"
    private const val SKILLS_BUNDLE_ZIP_RESOURCE = "docs/skills/docs-skills.zip"
    private const val BUNDLED_SCRIPTS_PREFIX = "$SKILL_NAME/scripts/"
    private const val BUNDLED_HOOKS_DIR = "hooks"
    const val HOOK_BLOCK_DISABLED_FLAG_FILE_NAME = "DISABLE_BLOCK"
    private const val BUNDLED_INSTALL_DOC_PATH = "install/agent_setup.md"
    private val HOOK_SCRIPT_FILES = setOf("start.py", "stop.py", "edit.py", "command.py", "hook_common.py")

    /**
     * Installs skill for selected clients by extracting bundled skill files.
     */
    fun install(projectDir: File, selectedClients: Set<InstallClient>, logger: Logger): InstallSummary {
        return install(projectDir, selectedClients, logger, File(System.getProperty("user.home")))
    }

    fun install(projectDir: File, selectedClients: Set<InstallClient>, logger: Logger, userHome: File): InstallSummary {
        return withGlobalResourceLock("Install Jugg skills", File(userHome, ".jugg")) {
            if (selectedClients.isEmpty()) return@withGlobalResourceLock InstallSummary(emptyList())
            val results = selectedClients.map { client ->
                installForClient(projectDir, client, logger, userHome)
            }.sortedBy { it.agent }
            InstallSummary(results)
        }
    }

    private fun installForClient(projectDir: File, client: InstallClient, logger: Logger, userHome: File): InstallAgentResult {
        val reasons = mutableListOf<String>()
        val skillStatus = runCatching {
            installSkill(client, logger, userHome)
            "ok"
        }.getOrElse { error ->
            val reason = error.safeReason()
            reasons.add("SKILL:$reason")
            logger.warn(
                "[Install Jugg Skills] projectDir=${projectDir.path}, agent=${client.cliName}, skill=fail, reason=$reason",
                error,
            )
            "fail"
        }
        logger.info("[Install Jugg Skills] projectDir=${projectDir.path}, agent=${client.cliName}, skill=$skillStatus")
        return InstallAgentResult(client.cliName, skillStatus, reasons.distinct())
    }

    private fun installSkill(client: InstallClient, logger: Logger, userHome: File) {
        val bundledSkillsHome = ensureBundledSkillsHome(userHome)
        val agent = InstallAgents.resolveAgentInstaller(client)
        installSkillToDir(
            sourceSkillDir = File(bundledSkillsHome, SKILL_NAME),
            targetSkillDir = File(skillRootForClient(agent, userHome), SKILL_NAME),
        )
        agent.resolveInternalSkillHomes(userHome)
            .filter { it.exists() }
            .forEach { internalHome ->
                installSkillToDir(
                    sourceSkillDir = File(bundledSkillsHome, SKILL_NAME),
                    targetSkillDir = File(internalHome, "skills/$SKILL_NAME"),
                )
            }
        if (client == InstallClient.CODEX) {
            CodexPermissionRuleInstaller.install(
                CodexAgentInstaller.resolvePermissionRuleTargets(userHome, SKILL_NAME),
                logger,
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
        val agent = InstallAgents.resolveAgentInstaller(client)
        val primary = File(skillRootDirNoCreate(agent, userHome), SKILL_NAME)
        val internals = agent.resolveInternalSkillHomes(userHome)
            .filter { it.exists() }
            .map { File(it, "skills/$SKILL_NAME") }
        return listOf(primary) + internals
    }

    private fun skillRootForClient(agent: IAgentInstaller, userHome: File): File {
        return skillRootDirNoCreate(agent, userHome).also { it.mkdirs() }
    }

    private fun skillRootDirNoCreate(agent: IAgentInstaller, userHome: File): File {
        return agent.resolvePrimarySkillRoot(userHome)
    }

    fun ensureBundledSkillsHome(userHome: File): File {
        return withGlobalResourceLock("Prepare bundled Jugg skills", File(userHome, ".jugg")) {
            val bundledSkillsHome = File(userHome, ".jugg/skills")
            extractBundledSkills(targetDir = bundledSkillsHome)
            bundledSkillsHome
        }
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

    private fun Throwable.safeReason(): String {
        return (message ?: javaClass.simpleName).replace(Regex("\\s+"), "_")
    }

    /**
     * Installs the jugg CLI to ~/.jugg/bin by extracting the bundled scripts/ directory.
     * On macOS/Linux, also sets executable permissions and creates a symlink in ~/.local/bin.
     * Returns Result.success on success, Result.failure on error.
     */
    fun installCli(logger: Logger, userHome: File = File(System.getProperty("user.home"))): Result<Unit> {
        val windows = isWindows()
        val result = withGlobalResourceLock("Install Jugg CLI", File(userHome, ".jugg")) {
            runCatching {
                val binDir = File(userHome, ".jugg/bin")
                extractBundledScriptsTo(binDir)
                if (windows) {
                    normalizeWindowsCmdWrapper(binDir)
                } else {
                    setExecutable(binDir)
                    createSymlink(userHome, binDir)
                }
                binDir
            }
        }
        return result.mapCatching { binDir ->
            if (windows) addWindowsCliDirToUserPath(userHome, binDir)
            logger.info("[Install Jugg CLI] installed to ${binDir.path}")
        }
    }

    private fun extractBundledScriptsTo(binDir: File) {
        binDir.deleteRecursively()
        binDir.mkdirs()
        val stream = JuggSkillInstaller::class.java.classLoader.getResourceAsStream(SKILLS_BUNDLE_ZIP_RESOURCE)
            ?: throw FileNotFoundException("resource_not_found_$SKILLS_BUNDLE_ZIP_RESOURCE")
        val canonicalParent = binDir.canonicalPath + File.separator
        ZipInputStream(stream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.startsWith(BUNDLED_SCRIPTS_PREFIX) && !entry.isDirectory) {
                    val outFile = File(binDir, entry.name.removePrefix(BUNDLED_SCRIPTS_PREFIX))
                    if (!outFile.canonicalPath.startsWith(canonicalParent)) {
                        throw IOException("invalid_zip_entry_path")
                    }
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { output -> zip.copyTo(output) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (!File(binDir, "jugg.py").isFile) {
            throw FileNotFoundException("resource_missing_file_jugg.py")
        }
    }

    fun resolveHooksDir(userHome: File): File {
        return File(userHome, ".jugg/skills/hooks")
    }

    /**
     * Creates or removes ~/.jugg/skills/hooks/DISABLE_BLOCK to suppress command/stop block reminders.
     */
    fun setHookBlockDisabled(
        disabled: Boolean,
        logger: Logger,
        userHome: File = File(System.getProperty("user.home")),
    ): Result<Unit> {
        return withGlobalResourceLock("Update Jugg hook state", File(userHome, ".jugg")) {
            runCatching {
                val hooksDir = resolveHooksDir(userHome)
                hooksDir.mkdirs()
                val flagFile = File(hooksDir, HOOK_BLOCK_DISABLED_FLAG_FILE_NAME)
                if (disabled) {
                    if (!flagFile.exists()) {
                        flagFile.writeText("", StandardCharsets.UTF_8)
                    }
                    logger.info("[Install Jugg Hooks] block reminders disabled via ${flagFile.path}")
                    return@runCatching
                }
                if (flagFile.exists() && !flagFile.delete()) {
                    throw IOException("failed_to_delete_${flagFile.path}")
                }
                logger.info("[Install Jugg Hooks] block reminders enabled")
            }
        }
    }

    /**
     * Makes bundled hook scripts available from ~/.jugg/skills/hooks.
     */
    fun installHooks(logger: Logger, userHome: File = File(System.getProperty("user.home"))): Result<Unit> {
        return withGlobalResourceLock("Install Jugg hooks", File(userHome, ".jugg")) {
            runCatching {
                val bundledHooksDir = File(ensureBundledSkillsHome(userHome), BUNDLED_HOOKS_DIR)
                HOOK_SCRIPT_FILES.forEach { fileName ->
                    val sourceFile = File(bundledHooksDir, fileName)
                    if (!sourceFile.isFile) {
                        throw FileNotFoundException("source_file_not_found_${sourceFile.path}")
                    }
                }
                if (!isWindows()) {
                    HOOK_SCRIPT_FILES
                        .filter { it != "hook_common.py" }
                        .forEach { fileName ->
                            File(bundledHooksDir, fileName).takeIf { it.exists() }?.setExecutable(true, false)
                        }
                }
                logger.info("[Install Jugg Hooks] installed to ${bundledHooksDir.path}")
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
        val target = File(binDir, "jugg").toPath()
        // Remove existing symlink or file, then create new symlink
        if (symlinkFile.exists() || Files.isSymbolicLink(symlinkFile.toPath())) {
            symlinkFile.delete()
        }
        Files.createSymbolicLink(symlinkFile.toPath(), target)
    }

    private fun normalizeWindowsCmdWrapper(binDir: File) {
        val cmdFile = File(binDir, "jugg.cmd")
        if (!cmdFile.isFile) {
            throw FileNotFoundException("source_file_not_found_${cmdFile.path}")
        }
        val content = normalizeWindowsCmdContent(cmdFile.readText(StandardCharsets.UTF_8))
        if (content.any { it.code !in 0..127 }) {
            throw IOException("windows_cmd_wrapper_not_ascii")
        }
        cmdFile.writeText(content, StandardCharsets.US_ASCII)
    }

    private fun normalizeWindowsCmdContent(content: String): String {
        return content
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .split("\n")
            .joinToString("\r\n")
    }

    private fun addWindowsCliDirToUserPath(userHome: File, binDir: File) {
        if (!isCurrentUserHome(userHome)) {
            return
        }
        WindowsUserPathUpdater.prependIfMissing(binDir.canonicalPath)
    }

    private fun isCurrentUserHome(userHome: File): Boolean {
        return userHome.canonicalFile == File(System.getProperty("user.home")).canonicalFile
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

    fun hookClients(): Set<InstallClient> {
        return clients.ifEmpty { setOf(InstallClient.CLAUDE) }
    }

    fun skillClients(): Set<InstallClient> = clients

    val requiresHooks: Boolean get() = installHooks
    val requiresCli: Boolean get() = installCli || requiresHooks
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
