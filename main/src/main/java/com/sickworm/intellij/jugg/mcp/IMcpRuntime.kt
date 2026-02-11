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

interface IMcpInvoker {
    fun invokeMcp(request: McpJsonRpcRequest): McpJsonRpcResponse
}

