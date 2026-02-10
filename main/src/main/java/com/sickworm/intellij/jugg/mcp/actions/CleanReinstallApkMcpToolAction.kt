package com.sickworm.intellij.jugg.mcp.actions

import com.sickworm.intellij.jugg.compiler.ForceGradleCompileHelper
import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.mcp.McpToolResult

class CleanReinstallApkMcpToolAction : McpToolAction {
    override val toolName: String = "clean_reinstall_apk"

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Uninstall app then perform a full Gradle build and reinstall APK. Clears app data including Jugg incremental patches stored in code_cache. Use when incremental deploy state is corrupted or signatures mismatch. Avoid for quick iteration due to slower execution. Side effects: uninstalls app (losing all app data), rebuilds from scratch, and reinstalls APK.",
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
