package com.sickworm.intellij.jugg.project.dependency

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.compiler.JuggCompilerHelper
import com.sickworm.intellij.jugg.gradle.compile.CmdExecutor
import com.sickworm.intellij.jugg.gradle.compile.CompileProjectCommand
import com.sickworm.intellij.jugg.gradle.compile.LocalGradleCompileClient
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.CompileContextManager
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.TaskRunnerManager
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
    loggerArg: Logger,
): Disposable {

    private val logger = loggerArg.getInstance("GradleProjectInfoLocalFetchManager")

    private var isNeedUpdate: Boolean
        get() = pathManager.markProjectInfoNeedUpdateFlagFile.exists() || !isProjectInfoExits
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

    val isProjectInfoExits: Boolean get() = pathManager.gradleProjectInfoFile.exists()

    @Volatile
    private var isUpdating: Boolean = false

    private val cmdExecutor = CmdExecutor(logger)

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
            if (ideProjectInfoFileLastModifiedTime > lastBuildModifiedTime) {
                logger.debug("ide project info already update after last build file modified, ignore")
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
    fun runUpdateIfNeeded(isForce: Boolean = false) {
        logger.debug("runUpdateIfNeeded isNeedUpdate $isNeedUpdate, isUpdating $isUpdating, isForce: $isForce")
        if (!isForce && (!isNeedUpdate || isUpdating)) {
            logger.debug("no need execute update, exit")
            return
        }

        taskRunnerManager.runBackgroundSafe("Update project info from gradle", ::update)
    }

    private fun update(isKeepDaemon: Boolean = false): Boolean {
        try {
            isUpdating = true
            writeInitGradleFile()
            compileContextManager.ensureInitProjectInfo()

            val daemonArg = if (isKeepDaemon) "" else "--no-daemon"
            val localFetchCommand = CompileProjectCommand(
                "./gradlew --dry-run --console=plain $daemonArg -I ${pathManager.initGradleFilePath.absolutePath}",
                pathManager.projectDir.path,
                pathManager.initGradleFileRelativePath
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
                compileContextManager.updateCompileContextAfterLocalFetch()
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

    private var hasWrote = false

    fun writeInitGradleFile() {
        val initGradleFile = pathManager.initGradleFilePath
        if (hasWrote && initGradleFile.exists()) {
            return
        }
        TimeLogger.start("writeInitGradleFile")
        initGradleFile.parentFile.mkdirs()
        JuggCompilerHelper::class.java.getResource("/gradle/readProjectInfo.gradle.kts")!!.openStream().use { ins ->
            val text = ins.reader().readText()
            initGradleFile.writeText(text)
        }
        hasWrote = true
        TimeLogger.end("writeInitGradleFile", logger)
    }


    private fun Long.timeStampToTime(): String {
        return SimpleDateFormat("MM-dd HH:mm:ss.SSS").format(Date(this))
    }

    override fun dispose() {
        cmdExecutor.release()
    }
}