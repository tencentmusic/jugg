package com.sickworm.intellij.jugg.gradle.compile

import java.io.File
import java.util.concurrent.CancellationException

/** Optional diagnostics only; real transfers remain authoritative and are never retried here. */
internal fun probeWindowsRsync(root: File, executor: CmdExecutor, environment: Map<String, String>): Boolean {
    var available = true
    for ((tool, argument) in listOf("rsync" to "--version", "ssh" to "-V")) {
        val result = try {
            executor.invokeProcess(listOf(File(root, "$tool.exe").absolutePath, argument), environment, quietOnSuccess = true)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw exception
        } catch (exception: Exception) {
            // The executor records failure details; a broken probe must not block real work.
            available = false
            continue
        }
        if (result == IGradleCompileClient.Error.ERROR_CANCELED) return false
        available = available && result == 0
    }
    return available
}
