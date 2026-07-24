package com.sickworm.intellij.jugg.project.dependency

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.gradle.compile.CmdExecutor
import com.sickworm.intellij.jugg.gradle.compile.CompileProjectCommand
import com.sickworm.intellij.jugg.gradle.compile.GradleScriptWriter
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.gradle.compile.LocalGradleCompileClient
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.CompileContextManager
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.TaskRunnerManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.project.data.JuggProjectInfo
import java.text.SimpleDateFormat
import java.util.*

/**
 * Trigger [update] to get [JuggProjectInfo] if build file is changed.
 * Only run when [markIsNeedUpdate] to true.
 */
class GradleProjectInfoLocalFetchManager(
    private val project: Project,
    private val pathManager: JuggPathManager,
    private val compileContextManager: CompileContextManager,
    private val taskRunnerManager: TaskRunnerManager,
    private val dependencyChangeManager: IDependencyChangeManager,
    private val deployHistoryManager: IDeployHistoryManager,
    loggerArg: Logger,
): Disposable {

    private val logger = loggerArg.getInstance("GradleProjectInfoLocalFetchManager")

    private var isNeedUpdate: Boolean
        get() = pathManager.markProjectInfoNeedUpdateFlagFile.exists() || !pathManager.gradleProjectInfoFile.exists()
        set(value) {
            val markFile = pathManager.markProjectInfoNeedUpdateFlagFile
            if (value) {
                if (!markFile.exists()) {
                    markFile.parentFile.mkdirs()
                    markFile.createNewFile()
                }
            } else {
                markFile.delete()
            }
        }

    val isProjectInfoAvailable: Boolean get() = pathManager.gradleProjectInfoFile.exists()
            && deployHistoryManager.getFullBuildInfo()?.compileCommand != null

    @Volatile
    private var isUpdating: Boolean = false

    private val cmdExecutor = CmdExecutor(logger, isLogAllDebug = true)

    /**
     * Mark as true when:
     * 1. build file changed
     * Mark as failed when:
     * 1. build file revert to no changes
     * 2. IDE sync finished (runtime and annotation processor won't update, we don't use these to do incremental compile for now)
     * 3. Local build finished
     * 4. Local fetch[update] finished
     */
    fun markIsNeedUpdate(isNeedUpdate: Boolean, lastBuildModifiedTime: Long = Long.MAX_VALUE) {
        val gradleProjectInfoFileLastModifiedTime = pathManager.gradleProjectInfoFile.lastModified()
        val ideProjectInfoFileLastModifiedTime = pathManager.ideProjectInfoFile.lastModified()
        logger.debug("markIsNeedUpdate $isNeedUpdate, " +
                "lastBuildModifiedTime: ${lastBuildModifiedTime.timeStampToTime()}, " +
                "gradleLastModifiedTime: ${gradleProjectInfoFileLastModifiedTime.timeStampToTime()}, " +
                "ideLastModifiedTime: ${ideProjectInfoFileLastModifiedTime.timeStampToTime()}")
        if (isNeedUpdate) {
            if (gradleProjectInfoFileLastModifiedTime > lastBuildModifiedTime) {
                logger.debug("gradle project info already update after last build file modified, ignore")
                return
            }
            // still need updates, because gradle project has unique infos we need
//            if (ideProjectInfoFileLastModifiedTime > lastBuildModifiedTime) {
//                logger.debug("ide project info already update after last build file modified, ignore")
//                return
//            }
        }
        this.isNeedUpdate = isNeedUpdate
    }

    /**
     * Trigger when:
     * 1. init compile finished after project opened/build finished
     * 2. start remote compile
     */
    fun runUpdateIfNeeded(
        isForce: Boolean = false,
        specificCompileCommand: String? = null,
        buildTarget: BuildTarget = deployHistoryManager.getFullBuildInfo()?.buildTarget ?: BuildTarget.APP,
    ) {
        // make sure we have checked the gradle project info data
        // gradleProjectInfoFile will be deleted if data is invalid
        compileContextManager.ensureInitProjectInfo()

        logger.debug("runUpdateIfNeeded isNeedUpdate $isNeedUpdate, isUpdating $isUpdating, isForce: $isForce")
        if (isUpdating || (!isForce && !isNeedUpdate)) {
            logger.debug("no need execute update, exit")
            return
        }

        taskRunnerManager.runTaskSafe("Update project info from gradle", {
            update(specificCompileCommand, buildTarget)
        }, isBlockIncrementalCompile = false)
    }

    @Synchronized
    private fun update(specificCompileCommand: String?, buildTarget: BuildTarget): Boolean {
        try {
            isUpdating = true
            GradleScriptWriter(pathManager, logger).writeInitGradleFile()
            compileContextManager.ensureInitProjectInfo()

            // cannot use --dry-run only on Gradle 8.x, it cannot get kotlin task
            // use real command to detect build variant correctly
            var finalCompileCommand = if (specificCompileCommand.isNullOrEmpty()) {
                val baseBuildCommand = deployHistoryManager.getFullBuildInfo()?.compileCommand
                if (baseBuildCommand == null) {
                    logger.debug("cannot get standard base build command, can not update")
                    return false
                }
                baseBuildCommand
            } else {
                specificCompileCommand
            }
            if (!CompileProjectCommand.isNormalGradleCommand(finalCompileCommand)) {
                logger.debug("finalCompileCommand: $finalCompileCommand is not normal gradle command, can not update")
                return false
            }

            // e.g. ./gradlew assembleDebug --dry-run --console=plain --no-daemon -Dorg.gradle.configuration-cache.problems=warn
            // -I /build/jugg/readProjectInfo.gradle.kts -Pjugg.inject.application.enable=true
            if (!finalCompileCommand.contains("--dry-run")) {
                finalCompileCommand += " --dry-run"
            }
            if (!finalCompileCommand.contains("--no-daemon")) {
                finalCompileCommand += " --no-daemon"
            }

            val localFetchCommand = CompileProjectCommand(
                finalCompileCommand,
                pathManager.projectDir.path,
                pathManager.initGradleFilePath.path,
                logger = logger,
                buildTarget = buildTarget,
            )
            logger.debug("runUpdateIfNeeded start")
            TimeLogger.start("localFetch")
            dependencyChangeManager.onStartSyncing(isFromIde = false)
            val result = cmdExecutor.invoke(localFetchCommand, LocalGradleCompileClient.buildCompileEnv(project, logger))
            TimeLogger.end("localFetch", logger)
            logger.debug("runUpdateIfNeeded end, result: $result")

            val isSuccess = result == 0
            if (isSuccess) {
                // update success
                markIsNeedUpdate(false)
                compileContextManager.updateCompileContextAfterLocalFetch(buildTarget)
            }
            dependencyChangeManager.onEndSyncing(isFromIde = false, isSuccess, compileContextManager.compileContext)
            return isSuccess
        } catch (e: Exception) {
            logger.warn("runUpdateIfNeeded exception", e)
            return false
        } finally {
            isUpdating = false
        }
    }

    private fun Long.timeStampToTime(): String {
        return SimpleDateFormat("MM-dd HH:mm:ss.SSS").format(Date(this))
    }

    override fun dispose() {
        cmdExecutor.release()
    }
}
