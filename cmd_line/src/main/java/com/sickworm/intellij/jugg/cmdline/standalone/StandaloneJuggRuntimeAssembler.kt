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
        val resources = StandaloneProjectResources()
        return try {
            StandaloneProjectRuntime(projectDir, runtimeInfo, activity, toolRegistry, resources)
        } catch (error: Throwable) {
            resources.cleanup().forEach(error::addSuppressed)
            throw error
        }
    }
}

/** Tracks project resources during construction so partial runtimes can be closed safely. */
internal class StandaloneProjectResources {
    private val cleanupActions = mutableListOf<() -> Unit>()
    private var closed = false

    fun register(action: () -> Unit) {
        val closeNow = synchronized(this) {
            if (closed) {
                true
            } else {
                cleanupActions.add(action)
                false
            }
        }
        if (closeNow) action()
    }

    fun cleanup(): List<Throwable> {
        val actions = synchronized(this) {
            if (closed) return emptyList()
            closed = true
            cleanupActions.asReversed().toList().also { cleanupActions.clear() }
        }
        return actions.mapNotNull { action -> runCatching(action).exceptionOrNull() }
    }
}
