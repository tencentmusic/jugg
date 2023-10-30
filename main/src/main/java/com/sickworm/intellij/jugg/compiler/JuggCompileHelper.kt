package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.apk.ApkReader
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.DeployStateManager
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.deploy.run.IdeDeployState
import com.sickworm.intellij.jugg.gradle.compile.GradleCompileResult
import com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient
import com.sickworm.intellij.jugg.gradle.compile.LocalGradleCompileClient
import com.sickworm.intellij.jugg.gradle.compile.RemoteGradleCompileClient
import com.sickworm.intellij.jugg.ide.*
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.logger.JuggReporter
import com.sickworm.intellij.jugg.project.ChangedFile
import com.sickworm.intellij.jugg.project.CompileContextManager
import com.sickworm.intellij.jugg.project.IFileChangesHandler
import org.jetbrains.annotations.TestOnly
import java.io.File

class JuggCompilerHelper(
    private val project: Project,
    private val juggReporter: JuggReporter,
    private val deployTargetManager: IDeployTargetManager,
    private val deployStateManager: DeployStateManager,
    private val deployFileManager: DeployFileManager,
    private val compileContextManager: CompileContextManager,
    private val fileChangesHandler: IFileChangesHandler,
    private val deployStateListenerGetter: () -> JuggStateListener,
    private val logger: Logger = JuggLogger.getInstance(project, "JuggCompilerHelper"),
): Disposable {

    var juggCompiler: JuggCompiler? = null
        set(value) {
            field?.dispose()
            field = value
        }

    private val deployStateListener get() = deployStateListenerGetter.invoke()

    private val gradleCompileClientManager = GradleCompileClientManager(project).also {
        Disposer.register(this, it)
    }

    @Synchronized
    fun compile(
        options: JuggGradleCompileOptions,
        processHandler: SimpleProcessHandler,
        indicator: ProgressIndicator,
        isForceInstall: Boolean,
    ): CompileTaskResult {
        val result = doCompile(options, processHandler, indicator, isForceInstall)
        if (processHandler.isProcessTerminating || processHandler.isProcessTerminated) {
            logger.warn("Compile canceled.")
            return result.copy(
                isSuccess = false,
                isCanFallback = false,
                failedReason = "Compile canceled",
            )
        }
        return result
    }

    @Synchronized
    fun doCompile(
        options: JuggGradleCompileOptions,
        processHandler: SimpleProcessHandler,
        indicator: ProgressIndicator,
        isForceInstall: Boolean,
    ): CompileTaskResult {
        val statTime = System.currentTimeMillis()
        if (!isForceInstall) {
            val loggerListener = IndicatorLoggerListener(indicator)
            JuggLogger.listenProjectLog(project, loggerListener)
            var incrementalResult = incrementalCompile(processHandler)
            JuggLogger.stopListenProjectLog(project, loggerListener)
            incrementalResult = incrementalResult.copy(costTime = System.currentTimeMillis() - statTime)
            juggReporter.report {
                action = "incremental_compile"
                isSuccess = incrementalResult.isSuccess
                costTime = incrementalResult.costTime
                detail = incrementalResult.failedReason
            }
            if (incrementalResult.isSuccess) {
                return incrementalResult
            } else if (!incrementalResult.isCanFallback) {
                logger.warn("\nFound incremental compile error. Please see logs for details.")
                logger.warn("Run again directly will fall back to gradle compile.\n")
                return incrementalResult
            } else {
                logger.debug("incremental compile not proceed. Will fall back to gradle compile.")
                JuggRunningTask.notifyFallback(project, incrementalResult.failedReason ?: "See log for details.")
            }
        }

        if (processHandler.isProcessTerminating || processHandler.isProcessTerminated) {
            logger.warn("Compile canceled.")
            return CompileTaskResult(
                isSuccess = false,
                isGradleCompile = true,
                isCanFallback = false,
                costTime = System.currentTimeMillis() - statTime,
                failedReason = "Compile canceled",
            )
        }

        val result = gradleCompile(options, processHandler, indicator)
        if (result.isSuccess) {
            JuggSettings.updateDefaultSettings(options)
        }
        return CompileTaskResult(isSuccess = result.isSuccess,
            isGradleCompile = true,
            isCanFallback = false,
            costTime = System.currentTimeMillis() - statTime,
            failedReason = result.failedReason,
        )
    }

    private fun gradleCompile(
        options: JuggGradleCompileOptions,
        processHandler: SimpleProcessHandler,
        indicator: ProgressIndicator,
    ): GradleCompileResult {
        val client = gradleCompileClientManager.getClient(options.isRemoteCompile)
        val task = JuggGradleCompileTask(project, client, options, processHandler, indicator)
        val result = task.run()
        if (result.isSuccess) {
            val apkFile = result.compileOutputFile
            val apkReader = ApkReader(apkFile, logger)
            val apkInfo = apkReader.getApkInfo()
            deployTargetManager.setApks(listOf(apkInfo))
        }
        return result
    }

    @TestOnly
    fun incrementalCompile(processHandler: SimpleProcessHandler): CompileTaskResult {
        val deployState = deployStateManager.updateDeployState()
        logger.debug("Try incremental compile. Current state: $deployState")

        if (!deployState.isReadyIncCompile) {
            logger.info("Deploy state ${deployStateManager.deployState} not ready for incremental compile. Return.")
            return CompileTaskResult.incrementalFailed(true, deployState.msg)
        }

        if (!deployState.isReadyDeploy) {
            if (deployState.ideDeployState.state == IdeDeployState.State.INVALID_DEVICE) {
                logger.info("Device not ready for incremental compile(${deployState.ideDeployState.message}). Return.")
                return CompileTaskResult.incrementalFailed(true, deployState.ideDeployState.message)
            }
        }

        val compiler = juggCompiler ?: run {
            logger.warn("Jugg compiler not init, may some error occurs. please see log for details")
            return CompileTaskResult.incrementalFailed(true, "Jugg compiler not init")
        }

        // read all uncompiled files
        val uncompiledFiles = deployFileManager.getUncompiledFiles()
        if (uncompiledFiles.all { it.hasCompiledOnce }) {
            val deviceName = deployTargetManager.getDeviceOrNull()?.name
            if (JuggRunningTask.isFirstTimeRun(project, deviceName)) {
                logger.info("No file changes, but it's first time run, will run with incremental compile.")
            } else {
                logger.info("No file changes. will fallback to gradle compile.")
                return CompileTaskResult.incrementalFailed(true, "No file changes")
            }
        }

        return doIncrementalCompile(compiler, uncompiledFiles, processHandler)
    }

    private fun doIncrementalCompile(compiler: JuggCompiler, uncompiledFiles: List<ChangedFile>, processHandler: SimpleProcessHandler): CompileTaskResult {
        if (processHandler.isProcessTerminating || processHandler.isProcessTerminated) {
            logger.warn("Compile canceled.")
            return CompileTaskResult.incrementalFailed(false, "Compile canceled")
        }

        val compileFiles = uncompiledFiles.map {
            CompileFile(it.type, it.file, it.baseDir, it.module)
        }

        deployStateListener.onFileStatesUpdate(compileFiles.map {
            ChangedFileInfo(it.file, ChangedFileInfo.State.COMPILING)
        })

        // do compile
        logger.info("Compile files:\n${compileFiles.desc()}")
        val startTime = System.currentTimeMillis()
        val compileResult = try {
            compiler.compile(CompileTask(compileFiles, compileContextManager.stagingDir))
        } catch (e: Exception) {
            logger.error("Compile unexpected error: ${e.message}", e)
            return CompileTaskResult.incrementalFailed(true, "Exception: $e")
        }

        // update file status
        val successFiles = compileResult.details.filter { it.isSuccess }.map { it.get() }
        val failedFiles = compileResult.details.filter { !it.isSuccess }.map { it.getFailure().file }
        deployFileManager.updateUncompiledFiles(successFiles, failedFiles)
        deployFileManager.addDeployFiles(compileResult.outputs)

        // notify ui state
        val successStates = compileResult.successFiles.map {
            ChangedFileInfo(it.file.file, ChangedFileInfo.State.COMPILED)
        }
        val failedStates = compileResult.failedFiles.map {
            ChangedFileInfo(it.file.file, ChangedFileInfo.State.COMPILE_FAILED)
        }
        val costTime = System.currentTimeMillis() - startTime
        logger.info("Compile finished in ${costTime / 1000}s, " +
                "success: ${compileResult.successFiles.size}, " +
                "failure: ${compileResult.failedFiles.size}.")
        deployStateListener.onFileStatesUpdate(successStates + failedStates)

        val isSuccess = failedStates.isEmpty()
        if (isSuccess) {
            val recompileFiles = deployFileManager.getRecompileFiles(uncompiledFiles)
            val effectedSourceFiles = recompileFiles.effectedSourceFiles
            if (effectedSourceFiles.isNotEmpty()) {
                logger.info("Compile success, but found effected source files, continue compile. Files: ${effectedSourceFiles.map { it.name }}")
                val changedFiles = fileChangesHandler.filter(effectedSourceFiles)
                deployFileManager.addChangedFile(changedFiles)
            }

            val redexClasses = recompileFiles.redexClasses
            if (redexClasses.isNotEmpty()) {
                logger.info("Compile success, but found classes that need to be redexed, continue compile. Classes: ${redexClasses.map { it.file.name }}")
                deployFileManager.addChangedFile(redexClasses)
            }

            if (deployFileManager.getUncompiledFiles().isNotEmpty()) {
                return doIncrementalCompile(compiler, deployFileManager.getUncompiledFiles(), processHandler)
            }
        }

        return if (isSuccess) {
            CompileTaskResult.incrementalSuccess()
        } else {
            CompileTaskResult.incrementalFailed(false, "Compile failed: $compileResult")
        }
    }

    @Synchronized
    fun warmUp() {
        juggCompiler?.warmUp()
    }

    /**
     * Fetch classpath from gradle compile client.
     * @return classpath root dir
     */
    @Synchronized
    fun fetchClasspathResult(isRemote: Boolean, buildDirs: List<ModuleBuildPathInfo>): File? {
        return gradleCompileClientManager.getClient(isRemote).fetchClasspathResult(buildDirs)
    }

    override fun dispose() {
        juggCompiler?.dispose()
        juggCompiler = null
    }
}

private class GradleCompileClientManager(private val project: Project): Disposable {

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

data class CompileTaskResult(
    val isSuccess: Boolean,
    val isGradleCompile: Boolean,
    val isCanFallback: Boolean,
    val costTime: Long,
    val failedReason: String? = null,
) {
    companion object {

        fun incrementalSuccess() = CompileTaskResult(
            isSuccess = true,
            isGradleCompile = false,
            isCanFallback = false,
            costTime = 0,
        )

        fun incrementalFailed(isCanFallback: Boolean, failedReason: String) = CompileTaskResult(
            isSuccess = false,
            isGradleCompile = false,
            isCanFallback,
            costTime = 0,
            failedReason = failedReason,
        )
    }
}
