package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.idea.IdeInfo
import com.android.tools.idea.run.ConsolePrinter
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.apk.ApkReader
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient
import com.sickworm.intellij.jugg.gradle.compile.LocalGradleCompileClient
import com.sickworm.intellij.jugg.gradle.compile.RemoteGradleCompileClient
import com.sickworm.intellij.jugg.ide.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.JuggGradleCompileRunningTask
import com.sickworm.intellij.jugg.ide.LoggerWrapper
import com.sickworm.intellij.jugg.ide.SimpleProcessHandler
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.project.JuggException
import com.sickworm.intellij.jugg.project.JuggInternalException
import org.jetbrains.android.download.AndroidProfilerDownloader
import org.jetbrains.annotations.TestOnly
import java.io.File

/**
 * Create a deploy task.
 *
 * @see [com.android.tools.idea.run.AndroidRunConfigurationBase.getState]
 * @see [com.android.tools.idea.run.LaunchTaskRunner.run]
 */
class JuggDeployerHelper(
    private val project: Project,
    private val deployTargetManager: IDeployTargetManager,
    private val logger: Logger = JuggLogger.getInstance(project, "JuggDeployerHelper"),
) : Disposable {

    @TestOnly
    var installPathProvider: Computable<String> = Computable<String> {
        CopyEmbeddedDistributionPaths().get()
    }

    fun runTask(data: JuggDeployData, isInstall: Boolean = false) {
        if (data.apks.isEmpty()) {
            throw JuggInternalException.apkNotFound(data)
        }

        val type = when {
            isInstall -> JuggDeployType.INSTALL
            data.isNeedRestartApp -> JuggDeployType.HOT_FIX
            else -> JuggDeployType.HOT_RELOAD
        }
        val task = JuggDeployTask(project, installPathProvider, type, data)

        // TODO ConsolePrinter
        val consolePrinter = MockConsolePrinter(logger)
        // TODO try ExecutionManager
        val device = deployTargetManager.getDevice()
        val launchContext = LaunchContext(consolePrinter, device)
        val launchResult = task.run(launchContext)
        if (!launchResult.success) {
            throw JuggException.applyChangesFailed(launchResult)
        }

        if (data.isNeedRestartApp || isInstall) {
            deployTargetManager.restartApp()
        }
    }

    private val compileClientManager = CompileClientManager(project).also {
        Disposer.register(this, it)
    }

    fun runFullBuildAndLaunch(settings: JuggGradleCompileOptions): ExecutionResult {
        val consoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        val client = compileClientManager.getClient(settings.isRemoteCompile)
        val processHandler = SimpleProcessHandler {
            client.cancelAction(isByUser = false)
        }
        consoleView.attachToProcess(processHandler)
        val task = JuggGradleCompileRunningTask(project, client, settings, processHandler) { apkFile ->
            val loggerWrapper = LoggerWrapper(processHandler, logger)
            val apkReader = ApkReader(apkFile, loggerWrapper)
            val apkInfo = apkReader.getApkInfo()
            deployTargetManager.setApks(listOf(apkInfo))
            runTask(JuggDeployData.forInstall(listOf(apkInfo)), true)
        }
        ProgressManager.getInstance().run(task)
        return DefaultExecutionResult(consoleView, processHandler)
    }

    override fun dispose() {
    }

}

class MockConsolePrinter(private val logger: Logger): ConsolePrinter {

    override fun stdout(message: String) {
        logger.info(message)
    }

    override fun stderr(message: String) {
        logger.error(message)
    }
}

/**
 * Copied from EmbeddedDistributionPaths.getInstance().findEmbeddedInstaller()
 * because this method only exists in Intellij Idea
 */
private class CopyEmbeddedDistributionPaths {

    fun get(): String {
        val path = "plugins/android/resources/installer"
        var file: File? = File(PathManager.getHomePath(), path)
        if (file!!.exists()) {
            return file.absolutePath
        }

        file = getOptionalIjPath(path)
        if (file != null && file.exists()) {
            return file.absolutePath
        }
        // Development mode
        assert(IdeInfo.getInstance().isAndroidStudio) { "Bazel paths exist only in AndroidStudio development mode" }
        return File(
            PathManager.getHomePath(),
            "../../bazel-bin/tools/base/deploy/installer/android-installer"
        ).absolutePath
    }

    private fun getOptionalIjPath(@Suppress("SameParameterValue") path: String): File? {
        // IJ does not bundle some large resources from android plugin, and downloads them on demand.
        AndroidProfilerDownloader.getInstance().makeSureComponentIsInPlace()
        return AndroidProfilerDownloader.getInstance().getHostDir(path)
    }
}

private class CompileClientManager(private val project: Project): Disposable {

    private var isCacheRemoteClient: Boolean? = null
    private var cacheClient: IGradleCompileClient? = null

    fun getClient(isRemote: Boolean): IGradleCompileClient {
        val cacheClient = cacheClient
        val isCacheRemoteClient = isCacheRemoteClient

        return if (cacheClient != null && isCacheRemoteClient == isRemote) {
            cacheClient
        } else {
            cacheClient?.dispose()
            val newClient = if (isRemote) RemoteGradleCompileClient(project) else LocalGradleCompileClient(project)
            Disposer.register(this, newClient)
            this.cacheClient = newClient
            this.isCacheRemoteClient = isRemote
            newClient
        }
    }

    override fun dispose() {
    }
}
