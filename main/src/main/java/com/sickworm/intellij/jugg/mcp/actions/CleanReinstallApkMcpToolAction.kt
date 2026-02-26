package com.sickworm.intellij.jugg.mcp.actions

import com.sickworm.intellij.jugg.compiler.ForceGradleCompileHelper
import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.mcp.McpToolResult

/**
 * CleanReinstallApkMcpToolAction implements MCP tool `clean_reinstall_apk` and converts request arguments into tool execution and MCP result payloads.
 */
class CleanReinstallApkMcpToolAction : McpToolAction {
    override val toolName: String = "clean_reinstall_apk"

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Clear App data, redeploy incremental changes, and reinstall APK.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
            ),
            required = listOf("projectDir"),
            additionalProperties = false,
        ),
        outputSchema = McpToolSchemas.baseOutputSchema,
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        return cleanReinstallAction(runtime)
    }

    private fun cleanReinstallAction(runtime: IMcpRuntime): McpToolResult {
        ForceGradleCompileHelper.isCleanAndReinstallNextTime = true
        return CompileAndDeployMcpToolAction.deployAction(runtime, "clean_reinstall_apk")
    }

}
