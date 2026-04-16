@file:Suppress("SameParameterValue")

package com.sickworm.intellij.jugg.ide.logic

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
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

    private fun skillRootForClient(client: InstallClient, userHome: File): File {
        return when (client) {
            InstallClient.CODEX -> File(codexHomeDir(userHome), "skills")
            InstallClient.CLAUDE -> File(claudeHomeDir(userHome), "skills")
            InstallClient.GEMINI -> File(geminiHomeDir(userHome), "skills")
            InstallClient.CODEBUDDY -> File(userHome, ".codebuddy/skills")
        }.also { it.mkdirs() }
    }

    private fun internalDirsForClient(client: InstallClient, userHome: File): List<File> {
        return when (client) {
            InstallClient.CODEX -> listOf(File(userHome, ".codex-internal"))
            InstallClient.CLAUDE -> listOf(File(userHome, ".claude-internal"))
            InstallClient.GEMINI -> listOf(File(userHome, ".gemini-internal"))
            InstallClient.CODEBUDDY -> emptyList()
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
}

enum class InstallClient(val cliName: String) {
    CODEX("codex"),
    CLAUDE("claude"),
    GEMINI("gemini"),
    CODEBUDDY("codebuddy"),
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
