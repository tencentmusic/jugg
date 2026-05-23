package com.sickworm.intellij.jugg.deploy.run

import com.android.ddmlib.IDevice
import com.intellij.openapi.progress.ProgressIndicator
import com.sickworm.intellij.jugg.compiler.CompileUiHandler
import com.sickworm.intellij.jugg.deploy.instrument.AndroidTestResultModel
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
    /** When false, retry falls back to Apply Changes instead of direct overlay writer. */
    val isAllowDirectOverlayDeploy: Boolean = true,
    val androidTestRunSpec: AndroidTestRunSpec? = null,
    val androidTestResultModel: AndroidTestResultModel? = null,
    val startTime: Long = System.currentTimeMillis(),
    var timeOutRetryTimes: Int = 0,
) {

    fun costTime(): Long {
        return System.currentTimeMillis() - startTime
    }
}

data class DeployTaskResult(
    val isSuccess: Boolean,
    val costTime: Long,
    val isCanFallback: Boolean = false,
    val deployType: JuggDeployData.DeployType? = null,
    val failedReason: String? = null,
    val costTimeExceptCheck: Long = costTime,
    /** false when deploy data has no incremental products (no classes, no overlays). */
    val hasDeployChanges: Boolean = true,
)

/**
 * Parameters for a single [JuggDeployerHelper] device deploy task execution.
 */
data class JuggDeployRunTaskRequest(
    val device: IDevice,
    val data: JuggDeployData,
    val compileUiHandler: CompileUiHandler,
    val isSkipExceptOverlayCheck: Boolean = false,
    val isMultipleDevices: Boolean = false,
    val isLastDevice: Boolean = false,
    val androidTestRunSpec: AndroidTestRunSpec? = null,
    val androidTestResultModel: AndroidTestResultModel? = null,
    val isDeviceReadyDeploy: Boolean = true,
    val isAllowDirectOverlayDeploy: Boolean = true,
    /** When true, skip app restart/start after this task; a follow-up deploy will launch the app. */
    val deferPostDeployLaunch: Boolean = false,
) {
    companion object {
        fun fromDeployOptions(
            deployOptions: DeployOptions,
            data: JuggDeployData,
            isSkipExceptOverlayCheck: Boolean = deployOptions.isSkipExceptOverlayCheck,
            isDeviceReadyDeploy: Boolean = true,
        ): JuggDeployRunTaskRequest {
            return JuggDeployRunTaskRequest(
                device = deployOptions.device,
                data = data,
                compileUiHandler = deployOptions.compileUiHandler,
                isSkipExceptOverlayCheck = isSkipExceptOverlayCheck,
                isMultipleDevices = deployOptions.isMultipleDevices,
                isLastDevice = deployOptions.isLastDevice,
                androidTestRunSpec = deployOptions.androidTestRunSpec,
                androidTestResultModel = deployOptions.androidTestResultModel,
                isDeviceReadyDeploy = isDeviceReadyDeploy,
                isAllowDirectOverlayDeploy = deployOptions.isAllowDirectOverlayDeploy,
            )
        }
    }
}
