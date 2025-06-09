package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.diagnostic.Logger

class SimpleSshCommand(
    override val baseCommand: String,
    val logger: Logger,
    private val outputFilter: ((String, Boolean) -> Boolean)? = null,
    private val isSecureCommand: Boolean = false,
    var isAllDebug: Boolean = false,
): BaseSshCommand() {

    // keep old constructor, avoid NoSuchMethodError for custom_compilers
    constructor(baseCommand: String, logger: Logger, outputFilter: ((String, Boolean) -> Boolean)? = null, isSecureCommand: Boolean = false)
       : this(baseCommand, logger, outputFilter, isSecureCommand, isAllDebug = false)

    override fun isCanOutput(line: String, isError: Boolean): Boolean {
        if (isAllDebug) {
            if (isError) {
                logger.debug(line)
                return false
            }
        }
        return outputFilter?.invoke(line, isError) ?: true
    }

    override fun getPrintSafeCommand(isNeedSetChineseLanguage: Boolean, isWindows: Boolean): String {
        return if (isSecureCommand) {
            "(secure)"
        } else {
            baseCommand
        }
    }
}