package com.sickworm.intellij.jugg.ide.logic

import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * Executes local installer script and summarizes MCP/skill installation results for each client.
 */
object JuggSkillInstaller {

    private val statusPattern = Regex("^(SKILL|MCP) agent=(\\S+) status=(\\S+) file=(\\S+)(?: reason=(\\S+))?$")

    fun buildCommand(scriptPath: String, client: InstallClient): List<String> {
        return listOf(
            "bash",
            scriptPath,
            "--with-mcp",
            "--mcp-client",
            client.cliName,
        )
    }

    /**
     * Runs installation scripts for selected clients and merges all output into one summary.
     */
    fun install(projectDir: File, selectedClients: Set<InstallClient>, logger: Logger): InstallSummary {
        if (selectedClients.isEmpty()) {
            return InstallSummary(emptyList())
        }
        val scriptFile = File(projectDir, "docs/skills/install/install_mcp_and_skill.sh")
        if (!scriptFile.exists()) {
            val missing = selectedClients.map {
                InstallAgentResult(
                    agent = it.cliName,
                    skillStatus = "fail",
                    mcpStatus = "fail",
                    reasons = listOf("install_script_not_found"),
                )
            }
            return InstallSummary(missing)
        }

        val allLines = mutableListOf<String>()
        selectedClients.forEach { client ->
            val command = buildCommand(scriptFile.path, client)
            logger.info("[Install Jugg MCP] run command for ${client.cliName}: ${command.joinToString(" ")}")
            val result = runCommand(command, projectDir)
            allLines.addAll(result.outputLines)
            if (result.exitCode != 0) {
                allLines.add("SKILL agent=${client.cliName} status=fail file=${scriptFile.path} reason=script_exit_${result.exitCode}")
                allLines.add("MCP agent=${client.cliName} status=fail file=${scriptFile.path} reason=script_exit_${result.exitCode}")
            }
        }
        return summarize(allLines, selectedClients)
    }

    fun summarize(lines: List<String>, selectedClients: Set<InstallClient>): InstallSummary {
        val details = selectedClients.associate {
            it.cliName to MutableInstallAgentResult(it.cliName)
        }.toMutableMap()

        lines.forEach { line ->
            val match = statusPattern.matchEntire(line) ?: return@forEach
            val scope = match.groupValues[1]
            val agent = match.groupValues[2]
            val status = match.groupValues[3]
            val reason = match.groupValues.getOrNull(5).orEmpty()
            val target = details.getOrPut(agent) { MutableInstallAgentResult(agent) }
            if (scope == "SKILL") {
                target.skillStatus = status
            } else {
                target.mcpStatus = status
            }
            if (reason.isNotEmpty()) {
                target.reasons.add("$scope:$reason")
            }
        }

        val resultList = details.values.map { detail ->
            if (detail.skillStatus != "ok") {
                detail.reasons.add("SKILL:${detail.skillStatus ?: "missing"}")
            }
            if (detail.mcpStatus != "ok") {
                detail.reasons.add("MCP:${detail.mcpStatus ?: "missing"}")
            }
            detail.toImmutable()
        }.sortedBy { it.agent }

        return InstallSummary(resultList)
    }

    private fun runCommand(command: List<String>, workingDir: File): CommandResult {
        val process = ProcessBuilder(command)
            .directory(workingDir)
            .redirectErrorStream(true)
            .start()
        val output = mutableListOf<String>()
        process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { output.add(it) }
        }
        val code = process.waitFor()
        return CommandResult(code, output)
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

private data class MutableInstallAgentResult(
    val agent: String,
    var skillStatus: String? = null,
    var mcpStatus: String? = null,
    val reasons: MutableList<String> = mutableListOf(),
) {
    fun toImmutable(): InstallAgentResult {
        return InstallAgentResult(
            agent = agent,
            skillStatus = skillStatus,
            mcpStatus = mcpStatus,
            reasons = reasons.toList().distinct(),
        )
    }
}

private data class CommandResult(
    val exitCode: Int,
    val outputLines: List<String>,
)
