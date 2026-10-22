package com.sickworm.intellij.jugg.diagnostics

import com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient
import com.sickworm.intellij.jugg.gradle.compile.WindowsRsyncSetupException
import com.sickworm.intellij.jugg.ide.ui.WindowsRsyncSetupDialog
import com.android.ddmlib.CollectingOutputReceiver
import com.intellij.execution.filters.HyperlinkInfo
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.sickworm.intellij.jugg.ai.mcp.RunLogCollector
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.compiler.ui.RunResult
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.diagnostics.InvestigationSnapshotBuilder.Source
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.logic.IJuggConfigurationRunner
import com.sickworm.intellij.jugg.ide.ui.AskAiDialog
import com.sickworm.intellij.jugg.ide.ui.JuggControlPanelController
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.CompileContextManager
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.TaskRunnerManager
import com.sickworm.intellij.jugg.server.JuggServer
import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/** Collects local investigation evidence and connects the Running Panel and failed-task Ask AI entries. */
class AskAiInvestigation(
    private val project: Project,
    private val pathManager: JuggPathManager,
    private val deployTargetManager: IDeployTargetManager,
    private val compileContextManager: CompileContextManager,
    private val deployFileManager: DeployFileManager,
    private val deployHistoryManager: IDeployHistoryManager,
    private val taskRunnerManager: TaskRunnerManager,
    private val juggServer: JuggServer,
    private val controlPanelController: JuggControlPanelController,
    private val logger: Logger,
) {
    /** Adds failure preservation without changing the original handler's completion or cancellation contract. */
    fun wrapCompileUiHandler(
        delegate: CompileUiHandler,
        console: ConsoleView,
        options: JuggGradleCompileOptions,
        startedAt: Long,
    ): CompileUiHandler = AskAiCompileUiHandler(delegate, project, console, options, this,
        controlPanelController, startedAt)

    /** Collects a user-described problem and preserves existing evidence before exposing an Agent prompt. */
    fun askAi(runner: IJuggConfigurationRunner) {
        controlPanelController.recordUserAction("ASK AI")
        taskRunnerManager.runBackgroundSafe("Prepare ASK AI") {
            val devices = try {
                deployTargetManager.getSelectedDevices()
            } catch (e: Exception) {
                logger.warn("Cannot list ASK AI devices. Continue with local evidence.", e)
                emptyList()
            }
            SwingUtilities.invokeLater {
                if (project.isDisposed) return@invokeLater
                val dialog = AskAiDialog(project, devices.map { it.serialNumber to it.name })
                if (!dialog.showAndGet()) return@invokeLater
                saveInvestigation(runner, dialog.problemType, dialog.problemDescription, dialog.deviceSerials)
            }
        }
    }

    private fun saveInvestigation(runner: IJuggConfigurationRunner, problemType: String, description: String, deviceSerials: Set<String>) {
        taskRunnerManager.runTaskSafe("Save investigation evidence", {
            try {
                check(!runner.isCompiling) { "A Jugg task is running. Wait for it to finish and try ASK AI again." }
                val failures = mutableMapOf<String, String>()
                if (deviceSerials.isEmpty()) failures["logcat"] = "No device was selected; logcat was not collected."
                val deviceLogs = collectInvestigationLogcat(deviceSerials, failures)
                val environment = investigationEnvironment() + mapOf("requestedDeviceSerials" to deviceSerials,
                    "recentRuns" to controlPanelController.model.snapshot().recentRuns)
                val snapshot = buildInvestigation(problemType, description, environment, failures, "", deviceLogs)
                SwingUtilities.invokeLater {
                    if (!project.isDisposed) AskAiDialog(project, emptyList(), snapshot).show()
                }
            } catch (e: Exception) {
                showInvestigationFailure(e)
            }
        })
    }

    /** Called synchronously at the failed task boundary so a later Run cannot replace its local evidence. */
    fun preserveCompileFailure(
        options: JuggGradleCompileOptions,
        result: RunResult,
        startedAt: Long,
        taskOutput: String,
    ): InvestigationSnapshotBuilder.Snapshot? {
        return try {
            val environment = investigationEnvironment() + mapOf(
                "taskStartedAtEpochMs" to startedAt, "taskEndedAtEpochMs" to System.currentTimeMillis(),
                "compileMode" to if (result.isGradleCompile) "Gradle" else "Incremental",
                "compileCommand" to options.compileCommand, "buildTarget" to options.buildTarget.name,
            )
            buildInvestigation("Compilation failed", "Compilation failed. See the saved task output and Jugg logs.",
                environment, mapOf("logcat" to "Not collected for compilation failure"), taskOutput, emptyMap())
        } catch (e: Exception) {
            logger.warn("Cannot preserve ASK AI compilation evidence. Use ASK AI in the Running Panel to retry.", e)
            null
        }
    }

    private fun investigationEnvironment(): Map<String, Any?> = mapOf(
        "pluginVersion" to juggServer.version, "ideVersion" to PlatformApi.getIdeVersion(),
        "os" to System.getProperty("os.name"), "jvm" to System.getProperty("java.version"),
        "agpR8Classpath" to compileContextManager.compileContext.agpR8Classpath?.absolutePath,
        "isMinified" to compileContextManager.compileContext.isMinified,
        "packageName" to compileContextManager.compileContext.packageName,
        "fullBuildInfo" to deployHistoryManager.getFullBuildInfo(),
        "changedFiles" to deployFileManager.getUndeployedFiles().map { it.file.absolutePath },
        "overlayIds" to deployHistoryManager.lastDeployOverlayIds,
    )

    private fun collectInvestigationLogcat(serials: Set<String>, failures: MutableMap<String, String>): Map<String, String> {
        if (serials.isEmpty()) return emptyMap()
        val connected = try {
            deployTargetManager.getConnectedDevices().associateBy { it.serialNumber }
        } catch (e: Exception) {
            failures["devices"] = e.toString()
            logger.warn("Cannot query ASK AI devices. Preserve local evidence only.", e)
            return emptyMap()
        }
        return serials.mapIndexedNotNull { index, serial ->
            try {
                val device = connected[serial]
                check(device != null && device.isOnline) { "Device is offline or disconnected: $serial" }
                val command = "logcat -b all -d -v threadtime -t 20000"
                val receiver = CollectingOutputReceiver()
                device.executeShellCommand(command, receiver, 5L, TimeUnit.SECONDS)
                val content = receiver.output
                check(content.isNotBlank()) { "No logcat output was collected from $serial" }
                "device-$index" to "Device serial: $serial\nDevice: ${device.name}\nAndroid API: ${device.version.apiLevel}\n" +
                        "Collected at: ${java.time.OffsetDateTime.now()}\nCommand: $command\n$content"
            } catch (e: Exception) {
                failures["device:$serial"] = e.toString()
                logger.warn("Cannot collect ASK AI logcat for device $serial.", e)
                null
            }
        }.toMap()
    }

    private fun buildInvestigation(
        problemType: String,
        description: String,
        environment: Map<String, Any?>,
        failures: Map<String, String>,
        taskOutput: String,
        deviceLogs: Map<String, String>,
    ): InvestigationSnapshotBuilder.Snapshot {
        val collectionFailures = failures.toMutableMap()
        try {
            JuggLogger.flushProjectLog(project)
        } catch (e: Exception) {
            collectionFailures["logs-flush"] = e.toString()
            // The file writer cannot enqueue a warning on an interrupted thread; the manifest retains the failure.
            if (!Thread.currentThread().isInterrupted) {
                logger.warn("Cannot flush ASK AI logs. The saved log files may be incomplete.", e)
            }
        }
        return synchronized(deployFileManager) {
            val sources = investigationSources(collectionFailures)
            InvestigationSnapshotBuilder(pathManager.investigationDir, pathManager.projectDir,
                logger.getInstance("InvestigationSnapshotBuilder")).build(problemType, description, environment,
                sources, collectionFailures, taskOutput, deviceLogs)
        }
    }

    private fun investigationSources(failures: MutableMap<String, String>): List<Source> {
        val context = compileContextManager.compileContext
        val logs = pathManager.logDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith("compile_") && it.extension == "log" && !it.name.startsWith("compile_latest") }
            .sortedByDescending { it.lastModified() }.take(10).map { Source(it, "logs") }
        val apks = context.apkInfos.flatMap { it.files }.map { Source(it.apkFile, "apks", listOf(it.apkFile.absolutePath)) }
        val deployed = deployFileManager.getDeployedFiles().map { Source(it.file, "deployed", it.targetApkPaths, it.relativeFile.path) }
        val staging = deployFileManager.getStagingFiles().map { Source(it.file, "staging", it.targetApkPaths, it.relativeFile.path) }
        val stateDirs = listOf(pathManager.projectInfosDir, pathManager.compileContextDbDir, pathManager.deployHistoryDbDir)
        val state = stateDirs.flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "json" }
            .map { Source(it, "state") }.toList() }
        val wrapper = File(pathManager.projectDir, "gradle/wrapper/gradle-wrapper.properties")
            .takeIf { it.isFile }?.let { Source(it, "state") }
        val resourceApks = File(pathManager.databaseDir, "resource_apks").walkTopDown()
            .filter { it.isFile }.map { Source(it, "resource-apks") }.toList()
        val mappings = listOfNotNull(context.mappingFile, context.usageFile).filter { it.isFile }.map { Source(it, "mapping") }
        if (logs.isEmpty()) failures["logs"] = "No real Jugg compile log files were available."
        if (apks.isEmpty()) failures["apks"] = "No baseline APK was recorded in the current compile context."
        if (state.isEmpty()) failures["state"] = "No project model, compile baseline, or deployment history JSON was available."
        return logs + apks + deployed + staging + state + listOfNotNull(wrapper) + resourceApks + mappings
    }

    private fun showInvestigationFailure(exception: Exception) {
        logger.warn("Cannot save ASK AI investigation evidence.", exception)
        SwingUtilities.invokeLater {
            if (!project.isDisposed) Messages.showWarningDialog(project,
                "Cannot save investigation evidence: ${exception.message}", "ASK AI")
        }
    }

}

/** Freezes an IDE compile failure before delegating completion and exposes its saved prompt in the console. */
internal class AskAiCompileUiHandler(
    private val delegate: CompileUiHandler,
    private val project: Project,
    private val console: ConsoleView,
    private val options: JuggGradleCompileOptions,
    private val investigation: AskAiInvestigation,
    private val userActions: JuggControlPanelController,
    private val startedAt: Long,
) : CompileUiHandler by delegate {
    @Volatile private var hasWindowsSetupFailure = false
    private val taskLogs = RunLogCollector()
    private val collectsLogs = !delegate.isRpcMode

    init {
        if (collectsLogs) JuggLogger.listenProjectLog(project, taskLogs)
    }

    override fun createOutputParser(): IGradleCompileClient.TerminalOutputListener {
        val output = delegate.createOutputParser()
        return object : IGradleCompileClient.TerminalOutputListener by output {
            override fun onWindowsRsyncSetupFailure(failure: WindowsRsyncSetupException) {
                output.onWindowsRsyncSetupFailure(failure)
                if (hasWindowsSetupFailure) return
                hasWindowsSetupFailure = true
                if (delegate.isRpcMode) return
                SwingUtilities.invokeLater { printWindowsSetupLink(failure) }
            }
        }
    }

    private fun printWindowsSetupLink(failure: WindowsRsyncSetupException) {
        if (project.isDisposed) return
        console.performWhenNoDeferredOutput {
            console.print("\n[", ConsoleViewContentType.NORMAL_OUTPUT)
            console.printHyperlink("Windows rsync 环境配置 / Windows rsync setup", HyperlinkInfo {
                if (!project.isDisposed) WindowsRsyncSetupDialog(project, failure).show()
            })
            console.print("] Restart Android Studio after configuring PATH, then retry.\n", ConsoleViewContentType.NORMAL_OUTPUT)
        }
    }

    override fun onEnd(runResult: RunResult) {
        try {
            if (runResult.isCompileSuccess || runResult.isCancel || delegate.isRpcMode || delegate.isCanceled || hasWindowsSetupFailure) return
            val output = listOf(taskLogs.getAllLogs(), runResult.errorLog.joinToString("\n"))
                .filter { it.isNotBlank() }.joinToString("\n")
            val snapshot = investigation.preserveCompileFailure(options, runResult, startedAt,
                output) ?: return
            SwingUtilities.invokeLater { printFailureLink(snapshot) }
        } finally {
            try {
                if (collectsLogs) JuggLogger.stopListenProjectLog(project, taskLogs)
            } catch (_: IllegalAccessException) {
                // Project unregistration already disposes its dispatcher and all listeners.
            } finally {
                delegate.onEnd(runResult)
            }
        }
    }

    private fun printFailureLink(snapshot: InvestigationSnapshotBuilder.Snapshot) {
        if (project.isDisposed) return
        console.performWhenNoDeferredOutput {
            console.print("\nGot an unexpected error? [", ConsoleViewContentType.NORMAL_OUTPUT)
            console.printHyperlink("Ask AI", HyperlinkInfo { showPrompt(snapshot) })
            console.print("] to investigate with your agent.\n", ConsoleViewContentType.NORMAL_OUTPUT)
        }
    }

    private fun showPrompt(snapshot: InvestigationSnapshotBuilder.Snapshot) {
        if (project.isDisposed) return
        userActions.recordUserAction("ASK AI", "Compilation failed")
        AskAiDialog(project, emptyList(), snapshot).show()
    }
}
