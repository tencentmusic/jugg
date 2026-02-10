package com.sickworm.intellij.jugg.mcp

import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.ForceGradleCompileHelper
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.ide.logic.IJuggConfigurationRunner

interface IMcpRuntime {
    val project: Project
    val deployTargetManager: IDeployTargetManager
    val forceGradleCompileHelper: ForceGradleCompileHelper
    val juggConfigurationRunner: IJuggConfigurationRunner
}

object McpRuntimeHolder {
    @Volatile
    var runtime: IMcpRuntime? = null
}

object DumbMcpRuntime : IMcpRuntime {
    override val project: Project = error("Dumb MCP runtime is not initialized.")
    override val deployTargetManager: IDeployTargetManager = error("Dumb MCP runtime is not initialized.")
    override val forceGradleCompileHelper: ForceGradleCompileHelper = error("Dumb MCP runtime is not initialized.")
    override val juggConfigurationRunner: IJuggConfigurationRunner = error("Dumb MCP runtime is not initialized.")
}
