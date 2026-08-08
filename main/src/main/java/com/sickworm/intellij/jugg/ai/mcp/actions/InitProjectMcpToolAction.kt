package com.sickworm.intellij.jugg.ai.mcp.actions

import com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.ai.mcp.McpErrorCode
import com.sickworm.intellij.jugg.ai.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.ai.mcp.McpJsonSchemaProperty
import com.sickworm.intellij.jugg.ai.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.ai.mcp.McpToolResult
import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus

/** Initializes the run configuration required by standalone compile commands. */
class InitProjectMcpToolAction : McpToolAction {
    override val toolName: String = McpToolActionRegistry.ToolNames.INIT

    override val definition = McpToolDefinition(
        name = toolName,
        description = "Initialize the standalone Jugg run configuration from Gradle project information.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf("projectDir" to McpToolSchemas.projectDirProperty),
            required = listOf("projectDir"),
            additionalProperties = false,
        ),
        outputSchema = McpToolSchemas.baseOutputSchema.copy(
            properties = McpToolSchemas.baseOutputSchema.properties + mapOf(
                "data" to McpJsonSchemaProperty(type = "object", additionalProperties = true),
            ),
        ),
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        val result = runtime.initializeProject()
        val data = mutableMapOf<String, Any>()
        result.configurationId?.let { data["configurationId"] = it }
        result.configurationName?.let { data["configurationName"] = it }
        result.compileCommand?.let { data["compileCommand"] = it }
        return McpToolResult(
            status = if (result.isSuccess) McpToolStatus.OK else McpToolStatus.ERROR,
            message = result.message,
            data = data,
            artifacts = emptyList(),
            errorCode = if (result.isSuccess) null else McpErrorCode.INTERNAL_ERROR,
        )
    }
}
