package com.sickworm.intellij.jugg.logger

import com.intellij.openapi.diagnostic.Logger

/**
 * TimeLogger wall-clock stopwatch utility keyed by string tags.
 * Collaboration: Used by compile/deploy stages around [start], [end], and [getCostTime].
 * Data Contract: [start] overwrites any existing tag value; [getCostTime] returns `-1` when no start time exists.
 */
object TimeLogger {

    private val timeMap = mutableMapOf<String, Long>()

    fun start(tag: String) {
        timeMap[tag] = System.currentTimeMillis()
    }

    fun end(tag: String, logger: Logger): Long {
        val costTime = getCostTime(tag)
        logger.debug("$tag cost $costTime ms")
        return costTime
    }

    fun getCostTime(tag: String): Long {
        val startTime = timeMap[tag] ?: return -1
        return System.currentTimeMillis() - startTime
    }
}
