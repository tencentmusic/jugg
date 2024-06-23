package com.sickworm.intellij.jugg.project

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.JuggCompilerHelper
import com.sickworm.intellij.jugg.gradle.compile.CmdExecutor
import com.sickworm.intellij.jugg.gradle.compile.CompileProjectCommand
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.data.JuggProjectInfo
import java.text.SimpleDateFormat
import java.util.*

/**
 * Trigger [update] to get [JuggProjectInfo] if build file is changed.
 * Only run when [markIsNeedUpdate] to true.
 */
class GradleProjectInfoLocalFetchManager(
    private val pathManager: JuggPathManager,
    private val compileContextManager: CompileContextManager,
    private val taskRunnerManager: TaskRunnerManager,
    private val dependencyChangeManager: IDependencyChangeManager,
    loggerArg: Logger,
) {

    private val logger = loggerArg.getInstance("GradleProjectInfoLocalFetchManager")

    private var isNeedUpdate: Boolean
        get() = pathManager.markProjectInfoNeedUpdateFlagFile.exists()
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

    @Volatile
    private var isUpdating: Boolean = false

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
        logger.debug("markIsNeedUpdate $isNeedUpdate, " +
                "lastBuildModifiedTime: ${lastBuildModifiedTime.timeStampToTime()}, " +
                "gradleLastModifiedTime: ${gradleProjectInfoFileLastModifiedTime.timeStampToTime()}")
        if (isNeedUpdate) {
            if (gradleProjectInfoFileLastModifiedTime > lastBuildModifiedTime) {
                logger.debug("already update after last build file modified, ignore")
                return
            }
        }
        this.isNeedUpdate = isNeedUpdate
    }

    /**
     * Trigger when:
     * 1. init compile finished after project opened/build finished
     * 2. start remote compile
     */
    fun runUpdateIfNeeded() {
        logger.debug("runUpdateIfNeeded isNeedUpdate $isNeedUpdate, isUpdating $isUpdating")
        if (!isNeedUpdate || isUpdating) {
            logger.debug("no need execute update, exit")
            return
        }

        isUpdating = true
        taskRunnerManager.runTaskSafe("Update project info from gradle", ::update, isBlockIncrementalCompile = false)
    }

    private fun update() {
        try {
            writeInitGradleFile()
            val localFetchCommand = CompileProjectCommand(
                "./gradlew --dry-run --console=plain --no-daemon",
                pathManager.projectDir.path,
                pathManager.initGradleFileRelativePath
            )
            logger.debug("runUpdateIfNeeded start")
            TimeLogger.start("localFetch")
            dependencyChangeManager.onStartSyncing(isFromIde = false)
            val result = CmdExecutor(logger).invoke(localFetchCommand)
            TimeLogger.end("localFetch", logger)
            logger.debug("runUpdateIfNeeded end, result: $result")

            val isSuccess = result == 0
            if (isSuccess) {
                // update success
                markIsNeedUpdate(false)
                compileContextManager.updateCompileContextAfterLocalFetch()
            }
            dependencyChangeManager.onEndSyncing(isFromIde = false, isSuccess, compileContextManager.compileContext)
        } catch (e: Exception) {
            logger.debug("runUpdateIfNeeded exception", e)
        } finally {
            isUpdating = false
        }
    }

    fun writeInitGradleFile() {
        TimeLogger.start("writeInitGradleFile")
        val initGradleFile = pathManager.initGradleFilePath
        initGradleFile.parentFile.mkdirs()
        JuggCompilerHelper::class.java.getResource("/gradle/readProjectInfo.gradle.kts")!!.openStream().use { ins ->
            val text = ins.reader().readText()
            initGradleFile.writeText(text)
        }
        TimeLogger.end("writeInitGradleFile", logger)
    }


    private fun Long.timeStampToTime(): String {
        return SimpleDateFormat("MM-dd HH:mm:ss.SSS").format(Date(this))
    }
}