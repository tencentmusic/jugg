package com.sickworm.intellij.jugg.platform

import com.android.ddmlib.IDevice
import com.android.tools.deployer.model.Apk
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.apk.manifest.ManifestActivityInfo
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.git.IFileMatcher
import com.sickworm.intellij.jugg.git.IGitManager
import com.sickworm.intellij.jugg.ide.bean.ConfirmResult
import com.sickworm.intellij.jugg.mcp.McpJsonRpcRequest
import com.sickworm.intellij.jugg.mcp.McpJsonRpcResponse
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResult
import java.io.File

/**
 * IPlatformApi abstraction boundary for UI, device, environment, and gradle integration calls.
 * Collaboration: Implemented by host-layer adapters and consumed through [PlatformApi] delegation in core modules.
 */
interface IPlatformApi {

    fun showDialog(
        title: String,
        content: String,
        okButtonText: String? = null,
        cancelButtonText: String? = null,
        isShowCancelButton: Boolean = true,
    ): Boolean

    fun showChangeConfirmDialog(
        diffResult: DependencyDiffResult?,
        isRunLater: Boolean,
        logger: Logger,
    ): ConfirmResult

    fun showUserAndPasswordInputDialog(
        content: String,
        subTitle: String? = null,
        isPassword: Boolean = false,
        defaultInputText: String? = null,
        title: String? = null,
    ): String?

    fun allAvailableJavaHomes(): List<String>

    fun getGradleJdkPath(project: Project, logger: Logger): String?

    fun getAndroidHomePath(logger: Logger): String?

    fun getIdeVersion(): String

    fun toDeviceAdb(device: IDevice): IDeviceAdb?

    fun isHasRelaunchActivityIssues(device: IDeviceAdb, logger: Logger): Boolean

    fun invokeMcp(request: McpJsonRpcRequest): McpJsonRpcResponse

    fun getInitializedProjectDirs(): List<File>

    fun executeGradleCompile(autoConfirm: Boolean = false, useCleanAndReinstall: Boolean = false)
}
