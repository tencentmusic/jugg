package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.apk.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
import com.sickworm.intellij.jugg.deploy.instrument.InstrumentCommandBuilder
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.platform.PlatformApi
import java.io.File

/**
 * Wraps frequently used adb shell commands for app lifecycle and runtime diagnostics.
 */
class AdbCmdHelper(
    private val adb: IDeviceAdb,
    loggerArg: Logger,
) {

    private val logger = loggerArg.getInstance("AdbCmdHelper")

    @Suppress("MemberVisibilityCanBePrivate")
    fun startApp(packageName: String, launchedActivity: String, isRestart: Boolean = true, isDebug: Boolean = false) {
        logger.debug("startApp: $packageName, $launchedActivity")
        val restartFlag = if (isRestart) "-S" else ""
        val debugFlag = if (isDebug) "-D" else ""
        val flags = listOf(debugFlag, restartFlag).filter { it.isNotBlank() }.joinToString(" ")
        val flagPart = if (flags.isBlank()) "" else "$flags "
        execAdbShellCmd("am start ${flagPart}-n $packageName/$launchedActivity")
    }

    fun startDefaultApp(packageName: String, apks: List<ApkInfo>, isRestart: Boolean = true, isDebug: Boolean = false) {
        logger.debug("startDefaultApp: $packageName, apks: $apks, isRestart: $isRestart")
        val apkFiles = apks.flatMap { it.files }.map { it.apkFile }
        var launchedActivity: String? = null
        apkFiles.find { apkFile ->
            launchedActivity = adb.getDefaultLaunchActivity(apkFile)
            if (launchedActivity != null) {
                logger.debug("found default launch activity: $launchedActivity in ${apkFile.path}")
            }
            return@find launchedActivity != null
        }
        if (launchedActivity == null) {
            logger.warn("No default launch activity found for $packageName, won't start App.")
            return
        }
        startApp(packageName, launchedActivity!!, isRestart, isDebug)
    }

    fun stopApp(packageName: String) {
        logger.debug("stopApp: $packageName")
        execAdbShellCmd("am force-stop $packageName")
    }

    fun isAppForeground(packageName: String): Boolean {
        var startFind = false
        val result = execAdbShellCmd("dumpsys activity recents")
        result.lines().forEach {
            if (it.startsWith("  * Recent #0")) {
                startFind = true
            } else if (it.startsWith("  * Recent #")) {
                // reach end
                return false
            }

            if (startFind) {
                if (it.contains("$packageName/")) {
                    return true
                }
            }
        }
        return false
    }

    fun isAppInstalled(packageName: String): Boolean {
        val result = execAdbShellCmd("pm path $packageName")
        return result.lineSequence().any { it.startsWith("package:") }
    }

    fun dumpErrorLog(limit: Int = 100000): String {
        logger.debug("dumpErrorLog: $limit")
        return execAdbShellCmd("logcat -t$limit")
    }

    fun deleteDeployedDexFile(packageName: String, filePath: String) {
        logger.debug("deleteDeployedDexFile: $packageName, $filePath")
        execAdbShellCmd("run-as $packageName rm -rf /data/data/$packageName/code_cache/.overlay/$filePath")
    }

    /**
     * Runs `am instrument` with the given [spec] against [testApk] and streams output line-by-line
     * to [lineConsumer]. Returns when the instrumentation process finishes or [cancelSignal] fires.
     *
     * @return Shell exit code (0 = success).
     */
    fun runInstrumentation(
        spec: AndroidTestRunSpec,
        testApk: ApkInfo,
        lineConsumer: (String) -> Unit,
        cancelSignal: () -> Boolean,
    ): Int {
        val cmd = InstrumentCommandBuilder.build(spec, testApk)
        logger.info("runInstrumentation: $cmd")
        return adb.execAdbShellCmdStreaming(cmd, lineConsumer, cancelSignal)
    }

    private fun execAdbShellCmd(cmd: String): String {
        return adb.execAdbShellCmd(cmd)
    }

    companion object {

        fun findAdbExecutablePath(logger: Logger = Logger.getInstance("McpActionRuntime")): String {
            val candidates = mutableListOf<File>()
            val androidHomeCandidates = listOfNotNull(
                PlatformApi.getAndroidHomePath(logger),
                System.getenv("ANDROID_HOME"),
                System.getenv("ANDROID_SDK_ROOT"),
            )

            androidHomeCandidates.forEach { home ->
                candidates += File(home, "platform-tools/adb")
                candidates += File(home, "platform-tools/adb.exe")
            }

            return candidates.firstOrNull { it.exists() && it.canExecute() }?.absolutePath
                ?: candidates.firstOrNull { it.exists() }?.absolutePath
                ?: "adb"
        }

    }
}
