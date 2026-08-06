package com.sickworm.intellij.jugg.cmdline.standalone

import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.ai.mcp.McpJsonRpcRequest
import com.sickworm.intellij.jugg.ai.mcp.McpJsonRpcResponse
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.platform.IPlatformApi
import com.sickworm.intellij.jugg.project.runtime.RuntimeInfo
import java.io.File

/** Supplies process-level host services for the standalone daemon. */
class StandalonePlatformApi(
    private val registry: StandaloneProjectRegistry,
    private val runtimeInfo: RuntimeInfo,
) : IPlatformApi {
    override fun showDialog(title: String, content: String, okButtonText: String?, cancelButtonText: String?, isShowCancelButton: Boolean): Boolean = false

    override fun showUserAndPasswordInputDialog(content: String, subTitle: String?, isPassword: Boolean, defaultInputText: String?, title: String?): String? = null

    override fun allAvailableJavaHomes(): List<String> {
        return listOfNotNull(System.getProperty("java.home"), System.getenv("JAVA_HOME")).distinct()
    }

    override fun getGradleJdkPath(project: Project, logger: Logger): String? = allAvailableJavaHomes().firstOrNull()

    override fun getAndroidHomePath(logger: Logger): String? {
        return System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    }

    override fun getIdeVersion(): String = runtimeInfo.hostVersion

    override fun getRuntimeInfo(): RuntimeInfo = runtimeInfo

    override fun toDeviceAdb(device: IDevice): IDeviceAdb? = null

    override fun isHasRelaunchActivityIssues(device: IDeviceAdb, logger: Logger): Boolean = false

    override fun invokeMcp(request: McpJsonRpcRequest): McpJsonRpcResponse = registry.invokeMcp(request)

    override fun getInitializedProjectDirs(): List<File> = registry.getInitializedProjectDirs()

    override fun executeGradleCompile(autoConfirm: Boolean, useCleanAndReinstall: Boolean) {
        throw UnsupportedOperationException("Standalone Gradle build is not available before step 11")
    }
}
