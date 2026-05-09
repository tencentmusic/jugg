package com.sickworm.intellij.jugg.ai.mcp.actions

import com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.ai.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.ai.mcp.McpJsonSchemaProperty
import com.sickworm.intellij.jugg.ai.mcp.McpProjectInfo
import com.sickworm.intellij.jugg.ai.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.ai.mcp.McpToolResult
import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus
import com.sickworm.intellij.jugg.deploy.FullBuildInfoSerializer
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.JuggPathManager
import java.io.File

/**
 * ListProjectsMcpToolAction implements MCP tool `list-projects` and converts request arguments into tool execution and MCP result payloads.
 */
class ListProjectsMcpToolAction : McpToolAction, GlobalMcpToolAction {
    override val toolName: String = McpToolActionRegistry.ToolNames.LIST_PROJECTS

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "List projects initialized in current IDE process.",
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
                        "projects" to McpJsonSchemaProperty(
                            type = "array",
                            items = McpJsonSchemaProperty(
                                type = "object",
                                properties = mapOf(
                                    "projectDir" to McpJsonSchemaProperty(type = "string"),
                                    "initialized" to McpJsonSchemaProperty(type = "boolean"),
                                    "hasCompiledBefore" to McpJsonSchemaProperty(
                                        type = "boolean",
                                        description = "True when Jugg has persisted at least one full-build baseline for this project.",
                                    ),
                                ),
                                required = listOf("projectDir", "initialized", "hasCompiledBefore"),
                                additionalProperties = false,
                            )
                        )
                    ),
                    required = listOf("projects"),
                    additionalProperties = false,
                )
            )
        )
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        return listProjectsAction()
    }

    override fun executeGlobal(): McpToolResult = listProjectsAction()

    fun listProjectsAction(): McpToolResult {
        return McpToolResult(
            status = McpToolStatus.OK,
            message = "list-projects executed successfully.",
            data = mapOf(
                "projects" to PlatformApi.getInitializedProjectDirs().map {
                    McpProjectInfo(
                        projectDir = it.path,
                        initialized = true,
                        hasCompiledBefore = hasCompiledBefore(it),
                    )
                }
            ),
            artifacts = emptyList(),
            errorCode = null,
        )
    }

    private fun hasCompiledBefore(projectDir: File): Boolean {
        val pathManager = JuggPathManager(projectDir)
        val fullBuildInfoFile = File(pathManager.compileContextDbDir, "full_build_info.json")
        if (!fullBuildInfoFile.exists()) {
            return false
        }
        return runCatching {
            FullBuildInfoSerializer().deserialize(fullBuildInfoFile.readText(Charsets.UTF_8))
            true
        }.getOrDefault(false)
    }
}
