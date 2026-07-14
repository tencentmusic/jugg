package com.sickworm.intellij.jugg.project

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.deploy.IDeployStateManager
import com.sickworm.intellij.jugg.server.JuggServer
import com.sickworm.intellij.jugg.server.ReportEventData
import kotlinx.coroutines.*
import java.lang.Runnable
import kotlin.system.measureTimeMillis

/**
 * Run action in ProgressManager.getInstance().run()
 */
class TaskRunnerManager(
    private val project: Project,
    private val logger: Logger,
    private val deployStateManager: IDeployStateManager,
    private val juggServer: JuggServer,
    coroutineScope: CoroutineScope,
): CoroutineScope by coroutineScope, IBackgroundTaskRunner {

    var currentIndicator: ProgressIndicator? = null
        private set
    private var retryInitDelayMill = 3_000L

    override val isOnEdt: Boolean
        get() = ApplicationManager.getApplication().isDispatchThread

    override fun runBackgroundSafe(jobName: String, isNeedLog: Boolean, action: Runnable): Job {
        return runBackgroundSafe(jobName, 0L, isNeedLog, action)
    }

    override fun runBackgroundSafe(jobName: String, delayMs: Long, isNeedLog: Boolean, action: Runnable): Job {
        return launch {
            try {
                if (delayMs > 0) {
                    delay(delayMs)
                }
                if (isNeedLog) logger.debug("background job <$jobName> start")
                val costTime = measureTimeMillis {
                    action.run()
                }
                if (isNeedLog) logger.debug("background job <$jobName> finished, cost ${costTime}ms")
            } catch (e: Throwable) {
                logger.warn("background job <$jobName> failed", e)
            }
        }
    }

    fun <T> runAsyncSafe(jobName: String, action: CoroutineScope.() -> T): Deferred<T?> {
        return async {
            try {
                logger.debug("async job <$jobName> start")
                return@async action()
            } catch (e: Exception) {
                logger.warn("async job <$jobName> failed", e)
                return@async null
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
                        logger.warn("job <$jobName> failed", e)
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

                    if (!reportEventData.isSuccess) {
                        reportEventData.action = jobName
                        reportEventData.costTime = System.currentTimeMillis() - startTime
                        juggServer.report(reportEventData)

                        if (jobName == "Init project info") {
                            // compatible with com.intellij.serviceContainer.AlreadyDisposedException: Already disposed: Module: 'xxx' (disposed)
                            logger.debug("retry $jobName after ${retryInitDelayMill}ms") // maybe
                            launch {
                                delay(retryInitDelayMill)
                                retryInitDelayMill *= 2
                                runTaskSafe(jobName, action, isNeedShowIndicator)
                            }
                        }
                    } else if (jobName == "Init project info") {
                        retryInitDelayMill = 3_000L
                    }
                }
                if (isBlockIncrementalCompile) {
                    val t0 = System.currentTimeMillis()
                    logger.debug("job <$jobName> waiting for TaskRunnerManager lock, thread=${Thread.currentThread().name}")
                    synchronized(this@TaskRunnerManager) {
                        logger.debug("job <$jobName> acquired TaskRunnerManager lock, waitCost=${System.currentTimeMillis() - t0}ms")
                        runnable.invoke()
                    }
                } else {
                    runnable.invoke()
                }
            }
        }.setCancelText("Jugg: Stopping $jobName...").queue()
    }
}
