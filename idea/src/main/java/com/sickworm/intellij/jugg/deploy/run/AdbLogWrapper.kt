package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.idea.log.LogWrapper
import com.intellij.openapi.diagnostic.Logger

/**
 * only print warning and error
 */
class AdbLogWrapper(logger: Logger) : LogWrapper(logger) {
    init {
        alwaysLogAsDebug(true)
        allowVerbose(true)
    }

    override fun info(msgFormat: String?, vararg args: Any?) {
        verbose(msgFormat, *args)
    }
}