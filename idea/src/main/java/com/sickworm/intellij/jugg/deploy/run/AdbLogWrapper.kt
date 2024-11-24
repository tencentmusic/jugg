package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.idea.log.LogWrapper
import com.intellij.openapi.diagnostic.Logger

/**
 * only print warning and error
 */
class AdbLogWrapper(logger: Logger) : LogWrapper(logger) {

    var isDeployTimeout = false
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
        checkDeployTimeout(msgFormat)
    }

    override fun error(t: Throwable?, msgFormat: String?, vararg args: Any?) {
        super.error(t, msgFormat, *args)
        checkDeployTimeout(msgFormat)
    }

    override fun verbose(msgFormat: String?, vararg args: Any?) {
        super.verbose(msgFormat, *args)
        checkDeployTimeout(msgFormat)
    }

    private fun checkDeployTimeout(msgFormat: String?) {
        if (msgFormat?.contains("MessagePipeWrapper read() timeout") == true) {
            isDeployTimeout = true
        }
    }
}