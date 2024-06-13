package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.diagnostic.Logger

class SimpleSshCommand(
    override val baseCommand: String,
    val logger: Logger,
    private val outputFilter: ((String) -> Boolean)? = null,
    override val isSecureCommand: Boolean = false,
): BaseSshCommand() {

    override fun isCanOutput(line: String): Boolean {
        return outputFilter?.invoke(line) ?: true
    }
}