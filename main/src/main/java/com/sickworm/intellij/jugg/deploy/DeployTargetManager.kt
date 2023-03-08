package com.sickworm.intellij.jugg.deploy

import com.android.ddmlib.*
import com.android.tools.idea.run.AndroidRunConfiguration
import com.android.tools.idea.run.ApkInfo
import com.android.tools.idea.run.ApkProvider
import com.android.tools.idea.run.ValidationError
import com.intellij.execution.*
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.openapi.Disposable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.ide.JuggGradleCompileRunningTask
import com.sickworm.intellij.jugg.ide.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.SimpleProcessHandler
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.project.JuggException
import com.sickworm.intellij.jugg.project.JuggInternalException
import com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient
import com.sickworm.intellij.jugg.gradle.compile.LocalGradleCompileClient
import com.sickworm.intellij.jugg.gradle.compile.RemoteGradleCompileClient

/**
 * Manage device, run config, built apk.
 */
class DeployTargetManager(
    private val project: Project,
): IDeployTargetManager, Disposable {
    private val logger = JuggLogger.getInstance(project, "DeployTargetManager")

    private val compileClientManager = CompileClientManager(project).also {
        Disposer.register(this, it)
    }

    override fun runFullBuildAndLaunch(settings: JuggGradleCompileOptions?): ExecutionResult {
        if (settings == null) {
            // TODO remove after refactor test
            // not launched by JuggRunConfiguration
            val (runConfigAndSettings, _) = getRunConfig()
            ProgramRunnerUtil.executeConfiguration(runConfigAndSettings, DefaultRunExecutor.getRunExecutorInstance())
            return DefaultExecutionResult()
        }

        // launched by JuggRunConfiguration, run it
        val result = doRunFullBuildAndLaunch(settings)
        return result
    }

    private fun doRunFullBuildAndLaunch(settings: JuggGradleCompileOptions): ExecutionResult {
        val consoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        val client = compileClientManager.getClient(settings.isRemoteCompile)
        val processHandler = SimpleProcessHandler {
            client.cancelAction(isByUser = false)
        }
        consoleView.attachToProcess(processHandler)
        val task = JuggGradleCompileRunningTask(project, client, settings, processHandler, ::getDevice)
        ProgressManager.getInstance().run(task)
        return DefaultExecutionResult(consoleView, processHandler)
    }

    private var apkProviderFromRecover: ApkProvider? = null

    override fun setApksFromRecover(apks: List<ApkInfo>) {
        apkProviderFromRecover = object : ApkProvider {
            override fun getApks(device: IDevice): MutableCollection<ApkInfo> {
                return apks.toMutableList()
            }

            override fun validate(): MutableList<ValidationError> {
                return mutableListOf()
            }
        }
    }

    override fun getApks(): List<ApkInfo> {
        return try {
            val apkProvider = getApkProvider()
            val device = getDevice()
            apkProvider.getApks(device).toList()
        } catch (e: Exception) {
            logger.error("getApks failed", e)
            emptyList()
        }
    }

    override fun getDevice(): IDevice {
        try {
            val devices = AsDeployerCompat.getDevices(project)
            if (devices == null || devices.isEmpty()) {
                throw JuggException.deviceNotFound()
            }

            if (devices.size > 1) {
                throw JuggException.multipleDeviceFound()
            }

            return devices[0]
        } catch (e: Exception) {
            if (e is JuggException) {
                logger.debug("getDevice failed: ${e.message}")
            } else {
                logger.error("getDevice failed", e)
            }
            throw e
        }
    }

    override fun restartApp(): Boolean {
        return try {
            AdbCmdHelper(getDevice(), logger).startDefaultApp(getPackageName(), getApkProvider())
            true
        } catch (e: Exception) {
            logger.error("restartApp failed", e)
            false
        }
    }

    private fun getPackageName(): String {
        val apks = getApks()
        if (apks.isEmpty()) {
            throw JuggInternalException.getPackageNameFailedApkNotFound()
        }
        if (apks.size > 1) {
            throw JuggException.notSupportMultiApk()
        }
        return apks.first().applicationId
    }

    private fun getApkProvider(): ApkProvider {
        return apkProviderFromRecover ?: getGradleApkProvider()
    }

    private fun getGradleApkProvider(): ApkProvider {
        val (_, runConfig) = getRunConfig()
        return AsDeployerCompat.getApkProvider(project, runConfig)
    }

    private fun getRunConfig(): Pair<RunnerAndConfigurationSettings, AndroidRunConfiguration> {
        val runConfig = RunManager.getInstance(project).selectedConfiguration!!
        return runConfig to runConfig.configuration as AndroidRunConfiguration
    }

    override fun dispose() {
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