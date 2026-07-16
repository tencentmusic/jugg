package com.sickworm.intellij.jugg.ai.mcp.actions

import com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.ai.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.ai.mcp.McpJsonSchemaProperty
import com.sickworm.intellij.jugg.ai.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.ai.mcp.McpToolResult
import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.runtime.PluginInfoReader

/**
 * VersionMcpToolAction implements MCP tool `version`.
 *
 * Iterates over all initialized projects, reads the plugin version from each, then returns:
 * - a single `pluginVersion` when all projects report the same version, or
 * - the highest version as `pluginVersion` plus a per-project `projects` map when versions differ.
 */
class VersionMcpToolAction : McpToolAction, GlobalMcpToolAction {
    override val toolName: String = McpToolActionRegistry.ToolNames.VERSION

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Return the Jugg plugin version for all initialized projects. " +
            "Returns a single version when all projects share the same version, " +
            "or the highest version plus a per-project map when versions differ.",
        inputSchema = McpJsonSchemaObject(
            description = "No arguments required.",
            properties = emptyMap(),
            additionalProperties = false,
        ),
        outputSchema = McpToolSchemas.baseOutputSchema.copy(
            properties = McpToolSchemas.baseOutputSchema.properties + mapOf(
                "data" to McpJsonSchemaProperty(
                    type = "object",
                    properties = mapOf(
                        "pluginVersion" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Highest plugin version across all projects (or the unified version).",
                        ),
                        "projects" to McpJsonSchemaProperty(
                            type = "object",
                            description = "Per-project version map (projectDir -> version). " +
                                "Only present when projects have differing versions.",
                            additionalProperties = true,
                        ),
                    ),
                    required = listOf("pluginVersion"),
                    additionalProperties = false,
                )
            )
        ),
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        return versionAction()
    }

    override fun executeGlobal(): McpToolResult = versionAction()

    fun versionAction(): McpToolResult {
        val projectDirs = PlatformApi.getInitializedProjectDirs()

        val versionByProject: Map<String, String> = projectDirs.associate { dir ->
            dir.path to PluginInfoReader.getPluginVersion()
        }

        val data: Map<String, Any> = buildResultData(versionByProject)

        return McpToolResult(
            status = McpToolStatus.OK,
            message = "version executed successfully.",
            data = data,
            artifacts = emptyList(),
            errorCode = null,
        )
    }

    private fun buildResultData(versionByProject: Map<String, String>): Map<String, Any> {
        if (versionByProject.isEmpty()) {
            return mapOf("pluginVersion" to PluginInfoReader.getPluginVersion())
        }

        val distinctVersions = versionByProject.values.toSet()
        if (distinctVersions.size == 1) {
            return mapOf("pluginVersion" to distinctVersions.first())
        }

        val highestVersion = distinctVersions.maxWithOrNull(::compareVersions) ?: "unknown"
        return mapOf(
            "pluginVersion" to highestVersion,
            "projects" to versionByProject,
        )
    }

    /**
     * Compares two version strings using semver-style numeric segment comparison.
     * Falls back to lexicographic ordering for non-numeric segments.
     */
    private fun compareVersions(a: String, b: String): Int {
        val partsA = a.split(".")
        val partsB = b.split(".")
        val maxLen = maxOf(partsA.size, partsB.size)
        for (i in 0 until maxLen) {
            val numA = partsA.getOrNull(i)?.toIntOrNull() ?: 0
            val numB = partsB.getOrNull(i)?.toIntOrNull() ?: 0
            val cmp = numA.compareTo(numB)
            if (cmp != 0) return cmp
        }
        return 0
    }
}
