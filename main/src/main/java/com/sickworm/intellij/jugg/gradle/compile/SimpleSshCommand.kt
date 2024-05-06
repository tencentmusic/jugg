package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.diagnostic.Logger

class SimpleSshCommand(
    override val baseCommand: String,
    val logger: Logger,
    override val isSecureCommand: Boolean = false,
): BaseSshCommand() {


    val terminalOutputListener = object : IGradleCompileClient.TerminalOutputListener {
        override fun onOutput(line: String, isNeedPrint: Boolean) {
            // no-op
        }

        override fun onOutputErr(line: String) {
            // no-op
        }

    }
}