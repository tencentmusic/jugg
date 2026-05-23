package com.sickworm.intellij.jugg.deploy.run.deployflow

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Executes run-as inner scripts on the host shell with virtual device path rewriting.
 *
 * Complements marker-based Kotlin handlers: generic run-as scripts (e.g. AS startup agent
 * SetUpAgent) run through `/bin/sh` so quoting/syntax errors surface like on a real device.
 */
internal object VirtualDeployShellExecutor {

    fun parseRunAsShC(cmd: String): Pair<String, String>? {
        if (!cmd.startsWith("run-as ")) {
            return null
        }
        val shMarker = " sh -c '"
        val markerIndex = cmd.indexOf(shMarker)
        if (markerIndex < 0 || !cmd.endsWith("'")) {
            return null
        }
        val packageName = cmd.substring("run-as ".length, markerIndex)
        val innerScript = cmd.substring(markerIndex + shMarker.length, cmd.length - 1)
        return packageName to innerScript
    }

    fun wrapLikeIdeaDeviceAdb(cmd: String): String {
        val escaped = cmd.replace("'", "'\\''")
        return "sh -c '$escaped'"
    }

    fun rewriteDevicePaths(script: String, deviceRoot: File): String {
        val rootPath = deviceRoot.absolutePath
        return script.replace("/data/local/tmp/", "$rootPath/data/local/tmp/")
    }

    fun executeRunAsInner(device: VirtualDeployDevice, innerScript: String): String {
        device.packageDataDir().mkdirs()
        val rewritten = rewriteDevicePaths(innerScript, device.root)
        val process = ProcessBuilder("/bin/sh", "-c", rewritten)
            .directory(device.packageDataDir())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return "shell timeout"
        }
        return output.trim()
    }

    private const val SHELL_TIMEOUT_SECONDS = 10L
}
