package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.intellij.openapi.progress.ProgressIndicator
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestRunSpec
import com.sickworm.intellij.jugg.ide.bean.IProcessHandler

data class DeployOptions(
    val device: IDevice,
    val isLastDevice: Boolean,
    val isMultipleDevices: Boolean = false,
    val processHandler: IProcessHandler? = null,
    val indicator: ProgressIndicator? = null,
    val isInstall: Boolean = false,
    val isWarmUp: Boolean = false,
    val compileUiHandler: CompileUiHandler = CompileUiHandler.DEFAULT,
    val retryReason: String? = null,
    val isSkipExceptOverlayCheck: Boolean = false,
    val retryDeployData: JuggDeployData? = null,
    val androidTestRunSpec: AndroidTestRunSpec? = null,
    val startTime: Long = System.currentTimeMillis(),
    var timeOutRetryTimes: Int = 0,
) {

    fun costTime(): Long {
        return System.currentTimeMillis() - startTime
    }
}
