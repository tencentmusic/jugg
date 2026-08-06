package com.sickworm.intellij.jugg.cmdline.standalone

import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfile
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.ai.mcp.McpJsonRpcRequest
import com.sickworm.intellij.jugg.ai.mcp.McpJsonRpcResponse
import com.sickworm.intellij.jugg.ai.mcp.McpToolInvoker
import com.sickworm.intellij.jugg.ai.mcp.McpToolRegistry
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.ForceGradleCompileHelper
import com.sickworm.intellij.jugg.compiler.GradleCompileExecutionResult
import com.sickworm.intellij.jugg.compiler.RemoteSshInfoResult
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
import com.sickworm.intellij.jugg.deploy.run.IDeployHost
import com.sickworm.intellij.jugg.deploy.run.StandaloneDeviceManager
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.logic.IJuggConfigurationRunner
import com.sickworm.intellij.jugg.ide.logic.JuggRunInvocationResult
import com.sickworm.intellij.jugg.project.runtime.IHostTaskExecutor
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import com.sickworm.intellij.jugg.project.runtime.RuntimeInfo
import com.sickworm.intellij.jugg.project.runtime.RuntimeOwnerChangeEvent
import com.sickworm.intellij.jugg.project.runtime.TaskRunnerManager
import com.sickworm.intellij.jugg.server.JuggServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File

/** Owns the standalone MCP and project-lock lifecycle for one project. */
class StandaloneProjectRuntime internal constructor(
    projectDirectory: File,
    private val runtimeInfo: RuntimeInfo,
    private val activity: StandaloneDaemonActivity,
    toolRegistry: McpToolRegistry,
) : IMcpRuntime, AutoCloseable {
    private val projectFile = projectDirectory.canonicalFile
    private val pathManager = JuggPathManager(projectFile)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val standaloneDeployStateManager = StandaloneDeployStateManager()
    private var standaloneDeviceManager: StandaloneDeviceManager? = null
    private var standaloneDeployEnvironment: IDeployHost? = null

    override val projectDir: String = projectFile.path
    override val logger: Logger = Logger.getInstance("StandaloneProjectRuntime")
    override val deployTargetManager: IDeployTargetManager = EmptyDeployTargetManager
    override val deployStateManager: com.sickworm.intellij.jugg.deploy.IDeployStateManager = standaloneDeployStateManager
    override val deployFileManager: DeployFileManager? = null
    override val forceGradleCompileHelper: ForceGradleCompileHelper = UnsupportedGradleCompileHelper
    override val juggConfigurationRunner: IJuggConfigurationRunner = StandaloneConfigurationRunner(activity)
    override val incrementalCompileFallbackChecker: com.sickworm.intellij.jugg.compiler.IIncrementalCompileFallbackChecker? = null

    private val juggServer = JuggServer(projectFile.name, pathManager, scope, runtimeInfo, logger)
    private val taskRunnerManager = TaskRunnerManager(
        logger = logger,
        deployStateManager = standaloneDeployStateManager,
        juggServer = juggServer,
        hostTaskExecutor = ImmediateHostTaskExecutor,
        pathManager = pathManager,
        runtimeType = runtimeInfo.runtimeType,
        runtimeVersion = runtimeInfo.runtimeVersion,
        coroutineScope = scope,
    )
    private val invoker = McpToolInvoker(projectDir, this, toolRegistry)

    val ownerChange: RuntimeOwnerChangeEvent?

    init {
        activity.beginProjectWrite()
        try {
            taskRunnerManager.runProjectWriteLocked("Initialize standalone runtime") {}
            ownerChange = taskRunnerManager.consumeRuntimeOwnerChange()
        } finally {
            activity.endProjectWrite()
        }
    }

    fun invokeMcp(request: McpJsonRpcRequest): McpJsonRpcResponse {
        return invoker.invokeMcp(request)
    }

    internal fun deployEnvironment(): IDeployHost {
        standaloneDeployEnvironment?.let { return it }
        val deviceManager = standaloneDeviceManager ?: StandaloneDeviceManager(resolveAdb()).also {
            standaloneDeviceManager = it
        }
        return StandaloneDeployEnvironment(deviceManager, runtimeInfo.runtimeVersion, logger).also {
            standaloneDeployEnvironment = it
        }
    }

    override fun refreshChangedFilesForStatus() = Unit

    override fun isAppReadyDeploy(): Boolean {
        return standaloneDeployStateManager.updateDeployState().isReadyDeploy
    }

    override fun close() {
        standaloneDeviceManager?.close()
        taskRunnerManager.dispose()
        scope.cancel()
    }

    private fun resolveAdb(): File {
        val androidHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        if (!androidHome.isNullOrBlank()) {
            val platformTools = File(androidHome, "platform-tools")
            listOf(File(platformTools, "adb"), File(platformTools, "adb.exe")).firstOrNull(File::isFile)?.let { return it }
        }
        return File("adb")
    }
}

private object EmptyDeployTargetManager : IDeployTargetManager {
    override fun setApks(apks: List<ApkInfo>) = Unit
    override fun getApks(): List<ApkInfo> = emptyList()
    override fun getSelectedDevices(): List<IDevice> = emptyList()
    override fun getConnectedDevices(): List<IDevice> = emptyList()
    override fun startApp(device: IDevice): Boolean = false
    override fun restartApp(device: IDevice): Boolean = false
    override fun stopApp(device: IDevice): Boolean = false
    override fun isAppForeground(device: IDevice): Boolean = false
    override fun getPackageName(): String = ""
}

private class StandaloneDeployStateManager : com.sickworm.intellij.jugg.deploy.IDeployStateManager {
    override val deployState = com.sickworm.intellij.jugg.deploy.JuggDeployState(
        com.sickworm.intellij.jugg.deploy.JuggDeployState.State.READY_FULL_COMPILE,
        "standalone runtime is ready for Gradle baseline",
        com.sickworm.intellij.jugg.deploy.run.IdeDeployState.ok,
    )
    override var isBuildFileChanged: Boolean = false
    override var whatBuildFileChanged: String = ""
    override var isInitializingIncrementalCompile: Boolean = false
    override fun updateDeployState() = deployState
    override fun getDeployState(device: IDevice) = deployState
    override fun beginFileProcessing() = Unit
    override fun endFileProcessing() = Unit
    override fun hasPendingFileProcessing(): Boolean = false
    override fun waitForPendingFileProcessing(timeoutMs: Long) = com.sickworm.intellij.jugg.deploy.FileProcessingWaitResult(false, 0, 0L, 0)
}

private object ImmediateHostTaskExecutor : IHostTaskExecutor {
    override val isOnEdt: Boolean = false

    override fun submit(title: String, cancelText: String, showIndicator: Boolean, action: Runnable) {
        action.run()
    }
}

private object UnsupportedGradleCompileHelper : ForceGradleCompileHelper() {
    override fun executeGradleCompile(autoConfirm: Boolean, useCleanAndReinstall: Boolean) {
        throw UnsupportedOperationException("Standalone Gradle build is not available before step 11")
    }

    override fun executeGradleCompileBlocking(autoConfirm: Boolean, useCleanAndReinstall: Boolean): GradleCompileExecutionResult {
        return GradleCompileExecutionResult("failed", "Standalone Gradle build is not available before step 11", false, false)
    }

    override fun resolveExecutionType(): String = "local"

    override fun requestRemoteSshInfo(requestedBy: String, reason: String): RemoteSshInfoResult {
        return RemoteSshInfoResult(false, "Standalone remote SSH is not available")
    }
}

private class StandaloneConfigurationRunner(
    private val activity: StandaloneDaemonActivity,
) : IJuggConfigurationRunner {
    override val isCompiling: Boolean
        get() = activity.isCompiling

    override fun runTask(
        options: JuggGradleCompileOptions,
        compileUiHandler: CompileUiHandler,
        executor: Executor?,
        runProfile: RunProfile?,
        androidTestRunSpec: AndroidTestRunSpec?,
    ): ExecutionResult {
        throw UnsupportedOperationException("Standalone compile is not available before step 11")
    }

    override fun forceReInstallNextTime() = Unit

    override fun runFirstConfiguration(
        isRpcMode: Boolean,
        isSkipDeploy: Boolean,
        isAlwaysRestartApp: Boolean,
    ): JuggRunInvocationResult {
        return JuggRunInvocationResult(false, errorMessage = "Standalone compile and deploy are not available before step 11")
    }

    override fun runFirstConfigurationWithSpec(
        isRpcMode: Boolean,
        isSkipDeploy: Boolean,
        isAlwaysRestartApp: Boolean,
        androidTestRunSpec: AndroidTestRunSpec?,
        buildTargetOverride: BuildTarget?,
    ): JuggRunInvocationResult {
        return runFirstConfiguration(isRpcMode, isSkipDeploy, isAlwaysRestartApp)
    }
}
