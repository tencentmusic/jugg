package com.sickworm.intellij.jugg.apk

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.gradle.compile.CmdExecutor
import com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient
import com.sickworm.intellij.jugg.gradle.compile.SimpleSshCommand
import java.io.File

/**
 * Runs a user-configured APK sign script on the rewritten APK in place.
 * Collaboration: Created by the incremental deploy flow and invoked by [ApkFileModifier] after zipalign.
 * Data Contract: The configured command receives the absolute APK path as its only added argument, streams output
 * to the Run window, and must sign the APK in place; verification stays in [ApkFileModifier].
 */
class CustomApkSignScriptRunner(
    private val script: String,
    private val projectDir: File,
    private val envArray: List<String>?,
    private val compileUiHandler: CompileUiHandler,
    private val logger: Logger,
) {

    private val cmdExecutor = CmdExecutor(logger, object : IGradleCompileClient.TerminalOutputListener {
        override fun onOutput(line: String, isNeedPrint: Boolean) {
            compileUiHandler.onDeployUiMessage(line)
        }

        override fun onOutputErr(line: String) {
            compileUiHandler.onDeployUiMessage(line)
        }
    })

    fun run(apkFile: File) {
        val command = "$script ${shellEscapeArgument(apkFile.absolutePath)}"
        compileUiHandler.updateIndicatorText("Running custom APK sign script...")
        cmdExecutor.prepareForInvoke()
        compileUiHandler.listenCancelAction(cmdExecutor::release)
        if (compileUiHandler.isCanceled) {
            cmdExecutor.release()
            compileUiHandler.listenCancelAction(null)
            throw IllegalStateException("Custom APK sign script canceled.")
        }
        logger.info("Custom APK sign script started.")
        val exitCode = try {
            cmdExecutor.invoke(SimpleSshCommand(command, isSecureCommand = true), envArray, null, projectDir)
        } finally {
            compileUiHandler.listenCancelAction(null)
        }
        if (compileUiHandler.isCanceled) {
            throw IllegalStateException("Custom APK sign script canceled.")
        }
        if (exitCode != 0) {
            throw IllegalStateException("Custom APK sign script failed with exit code $exitCode.")
        }
        logger.info("Custom APK sign script finished.")
    }
}
