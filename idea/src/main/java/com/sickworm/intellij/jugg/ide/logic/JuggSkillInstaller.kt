package com.sickworm.intellij.jugg.ide.logic

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

/**
 * Installs Jugg skill and MCP config for supported clients without relying on shell scripts.
 */
object JuggSkillInstaller {

    private const val SKILL_NAME = "jugg-android-dev-loop"
    private const val SKILL_ZIP_RESOURCE = "docs/skills/jugg-android-dev-loop.zip"
    private const val MCP_SERVER_NAME = "jugg-mcp"
    private const val MCP_ENDPOINT = "http://localhost:12320/jugg-mcp"

    /**
     * Installs selected clients by writing config files and extracting bundled skill files.
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

        val mcpStatus = runCatching {
            installMcp(client, userHome)
            "ok"
        }.getOrElse { error ->
            reasons.add("MCP:${error.safeReason()}")
            "fail"
        }
        logger.info("[Install Jugg MCP] projectDir=${projectDir.path}, agent=${client.cliName}, skill=$skillStatus, mcp=$mcpStatus")
        return InstallAgentResult(client.cliName, skillStatus, mcpStatus, reasons.distinct())
    }

    private fun installSkill(client: InstallClient, userHome: File) {
        val skillRoot = skillRootForClient(client, userHome)
        val skillDir = File(skillRoot, SKILL_NAME)
        if (skillDir.exists()) {
            skillDir.deleteRecursively()
        }
        skillDir.mkdirs()
        unzipSkillResource(skillDir)
    }

    private fun installMcp(client: InstallClient, userHome: File) {
        when (client) {
            InstallClient.CODEX -> writeCodexMcpConfig(userHome, MCP_SERVER_NAME, MCP_ENDPOINT)
            InstallClient.GEMINI -> writeGeminiMcpConfig(userHome, MCP_SERVER_NAME, MCP_ENDPOINT)
            InstallClient.CLAUDE -> writeClaudeMcpConfig(userHome, MCP_SERVER_NAME, MCP_ENDPOINT)
        }
    }

    private fun skillRootForClient(client: InstallClient, userHome: File): File {
        return when (client) {
            InstallClient.CODEX -> File(codexHomeDir(userHome), "skills")
            InstallClient.CLAUDE -> File(claudeHomeDir(userHome), "skills")
            InstallClient.GEMINI -> File(geminiHomeDir(userHome), "skills")
        }.also { it.mkdirs() }
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

    private fun writeCodexMcpConfig(userHome: File, serverName: String, endpoint: String) {
        val configFile = File(codexHomeDir(userHome), "config.toml")
        configFile.parentFile?.mkdirs()
        val content = if (configFile.exists()) configFile.readText(StandardCharsets.UTF_8) else ""
        val sectionRegex = Regex("""(?ms)^\[mcp_servers\."$serverName"]\n(?:.+\n)*?(?=^\[|\z)""")
        val newSection = "[mcp_servers.\"$serverName\"]\nurl = \"$endpoint\"\n"
        val normalized = if (sectionRegex.containsMatchIn(content)) {
            content.replace(sectionRegex, newSection)
        } else {
            val suffix = if (content.isBlank() || content.endsWith("\n")) "" else "\n"
            content + suffix + newSection
        }
        configFile.writeText(normalized, StandardCharsets.UTF_8)
    }

    private fun writeGeminiMcpConfig(userHome: File, serverName: String, endpoint: String) {
        val settingsFile = File(geminiHomeDir(userHome), "settings.json")
        upsertJsonMcpServer(settingsFile, serverName, JsonObject().apply { addProperty("httpUrl", endpoint) })
    }

    private fun writeClaudeMcpConfig(userHome: File, serverName: String, endpoint: String) {
        val settingsFile = File(userHome, ".claude.json")
        upsertJsonMcpServer(settingsFile, serverName, JsonObject().apply { addProperty("httpUrl", endpoint) })
    }

    private fun upsertJsonMcpServer(settingsFile: File, serverName: String, serverConfig: JsonObject) {
        settingsFile.parentFile?.mkdirs()
        val root = if (settingsFile.exists() && settingsFile.readText(StandardCharsets.UTF_8).isNotBlank()) {
            JsonParser.parseString(settingsFile.readText(StandardCharsets.UTF_8)).asJsonObject
        } else {
            JsonObject()
        }
        val mcpServers = if (root.has("mcpServers") && root.get("mcpServers").isJsonObject) {
            root.getAsJsonObject("mcpServers")
        } else {
            JsonObject().also { root.add("mcpServers", it) }
        }
        mcpServers.add(serverName, serverConfig)
        settingsFile.writeText(root.toString() + "\n", StandardCharsets.UTF_8)
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
}

data class InstallAgentResult(
    val agent: String,
    val skillStatus: String?,
    val mcpStatus: String?,
    val reasons: List<String>,
)

data class InstallSummary(
    val results: List<InstallAgentResult>,
) {
    val isAllSuccess: Boolean
        get() = results.isNotEmpty() && results.all { it.skillStatus == "ok" && it.mcpStatus == "ok" }

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
            "agent=${result.agent} skill=${result.skillStatus ?: "missing"} mcp=${result.mcpStatus ?: "missing"}$reasonText"
        }
    }
}
