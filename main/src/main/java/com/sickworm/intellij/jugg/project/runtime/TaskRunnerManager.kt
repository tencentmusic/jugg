package com.sickworm.intellij.jugg.project.runtime

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.IDeployStateManager
import com.sickworm.intellij.jugg.server.JuggServer
import com.sickworm.intellij.jugg.server.ReportEventData
import com.sickworm.intellij.jugg.runtime.PluginInfoReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.lang.Runnable
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.ContinuationInterceptor
import kotlin.system.measureTimeMillis

/** Submits host-specific tasks without exposing IDE APIs to the shared task domain. */
interface IHostTaskExecutor {
    val isOnEdt: Boolean
    fun submit(title: String, cancelText: String, showIndicator: Boolean, action: Runnable)
}

/**
 * Coordinates project write tasks, cross-process locks, background jobs, reporting, retry, and disposal.
 * Host executors control how a task is scheduled and how its progress indicator is presented.
 */
class TaskRunnerManager internal constructor(
    private val logger: Logger,
    private val deployStateManager: IDeployStateManager,
    private val juggServer: JuggServer,
    private val hostTaskExecutor: IHostTaskExecutor,
    private val executionLockManager: IExecutionLockManager,
    private val coroutineScope: CoroutineScope,
) {

    constructor(
        logger: Logger,
        deployStateManager: IDeployStateManager,
        juggServer: JuggServer,
        hostTaskExecutor: IHostTaskExecutor,
        pathManager: JuggPathManager,
        runtimeType: String,
        runtimeVersion: String,
        coroutineScope: CoroutineScope,
    ) : this(
        logger,
        deployStateManager,
        juggServer,
        hostTaskExecutor,
        FileExecutionLockManager(pathManager, RuntimeIdentity(runtimeType, runtimeVersion)),
        coroutineScope,
    )

    private val disposed = AtomicBoolean()
    private val backgroundJobs = Collections.synchronizedSet(mutableSetOf<Job>())
    private var retryInitDelayMillis = 3_000L

    val dispatcher: CoroutineDispatcher
        get() = coroutineScope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher ?: Dispatchers.Default

    val isOnEdt: Boolean
        get() = hostTaskExecutor.isOnEdt

    fun runBackgroundSafe(
        jobName: String,
        delayMs: Long = 0L,
        isNeedLog: Boolean = true,
        isProjectWrite: Boolean = false,
        isGlobalWrite: Boolean = false,
        action: Runnable,
    ): Job {
        require(!isProjectWrite || !isGlobalWrite) {
            "A task cannot hold project and global write locks together"
        }
        return track(coroutineScope.launch {
            try {
                if (delayMs > 0) delay(delayMs)
                if (disposed.get()) return@launch
                if (isNeedLog) logger.debug("background job <$jobName> start")
                val costTime = measureTimeMillis {
                    runWriteLocked(jobName, isProjectWrite, isGlobalWrite, action::run)
                }
                if (isNeedLog) logger.debug("background job <$jobName> finished, cost ${costTime}ms")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!disposed.get()) logger.warn("background job <$jobName> failed", e)
            }
        })
    }

    fun <T> runAsyncSafe(jobName: String, action: CoroutineScope.() -> T): Deferred<T?> {
        return track(coroutineScope.async {
            try {
                if (disposed.get()) return@async null
                logger.debug("async job <$jobName> start")
                action()
            } catch (e: Exception) {
                logger.warn("async job <$jobName> failed", e)
                null
            }
        })
    }

    fun runTaskSafe(
        jobName: String,
        action: Runnable,
        isNeedShowIndicator: Boolean = true,
        isGlobalWrite: Boolean = false,
        isProjectWrite: Boolean = !isGlobalWrite,
        isBlockIncrementalCompile: Boolean = isProjectWrite,
    ) {
        require(!isProjectWrite || !isGlobalWrite) {
            "A task cannot hold project and global write locks together"
        }
        require(isProjectWrite || !isBlockIncrementalCompile) {
            "A task cannot block incremental compile without holding the project write lock"
        }
        if (disposed.get()) return
        val title = "Jugg: $jobName"
        hostTaskExecutor.submit(title, "Jugg: Stopping $jobName...", isNeedShowIndicator) {
            executeTask(jobName, action, isProjectWrite, isGlobalWrite, isBlockIncrementalCompile)
        }
    }

    fun <T> runProjectWriteLocked(jobName: String, action: () -> T): T {
        return executionLockManager.withProjectLock(jobName, action)
    }

    fun <T> runGlobalWriteLocked(jobName: String, action: () -> T): T {
        return executionLockManager.withGlobalLock(jobName, action)
    }

    /** Prevents queued tasks from starting; an active locked write transaction is allowed to finish. */
    fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        val snapshot = synchronized(backgroundJobs) { backgroundJobs.toList() }
        snapshot.forEach(Job::cancel)
        backgroundJobs.clear()
    }

    private fun executeTask(
        jobName: String,
        action: Runnable,
        isProjectWrite: Boolean,
        isGlobalWrite: Boolean,
        isBlockIncrementalCompile: Boolean,
    ) {
        if (disposed.get()) return
        val report = ReportEventData()
        val startTime = System.currentTimeMillis()
        try {
            logger.debug("job <$jobName> start")
            runWriteLocked(jobName, isProjectWrite, isGlobalWrite) {
                runWithIncrementalCompileState(isBlockIncrementalCompile, action)
            }
            logger.debug("job <$jobName> finished, cost ${System.currentTimeMillis() - startTime}ms")
        } catch (e: Throwable) {
            logger.warn("job <$jobName> failed", e)
            report.detail = e.message ?: e.cause?.message.orEmpty()
            report.isSuccess = false
        }

        if (!report.isSuccess) {
            report.action = jobName
            report.costTime = System.currentTimeMillis() - startTime
            juggServer.report(report)
        }
        scheduleInitRetryIfNeeded(
            jobName,
            action,
            report.isSuccess,
            isProjectWrite,
            isGlobalWrite,
            isBlockIncrementalCompile,
        )
    }

    private fun runWithIncrementalCompileState(isBlockIncrementalCompile: Boolean, action: Runnable) {
        if (!isBlockIncrementalCompile) {
            action.run()
            return
        }
        deployStateManager.isInitializingIncrementalCompile = true
        try {
            action.run()
        } finally {
            deployStateManager.isInitializingIncrementalCompile = false
        }
    }

    private fun scheduleInitRetryIfNeeded(
        jobName: String,
        action: Runnable,
        isSuccess: Boolean,
        isProjectWrite: Boolean,
        isGlobalWrite: Boolean,
        isBlockIncrementalCompile: Boolean,
    ) {
        if (jobName != "Init project info") return
        if (isSuccess) {
            retryInitDelayMillis = 3_000L
            return
        }
        val delayMillis = retryInitDelayMillis
        retryInitDelayMillis *= 2
        logger.debug("retry $jobName after ${delayMillis}ms")
        runBackgroundSafe("Retry $jobName", delayMillis) {
            runTaskSafe(
                jobName = jobName,
                action = action,
                isProjectWrite = isProjectWrite,
                isGlobalWrite = isGlobalWrite,
                isBlockIncrementalCompile = isBlockIncrementalCompile,
            )
        }
    }

    private fun <T> runWriteLocked(
        jobName: String,
        isProjectWrite: Boolean,
        isGlobalWrite: Boolean,
        action: () -> T,
    ): T {
        return when {
            isProjectWrite -> runProjectWriteLocked(jobName, action)
            isGlobalWrite -> runGlobalWriteLocked(jobName, action)
            else -> action()
        }
    }

    private fun <T : Job> track(job: T): T {
        backgroundJobs.add(job)
        job.invokeOnCompletion { backgroundJobs.remove(job) }
        return job
    }

    companion object {
        /** Runs global infrastructure writes that must happen before a TaskRunner instance exists. */
        fun <T> runGlobalWriteLocked(
            jobName: String,
            globalRootDir: File = JuggGlobalPathManager.rootDir,
            action: () -> T,
        ): T {
            val runtimeIdentity = RuntimeIdentity("infrastructure", PluginInfoReader.getPluginVersion())
            return GlobalExecutionLock(runtimeIdentity, globalRootDir).withLock(jobName, action)
        }
    }
}
