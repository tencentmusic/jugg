package com.sickworm.intellij.jugg.cmdline.standalone

import java.util.concurrent.atomic.AtomicInteger

/** Tracks standalone work that must delay daemon idle shutdown. */
class StandaloneDaemonActivity {
    private val activeJobs = AtomicInteger()
    private val activeProjectWrites = AtomicInteger()
    private val activeUpdateDownloads = AtomicInteger()

    val isCompiling: Boolean
        get() = activeJobs.get() > 0

    fun beginJob() {
        activeJobs.incrementAndGet()
    }

    fun endJob() {
        activeJobs.decrementSafely()
    }

    fun beginProjectWrite() {
        activeProjectWrites.incrementAndGet()
    }

    fun endProjectWrite() {
        activeProjectWrites.decrementSafely()
    }

    fun beginUpdateDownload() {
        activeUpdateDownloads.incrementAndGet()
    }

    fun endUpdateDownload() {
        activeUpdateDownloads.decrementSafely()
    }

    fun isBusy(): Boolean {
        return activeJobs.get() > 0 || activeProjectWrites.get() > 0 || activeUpdateDownloads.get() > 0
    }

    private fun AtomicInteger.decrementSafely() {
        updateAndGet { value -> if (value > 0) value - 1 else 0 }
    }
}
