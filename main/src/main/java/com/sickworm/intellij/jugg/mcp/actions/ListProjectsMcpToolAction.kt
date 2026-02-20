package com.sickworm.intellij.jugg.mcp.actions

import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaProperty
import com.sickworm.intellij.jugg.mcp.McpProjectInfo
import com.sickworm.intellij.jugg.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.mcp.McpToolResult
import com.sickworm.intellij.jugg.mcp.McpToolStatus
import com.sickworm.intellij.jugg.platform.PlatformApi

/**
 * ListProjectsMcpToolAction implements MCP tool `list_projects` and converts request arguments into tool execution and MCP result payloads.
 */
class ListProjectsMcpToolAction : McpToolAction {
    override val toolName: String = "list_projects"

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "List projects initialized in this IDE process. Use when you need a valid projectDir before calling other tools. Avoid when projectDir is already known. Side effects: none.",
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
                                ),
                                required = listOf("projectDir", "initialized"),
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

    fun listProjectsAction(): McpToolResult {
        return McpToolResult(
            status = McpToolStatus.OK,
            message = "list_projects executed successfully.",
            data = mapOf(
                "projects" to PlatformApi.getInitializedProjectDirs().map {
                    McpProjectInfo(projectDir = it.path, initialized = true)
                }
            ),
            artifacts = emptyList(),
            errorCode = null,
        )
    }
}
