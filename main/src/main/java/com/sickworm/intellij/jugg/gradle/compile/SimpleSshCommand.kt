package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.diagnostic.Logger

/**
 * Lightweight SSH command wrapper with optional output filtering and secure-log masking.
 */
class SimpleSshCommand(
    override val baseCommand: String,
    private val outputFilter: ((String, Boolean) -> Boolean)? = null,
    private val isSecureCommand: Boolean = false,
): BaseSshCommand() {

    // keep old constructor, avoid NoSuchMethodError for custom_compilers
    @Suppress("UNUSED_PARAMETER")
    constructor(baseCommand: String, logger: Logger, outputFilter: ((String, Boolean) -> Boolean)? = null, isSecureCommand: Boolean = false)
       : this(baseCommand, outputFilter, isSecureCommand)

    override fun isCanOutput(line: String, isError: Boolean): Boolean {
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
