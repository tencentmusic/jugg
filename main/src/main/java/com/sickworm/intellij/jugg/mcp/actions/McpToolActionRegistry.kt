package com.sickworm.intellij.jugg.mcp.actions

/**
 * McpToolActionRegistry registers and looks up mcp tool handlers.
 */
class McpToolActionRegistry(
    actions: List<McpToolAction> = defaultActions(),
) {
    private val actionByName: Map<String, McpToolAction> = actions.associateBy { it.toolName }

    fun listActions(): List<McpToolAction> {
        return actionByName.values.toList()
    }

    fun getAction(toolName: String): McpToolAction? {
        return actionByName[toolName]
    }

    fun hasAction(toolName: String): Boolean {
        return actionByName.containsKey(toolName)
    }

    companion object {
        fun defaultActions(): List<McpToolAction> {
            return listOf(
                ListProjectsMcpToolAction(),
                RestartAppMcpToolAction(),
                CompileOnlyMcpToolAction(),
                CompileAndDeployMcpToolAction(),
                CleanReinstallApkMcpToolAction(),
                ForceGradleCompileMcpToolAction(),
                GetCompileStatusMcpToolAction(),
                RequestRemoteSshInfoMcpToolAction(),
                DeviceListMcpToolAction(),
                ScreenshotMcpToolAction(),
                StartRecordMcpToolAction(),
                StopRecordMcpToolAction(),
                LayoutDumpMcpToolAction(),
                LayoutVerifyMcpToolAction(),
                ActivityStackMcpToolAction(),
                CrashReportMcpToolAction(),
                TapMcpToolAction(),
            )
        }
    }
}
