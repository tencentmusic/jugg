package com.sickworm.intellij.jugg.cmdline.standalone

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Stops the daemon after external inactivity while allowing active work to finish. */
class DaemonIdleTimer(
    private val activity: StandaloneDaemonActivity,
    private val idleTimeoutMillis: Long,
    private val recheckMillis: Long,
    private val onIdle: () -> Unit,
) : AutoCloseable {
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "jugg-standalone-idle").apply { isDaemon = true }
    }
    private val started = AtomicBoolean()

    @Volatile
    private var lastExternalActivityMillis = System.currentTimeMillis()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scheduler.scheduleWithFixedDelay(::checkIdle, recheckMillis, recheckMillis, TimeUnit.MILLISECONDS)
    }

    fun recordExternalActivity(nowMillis: Long = System.currentTimeMillis()) {
        lastExternalActivityMillis = nowMillis
    }

    fun shouldExit(nowMillis: Long = System.currentTimeMillis()): Boolean {
        return nowMillis - lastExternalActivityMillis >= idleTimeoutMillis && !activity.isBusy()
    }

    private fun checkIdle() {
        if (shouldExit()) {
            onIdle()
        }
    }

    override fun close() {
        scheduler.shutdownNow()
    }
}
