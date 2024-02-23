package com.sickworm.intellij.jugg.logger

import com.intellij.openapi.diagnostic.Logger

object TimeLogger {

    private val timeMap = mutableMapOf<String, Long>()

    fun start(tag: String) {
        timeMap[tag] = System.currentTimeMillis()
    }

    fun end(tag: String, logger: Logger) {
        val startTime = timeMap[tag] ?: return
        val costTime = System.currentTimeMillis() - startTime
        logger.debug("$tag cost $costTime ms")
    }
}