package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.isMac
import com.sickworm.intellij.jugg.compiler.isWindows
import com.sickworm.intellij.jugg.logger.TimeLogger

/**
 * rsync 2.6.9 on macOS is not compatible.
 * Problems:
 * 1. files not synced in local gradle compilation sync
 * 2. out-of-dated files not deleted in remote gradle compilation sync
 *
 * Solutions:
 * Priority use embedded rsync if compatible, otherwise use system rsync.
 */
object RsyncCompatibleHelper {


    var isCompatible: Boolean = true
        private set

    var rsyncPath: String = getSystemRsyncPath()
        private set

    private var isInit: Boolean = false

    @Synchronized
    fun init(logger: Logger) {
        if (isInit) {
            return
        }
        if (isWindows) {
            isCompatible = false
            isInit = true
            return
        }

        rsyncPath = getRsyncPath(logger)
        isCompatible = isCompatible(logger)
        isInit = true
    }

    private fun isCompatible(logger: Logger): Boolean {
        val isCompatible: Boolean
        @Suppress("LiftReturnOrAssignment")
        if (rsyncPath == RsyncCommand.getRsyncPath()) {
            isCompatible = true
        } else {
            isCompatible = detectRsyncCompatible(getSystemRsyncPath(), logger)
        }

        logger.debug("rsync compatible: $isCompatible")
        return isCompatible
    }

    private fun getRsyncPath(logger: Logger): String {
        if (!isMac) {
            return getSystemRsyncPath() // embedded rsync only support mac for now
        }

        TimeLogger.start("getRsyncPath")
        val embeddedRsyncPath = getEmbeddedRsyncPath()
        val isCompatible = detectRsyncCompatible(embeddedRsyncPath, logger)
        TimeLogger.end("getRsyncPath", logger)

        rsyncPath = if (isCompatible) {
            embeddedRsyncPath
        } else {
            "rsync" // use system rsync
        }
        logger.debug("rsync path: $rsyncPath")
        return rsyncPath
    }

    private fun detectRsyncCompatible(rsyncPath: String, logger: Logger): Boolean {
        val cmd = SimpleSshCommand("$rsyncPath --version", logger)
        val outputBuilder = StringBuilder()
        val outputListener = object : IGradleCompileClient.TerminalOutputListener {
            override fun onOutput(line: String, isNeedPrint: Boolean) {
                outputBuilder.appendLine(line)
            }

            override fun onOutputErr(line: String) {
                outputBuilder.appendLine(line)
            }
        }
        val result = CmdExecutor(logger, outputListener).invoke(cmd)
        if (result != 0) {
            logger.debug("rsync not compatible for command not run successfully.")
            return false
        }

        val output = outputBuilder.toString()
        // requires protocol version 30 or higher, because I didn't test low version.
        if (output.contains(Regex("protocol +version [12]\\d"))) {
            logger.debug("rsync not compatible for protocol version not match.")
            return false
        }
        // requires version 3.0 or higher, because I didn't test low version.
        if (output.contains(Regex("rsync +version [12]\\."))) {
            logger.debug("rsync not compatible for version is too low.")
            return false
        }
        logger.debug("rsync compatible.")
        return true
    }

    private fun getEmbeddedRsyncPath(): String {
        return RsyncCommand.getRsyncPath()
    }

    private fun getSystemRsyncPath(): String {
        return "rsync"
    }
}