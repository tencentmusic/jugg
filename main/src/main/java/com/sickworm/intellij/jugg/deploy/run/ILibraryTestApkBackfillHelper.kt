package com.sickworm.intellij.jugg.deploy.run

import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec

/** Supplies the host-specific Library Test APK build only when an AndroidTest run needs it. */
interface ILibraryTestApkBackfillHelper {
    fun backfillIfNeeded(
        spec: AndroidTestRunSpec?,
        data: JuggDeployData,
        uiHandler: CompileUiHandler,
        installBackfilledApks: (List<ApkInfo>) -> Unit = {},
    ): JuggDeployData

    companion object {
        val NONE = object : ILibraryTestApkBackfillHelper {
            override fun backfillIfNeeded(
                spec: AndroidTestRunSpec?, data: JuggDeployData, uiHandler: CompileUiHandler,
                installBackfilledApks: (List<ApkInfo>) -> Unit,
            ): JuggDeployData = data
        }
    }
}
