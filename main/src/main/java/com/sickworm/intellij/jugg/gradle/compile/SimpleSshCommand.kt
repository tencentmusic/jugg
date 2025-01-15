package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.diagnostic.Logger

class SimpleSshCommand(
    override val baseCommand: String,
    val logger: Logger,
    private val outputFilter: ((String) -> Boolean)? = null,
    private val isSecureCommand: Boolean = false,
): BaseSshCommand() {

    override fun isCanOutput(line: String): Boolean {
        return outputFilter?.invoke(line) ?: true
    }

    override fun getPrintSafeCommand(isNeedSetChineseLanguage: Boolean, isWindows: Boolean): String {
        return if (isSecureCommand) {
            "(secure)"
        } else {
            baseCommand
        }
    }
}