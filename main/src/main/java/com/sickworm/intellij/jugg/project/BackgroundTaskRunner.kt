package com.sickworm.intellij.jugg.project

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

interface IBackgroundTaskRunner {
    fun runBackgroundSafe(jobName: String, action: Runnable): Job
    fun runBackgroundSafe(jobName: String, delayMs: Long, action: Runnable): Job
}

class CoroutineBackgroundTaskRunner(
    private val coroutineScope: CoroutineScope,
) : IBackgroundTaskRunner {
    override fun runBackgroundSafe(jobName: String, action: Runnable): Job {
        return runBackgroundSafe(jobName, 0L, action)
    }

    override fun runBackgroundSafe(jobName: String, delayMs: Long, action: Runnable): Job {
        return coroutineScope.launch {
            if (delayMs > 0L) {
                delay(delayMs)
            }
            action.run()
        }
    }
}
