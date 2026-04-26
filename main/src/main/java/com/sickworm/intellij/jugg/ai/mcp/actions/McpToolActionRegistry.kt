package com.sickworm.intellij.jugg.ai.mcp.actions

/**
 * McpToolActionRegistry registers and looks up mcp tool handlers.
 */
class McpToolActionRegistry(
    actions: List<McpToolAction> = defaultActions(),
) {

    /**
     * Canonical MCP tool name constants. All references to tool name strings must use these
     * constants to prevent mismatches across registration, routing, and tests.
     */
    object ToolNames {
        const val LIST_PROJECTS = "list-projects"
        const val RESTART = "restart"
        const val COMPILE = "compile"
        const val DEPLOY = "deploy"
        const val REINSTALL = "clean-reinstall"
        const val GRADLE_BUILD = "gradle-build"
        const val GET_COMPILE_STATUS = "get-compile-status"
        const val SSH_INFO = "ssh-info"
        const val DEVICES = "devices"
        const val SCREENSHOT = "screenshot"
        const val RECORD_START = "record-start"
        const val RECORD_STOP = "record-stop"
        const val LAYOUT_DUMP = "layout-dump"
        const val LAYOUT_VERIFY = "layout-verify"
        const val VIEW_LOCATE = "view-locate"
        const val VIEW_INSPECT = "view-inspect"
        const val ACTIVITY_STACK = "activity-stack"
        const val TAP = "tap"
        const val GET_STATUS = "status"
        const val VERSION = "version"
        const val WAIT_LOGS = "wait-logs"
    }

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

        /**
         * Tools that do not require a projectDir argument.
         * Used by routing layers (McpRequestValidator, IdeaMcpRuntime, McpBaseInvoker) as the
         * single source of truth — add new global tools here instead of updating each router.
         */
        val noProjectDirTools: Set<String> = setOf(
            ToolNames.LIST_PROJECTS,
            ToolNames.VERSION,
        )

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
                LayoutDumpMcpToolAction(),
                LayoutVerifyMcpToolAction(),
                UiFindMcpToolAction(),
                EvalViewMcpToolAction(),
                ActivityStackMcpToolAction(),
                TapMcpToolAction(),
                GetStatusMcpToolAction(),
                VersionMcpToolAction(),
                WaitLogsMcpToolAction(),
            )
        }
    }
}
