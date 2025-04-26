package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.intellij.openapi.progress.ProgressIndicator
import com.sickworm.intellij.jugg.ide.bean.IProcessHandler

data class DeployOptions(
    val device: IDevice,
    val isLastDevice: Boolean,
    val processHandler: IProcessHandler? = null,
    val indicator: ProgressIndicator? = null,
    val isInstall: Boolean = false,
    val isWarmUp: Boolean = false,
    val retryReason: String? = null,
    val isSkipExceptOverlayCheck: Boolean = false,
    val retryDeployData: JuggDeployData? = null,
    val startTime: Long = System.currentTimeMillis(),
    var timeOutRetryTimes: Int = 0,
)