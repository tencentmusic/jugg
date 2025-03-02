package com.sickworm.intellij.jugg.project

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.deploy.DeployStateManager
import com.sickworm.intellij.jugg.server.JuggServer
import com.sickworm.intellij.jugg.server.ReportEventData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.system.measureTimeMillis

/**
 * Run action in ProgressManager.getInstance().run()
 */
class TaskRunnerManager(
    private val project: Project,
    private val logger: Logger,
    private val deployStateManager: DeployStateManager,
    private val juggServer: JuggServer,
    coroutineScope: CoroutineScope,
): CoroutineScope by coroutineScope {

    var currentIndicator: ProgressIndicator? = null
        private set
    private var retryInitDelayMill = 3_000L

    fun runBackgroundSafe(jobName: String, action: Runnable) {
        runBackgroundSafe(jobName, 0L, action)
    }

    fun runBackgroundSafe(jobName: String, delayMs: Long, action: Runnable) {
        launch {
            try {
                if (delayMs > 0) {
                    delay(delayMs)
                }
                logger.debug("background job <$jobName> start")
                val costTime = measureTimeMillis {
                    action.run()
                }
                logger.debug("background job <$jobName> finished, cost ${costTime}ms")
            } catch (e: Exception) {
                logger.error("background job <$jobName> failed", e)
            }
        }
    }

    fun runTaskSafe(jobName: String, action: Runnable, isNeedShowIndicator: Boolean = true, isBlockIncrementalCompile: Boolean = true) {
        val juggJobName = "Jugg: $jobName"
        object : Task.Backgroundable(project, juggJobName, false) {
            override fun run(indicator: ProgressIndicator) {
                val runnable = {
                    val reportEventData = ReportEventData()
                    val startTime = System.currentTimeMillis()

                    try {
                        logger.debug("job <$jobName> start")
                        if (isBlockIncrementalCompile) {
                            deployStateManager.isInitializingIncrementalCompile = true
                        }
                        if (isNeedShowIndicator) {
                            indicator.text = "$juggJobName..."
                            indicator.isIndeterminate = true
                            currentIndicator = indicator
                        }
                        action.run()
                        val costTime = System.currentTimeMillis() - startTime
                        logger.debug("job <$jobName> finished, cost ${costTime}ms")
                    } catch (e: Throwable) {
                        logger.error("job <$jobName> failed", e)
                        reportEventData.detail = e.message ?: e.cause?.message ?: ""
                        reportEventData.isSuccess = false
                    } finally {
                        if (isBlockIncrementalCompile) {
                            deployStateManager.isInitializingIncrementalCompile = false
                        }
                        if (isNeedShowIndicator) {
                            indicator.stop()
                            currentIndicator = null
                        }
                    }

                    reportEventData.action = jobName
                    reportEventData.costTime = System.currentTimeMillis() - startTime
                    juggServer.report(reportEventData)

                    if (jobName == "Init project info") {
                        if (!reportEventData.isSuccess) {
                            // compatible with com.intellij.serviceContainer.AlreadyDisposedException: Already disposed: Module: 'xxx' (disposed)
                            logger.debug("retry $jobName after ${retryInitDelayMill}ms") // maybe
                            launch {
                                delay(retryInitDelayMill)
                                retryInitDelayMill *= 2
                                runTaskSafe(jobName, action, isNeedShowIndicator)
                            }
                        } else {
                            retryInitDelayMill = 3_000L
                        }
                    }
                }
                if (isBlockIncrementalCompile) {
                    synchronized(this@TaskRunnerManager) {
                        runnable.invoke()
                    }
                } else {
                    runnable.invoke()
                }
            }
        }.setCancelText("Jugg: Stopping $jobName...").queue()
    }
}