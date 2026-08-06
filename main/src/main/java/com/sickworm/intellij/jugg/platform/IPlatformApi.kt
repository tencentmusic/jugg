package com.sickworm.intellij.jugg.platform

import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.sickworm.intellij.jugg.deploy.api.Apk
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.apk.manifest.ManifestActivityInfo
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.git.IFileMatcher
import com.sickworm.intellij.jugg.git.IGitManager
import com.sickworm.intellij.jugg.ai.mcp.McpJsonRpcRequest
import com.sickworm.intellij.jugg.ai.mcp.McpJsonRpcResponse
import com.sickworm.intellij.jugg.project.runtime.RuntimeInfo
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

    fun getRuntimeInfo(): RuntimeInfo

    fun toDeviceAdb(device: IDevice): IDeviceAdb?

    fun isHasRelaunchActivityIssues(device: IDeviceAdb, logger: Logger): Boolean

    fun invokeMcp(request: McpJsonRpcRequest): McpJsonRpcResponse

    fun getInitializedProjectDirs(): List<File>

    fun executeGradleCompile(autoConfirm: Boolean = false, useCleanAndReinstall: Boolean = false)
}
