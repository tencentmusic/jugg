package com.sickworm.intellij.jugg.cmdline

import com.android.ddmlib.IDevice
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.git.IFileMatcher
import com.sickworm.intellij.jugg.git.IGitManager
import com.sickworm.intellij.jugg.ide.bean.ConfirmResult
import com.sickworm.intellij.jugg.mcp.McpJsonRpcRequest
import com.sickworm.intellij.jugg.mcp.McpJsonRpcResponse
import com.sickworm.intellij.jugg.platform.IPlatformApi
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResult
import java.io.File

class CmdPlatformApi : IPlatformApi {

    override fun showDialog(
        title: String,
        content: String,
        okButtonText: String?,
        cancelButtonText: String?,
        isShowCancelButton: Boolean
    ): Boolean {
        TODO("Cmd line not support")
    }

    override fun showChangeConfirmDialog(
        diffResult: DependencyDiffResult?,
        isRunLater: Boolean,
        logger: Logger
    ): ConfirmResult {
        TODO("Cmd line not support")
    }

    override fun showUserAndPasswordInputDialog(
        content: String,
        subTitle: String?,
        isPassword: Boolean,
        defaultInputText: String?,
        title: String?
    ): String? {
        TODO("Cmd line not support")
    }

    override fun allAvailableJavaHomes(): List<String> {
        val javaHomeProp = System.getProperty("java.home")
        val javaHomeEnv = System.getenv("JAVA_HOME")
        val result = mutableListOf<String>()
        if (javaHomeProp != null) {
            result.add(javaHomeProp)
        }
        if (javaHomeEnv != null) {
            result.add(javaHomeEnv)
        }
        return result.distinct()
    }

    override fun getGradleJdkPath(project: Project, logger: Logger): String? {
        return allAvailableJavaHomes().firstOrNull()
    }

    override fun getAndroidHomePath(logger: Logger): String? {
        TODO("Cmd line not support")
    }

    override fun getIdeVersion(): String {
        TODO("Cmd line not support")
    }

    override fun toDeviceAdb(device: IDevice): IDeviceAdb? {
        return null
    }

    override fun isHasRelaunchActivityIssues(device: IDeviceAdb, logger: Logger): Boolean {
        TODO("Cmd line not support")
    }

    override fun invokeMcp(request: McpJsonRpcRequest): McpJsonRpcResponse {
        TODO("Cmd line not support")
    }

    override fun getInitializedProjectDirs(): List<File> {
        return emptyList()
    }

    override fun executeGradleCompile(autoConfirm: Boolean, useCleanAndReinstall: Boolean) {
        TODO("Cmd line not support")
    }
}
