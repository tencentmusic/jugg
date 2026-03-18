package com.sickworm.intellij.jugg.project

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.ContinuationInterceptor

interface IBackgroundTaskRunner {
    val dispatcher: CoroutineDispatcher
        get() = Dispatchers.Default

    /** Returns true if the current thread is the Event Dispatch Thread. */
    val isOnEdt: Boolean
        get() = false

    fun runBackgroundSafe(jobName: String, isNeedLog: Boolean = true, action: Runnable): Job
    fun runBackgroundSafe(jobName: String, delayMs: Long, isNeedLog: Boolean = true, action: Runnable): Job
}

class CoroutineBackgroundTaskRunner(
    private val coroutineScope: CoroutineScope,
) : IBackgroundTaskRunner {
    override val dispatcher: CoroutineDispatcher
        get() = coroutineScope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher ?: Dispatchers.Default

    override fun runBackgroundSafe(jobName: String, isNeedLog: Boolean, action: Runnable): Job {
        return runBackgroundSafe(jobName, 0L, false, action)
    }

    override fun runBackgroundSafe(jobName: String, delayMs: Long, isNeedLog: Boolean, action: Runnable): Job {
        return coroutineScope.launch {
            if (delayMs > 0L) {
                delay(delayMs)
            }
            action.run()
        }
    }
}
