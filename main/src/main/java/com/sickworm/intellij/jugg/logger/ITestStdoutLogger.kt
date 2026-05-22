package com.sickworm.intellij.jugg.logger

import com.intellij.openapi.diagnostic.Logger

/**
 * Test stdout logger that supports [Logger.getInstance] tag derivation without [JuggLogger.register].
 */
interface ITestStdoutLogger {

    fun deriveTag(tag: String): Logger
}
