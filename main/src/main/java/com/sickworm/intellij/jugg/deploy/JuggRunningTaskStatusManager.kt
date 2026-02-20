package com.sickworm.intellij.jugg.deploy

/**
 * IJuggRunningTaskStatusManager tracks first-run state across deploy executions and devices.
 */
interface IJuggRunningTaskStatusManager {

    fun isFirstTimeRun(runningDevice: String? = null): Boolean

    fun setHasRun(runningDevice: String?)

    fun resetHasRun()

}

/**
 * Tracks whether current deploy loop is the first run, scoped by selected device.
 */
class JuggRunningTaskStatusManager : IJuggRunningTaskStatusManager {

    private var isFirstTimeRun: Boolean = true

    private var lastRunningDevice: String = ""

    override fun isFirstTimeRun(runningDevice: String?): Boolean {
        return if (runningDevice == null) {
            isFirstTimeRun
        } else if (lastRunningDevice != runningDevice) {
            true
        } else {
            isFirstTimeRun
        }
    }

    override fun setHasRun(runningDevice: String?) {
        lastRunningDevice = runningDevice ?: INVALID_DEVICE
        isFirstTimeRun = false
    }

    override fun resetHasRun() {
        isFirstTimeRun = true
        lastRunningDevice = INVALID_DEVICE
    }

    companion object {

        private const val INVALID_DEVICE = "invalid_device"

    }
}
