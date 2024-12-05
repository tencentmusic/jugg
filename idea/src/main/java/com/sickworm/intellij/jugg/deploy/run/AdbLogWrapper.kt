package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.idea.log.LogWrapper
import com.intellij.openapi.diagnostic.Logger

/**
 * only print warning and error
 */
class AdbLogWrapper(private val logger: Logger) : LogWrapper(logger) {

    var realErrorMessage: String? = null
        private set

    init {
        alwaysLogAsDebug(true)
        allowVerbose(true)
    }

    override fun info(msgFormat: String?, vararg args: Any?) {
        verbose(msgFormat, *args)
    }

    override fun warning(msgFormat: String?, vararg args: Any?) {
        super.warning(msgFormat, *args)
        checkMessage(msgFormat, *args)
    }

    override fun error(t: Throwable?, msgFormat: String?, vararg args: Any?) {
        super.error(t, msgFormat, *args)
        checkMessage(msgFormat, *args)
    }

    override fun verbose(msgFormat: String?, vararg args: Any?) {
        super.verbose(msgFormat, *args)
        checkMessage(msgFormat, *args)
    }

    private fun checkMessage(msgFormat: String?, vararg args: Any?) {
        msgFormat ?: return
        val message = String.format(msgFormat, *args)
        if (message.contains("MessagePipeWrapper read() timeout")) {
            realErrorMessage = message
        } else if (message.contains("device") && message.contains("not found")) {
            realErrorMessage = message
        }
    }
}