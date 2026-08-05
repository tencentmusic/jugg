package com.sickworm.intellij.jugg.cmdline.standalone

import com.sickworm.intellij.jugg.ai.mcp.McpToolRegistry
import com.sickworm.intellij.jugg.project.runtime.RuntimeInfo
import java.io.File

/** Builds one concrete standalone project runtime from host-neutral shared components. */
class StandaloneJuggRuntimeAssembler(
    private val runtimeInfo: RuntimeInfo,
    private val activity: StandaloneDaemonActivity,
    private val toolRegistry: McpToolRegistry,
) {
    fun create(projectDir: File): StandaloneProjectRuntime {
        return StandaloneProjectRuntime(projectDir, runtimeInfo, activity, toolRegistry)
    }
}
