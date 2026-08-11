package com.sickworm.intellij.jugg.project.runtime

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.IDeployStateManager
import com.sickworm.intellij.jugg.server.JuggServer
import com.sickworm.intellij.jugg.server.ReportEventData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.Runnable
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.coroutines.ContinuationInterceptor
import kotlin.system.measureTimeMillis

/** Submits host-specific tasks without exposing IDE APIs to the shared task domain. */
interface IHostTaskExecutor {
    val isOnEdt: Boolean
    fun submit(title: String, cancelText: String, showIndicator: Boolean, action: Runnable)
}

/**
 * Coordinates runtime task serialization, cross-runtime locks, background jobs, reporting, retry, and disposal.
 * Host executors control how a task is scheduled and how its progress indicator is presented.
 */
class TaskRunnerManager internal constructor(
    private val logger: Logger,
    private val deployStateManager: IDeployStateManager,
    private val juggServer: JuggServer,
    private val hostTaskExecutor: IHostTaskExecutor,
    private val executionLockManager: IExecutionLockManager,
    private val coroutineScope: CoroutineScope,
    private val runtimeIdentity: RuntimeIdentity? = null,
    private val runtimeOwnerStore: RuntimeOwnerStore? = null,
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
        FileExecutionLockManager(pathManager, RuntimeIdentity(runtimeType, runtimeVersion), logger),
        coroutineScope,
        RuntimeIdentity(runtimeType, runtimeVersion).takeIf { runtimeType in runtimeOwnerTypes },
        RuntimeOwnerStore(pathManager.runtimeOwnerFile).takeIf { runtimeType in runtimeOwnerTypes },
    )

    private val disposed = AtomicBoolean()
    private val backgroundJobs = Collections.synchronizedSet(mutableSetOf<Job>())
    private val runtimeTaskCoordinator = RuntimeTaskCoordinator()
    private var retryInitDelayMillis = 3_000L
    private var runtimeOwnerChange: RuntimeOwnerChangeEvent? = null

    val dispatcher: CoroutineDispatcher
        get() = coroutineScope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher ?: Dispatchers.Default

    val isOnEdt: Boolean
        get() = hostTaskExecutor.isOnEdt

    fun runBackgroundSafe(
        jobName: String,
        delayMs: Long = 0L,
        isNeedLog: Boolean = true,
        isProjectWrite: Boolean = false,
        action: Runnable,
    ): Job {
        val inheritedOwner = runtimeTaskCoordinator.captureOwner()
        return track(coroutineScope.launch {
            try {
                if (delayMs > 0) delay(delayMs)
                if (disposed.get()) return@launch
                runtimeTaskCoordinator.withOwnerContext(inheritedOwner) {
                    if (isNeedLog) logger.debug("background job <$jobName> start")
                    val costTime = measureTimeMillis {
                        runBackgroundWriteLocked(jobName, isProjectWrite, action::run)
                    }
                    if (isNeedLog) logger.debug("background job <$jobName> finished, cost ${costTime}ms")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!disposed.get()) logger.warn("background job <$jobName> failed", e)
            }
        })
    }

    fun <T> runAsyncSafe(jobName: String, action: CoroutineScope.() -> T): Deferred<T?> {
        val inheritedOwner = runtimeTaskCoordinator.captureOwner()
        return track(coroutineScope.async {
            runtimeTaskCoordinator.withOwnerContext(inheritedOwner) {
                try {
                    if (disposed.get()) return@withOwnerContext null
                    logger.debug("async job <$jobName> start")
                    action()
                } catch (e: Exception) {
                    logger.warn("async job <$jobName> failed", e)
                    null
                }
            }
        })
    }

    fun runTaskSafe(
        jobName: String,
        action: Runnable,
        isNeedShowIndicator: Boolean = true,
        isProjectWrite: Boolean = true,
        isBlockIncrementalCompile: Boolean = isProjectWrite,
    ) {
        require(isProjectWrite || !isBlockIncrementalCompile) {
            "A task cannot block incremental compile without holding the project write lock"
        }
        if (disposed.get()) return
        val title = "Jugg: $jobName"
        val inheritedOwner = runtimeTaskCoordinator.captureOwner()
        hostTaskExecutor.submit(title, "Jugg: Stopping $jobName...", isNeedShowIndicator) {
            runtimeTaskCoordinator.withOwnerContext(inheritedOwner) {
                executeTask(jobName, action, isProjectWrite, isBlockIncrementalCompile)
            }
        }
    }

    fun <T> runProjectWriteLocked(jobName: String, action: () -> T): T {
        return runCoordinatedProjectWriteTransaction(jobName, action)
    }

    /** Runs a project transaction only when no unrelated local owner or other runtime owns the project. */
    fun <T : Any> tryRunProjectWriteLocked(jobName: String, action: () -> T): T? {
        return runtimeTaskCoordinator.tryWithLock {
            executionLockManager.tryWithProjectLock(jobName) {
                runClaimedProjectAction(action)
            }
        }
    }

    @Synchronized
    fun consumeRuntimeOwnerChange(): RuntimeOwnerChangeEvent? {
        val change = runtimeOwnerChange
        runtimeOwnerChange = null
        return change
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
        isBlockIncrementalCompile: Boolean,
    ) {
        if (disposed.get()) return
        val report = ReportEventData()
        val startTime = System.currentTimeMillis()
        try {
            logger.debug("job <$jobName> start")
            runTaskWriteLocked(jobName, isProjectWrite, isBlockIncrementalCompile, action)
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
            isBlockIncrementalCompile,
        )
    }

    private fun runWithIncrementalCompileState(action: Runnable) {
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
                isBlockIncrementalCompile = isBlockIncrementalCompile,
            )
        }
    }

    private fun runTaskWriteLocked(
        jobName: String,
        isProjectWrite: Boolean,
        isBlockIncrementalCompile: Boolean,
        action: Runnable,
    ) {
        when {
            isProjectWrite && isBlockIncrementalCompile -> runCoordinatedProjectWriteTransaction(jobName) {
                runWithIncrementalCompileState(action)
            }
            isProjectWrite -> runInheritedProjectWriteTransaction(jobName, action::run)
            else -> action.run()
        }
    }

    private fun <T> runBackgroundWriteLocked(
        jobName: String,
        isProjectWrite: Boolean,
        action: () -> T,
    ): T {
        return when {
            isProjectWrite -> runCoordinatedProjectWriteTransaction(jobName, action)
            else -> action()
        }
    }

    private fun <T> runCoordinatedProjectWriteTransaction(jobName: String, action: () -> T): T {
        return runtimeTaskCoordinator.withLock {
            runProjectWriteTransaction(jobName, action)
        }
    }

    private fun <T> runInheritedProjectWriteTransaction(jobName: String, action: () -> T): T {
        return runtimeTaskCoordinator.withInheritedLock {
            runProjectWriteTransaction(jobName, action)
        }
    }

    private fun <T> runProjectWriteTransaction(jobName: String, action: () -> T): T {
        return executionLockManager.withProjectLock(jobName) {
            runClaimedProjectAction(action)
        }
    }

    private fun <T> runClaimedProjectAction(action: () -> T): T {
        val identity = runtimeIdentity
        val ownerStore = runtimeOwnerStore
        if (identity != null && ownerStore != null) {
            try {
                ownerStore.claim(identity, logger)?.let { change ->
                    synchronized(this) {
                        runtimeOwnerChange = change
                    }
                }
            } catch (e: Exception) {
                logger.warn("persist runtime owner failed", e)
            }
        }
        return action()
    }

    private fun <T : Job> track(job: T): T {
        backgroundJobs.add(job)
        job.invokeOnCompletion { backgroundJobs.remove(job) }
        return job
    }

    companion object {
        private val runtimeOwnerTypes = setOf("idea", "standalone")
    }
}

/** Serializes unrelated runtime tasks while allowing child tasks to share their parent's logical owner. */
private class RuntimeTaskCoordinator {
    private val stateLock = ReentrantLock(true)
    private val ownerReleased = stateLock.newCondition()
    private val threadOwner = ThreadLocal<Any?>()
    private var activeOwner: Any? = null
    private var referenceCount = 0

    fun captureOwner(): Any? = threadOwner.get()

    fun <T> withOwnerContext(owner: Any?, action: () -> T): T {
        if (owner == null) return action()
        val previousOwner = threadOwner.get()
        threadOwner.set(owner)
        try {
            return action()
        } finally {
            if (previousOwner == null) threadOwner.remove() else threadOwner.set(previousOwner)
        }
    }

    fun <T> withLock(action: () -> T): T {
        val owner = threadOwner.get() ?: Any()
        acquire(owner)
        try {
            return withOwnerContext(owner, action)
        } finally {
            release(owner)
        }
    }

    fun <T> withInheritedLock(action: () -> T): T {
        if (threadOwner.get() == null) return action()
        return withLock(action)
    }

    fun <T : Any> tryWithLock(action: () -> T?): T? {
        val owner = threadOwner.get() ?: Any()
        if (!tryAcquire(owner)) return null
        try {
            return withOwnerContext(owner, action)
        } finally {
            release(owner)
        }
    }

    private fun acquire(owner: Any) {
        stateLock.lockInterruptibly()
        try {
            while (activeOwner != null && activeOwner !== owner) ownerReleased.await()
            activeOwner = owner
            referenceCount++
        } finally {
            stateLock.unlock()
        }
    }

    private fun tryAcquire(owner: Any): Boolean {
        if (!stateLock.tryLock()) return false
        try {
            if (activeOwner != null && activeOwner !== owner) return false
            activeOwner = owner
            referenceCount++
            return true
        } finally {
            stateLock.unlock()
        }
    }

    private fun release(owner: Any) {
        stateLock.lock()
        try {
            check(activeOwner === owner && referenceCount > 0)
            referenceCount--
            if (referenceCount == 0) {
                activeOwner = null
                ownerReleased.signalAll()
            }
        } finally {
            stateLock.unlock()
        }
    }
}
