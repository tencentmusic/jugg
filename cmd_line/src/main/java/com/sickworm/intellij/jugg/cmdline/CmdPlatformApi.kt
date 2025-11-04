package com.sickworm.intellij.jugg.cmdline

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.git.IFileMatcher
import com.sickworm.intellij.jugg.git.IGitManager
import com.sickworm.intellij.jugg.ide.bean.ConfirmResult
import com.sickworm.intellij.jugg.platform.IPlatformApi
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResult
import com.sickworm.intellij.jugg.rpc.RpcRequest
import com.sickworm.intellij.jugg.rpc.RpcResponse
import java.io.File

class CmdPlatformApi : IPlatformApi {

    override fun showDialog(
        title: String,
        content: String,
        okButtonText: String?,
        cancelButtonText: String?,
        isShowCancelButton: Boolean
    ): Boolean {
        TODO("Not yet implemented")
    }

    override fun showChangeConfirmDialog(
        diffResult: DependencyDiffResult?,
        isRunLater: Boolean,
        logger: Logger
    ): ConfirmResult {
        TODO("Not yet implemented")
    }

    override fun showUserAndPasswordInputDialog(
        content: String,
        subTitle: String?,
        isPassword: Boolean,
        defaultInputText: String?,
        title: String?
    ): String? {
        TODO("Not yet implemented")
    }

    override fun allAvailableJavaHomes(): List<String> {
        TODO("Not yet implemented")
    }

    override fun getGradleJdkPath(project: Project, logger: Logger): String? {
        TODO("Not yet implemented")
    }

    override fun getAndroidHomePath(logger: Logger): String? {
        TODO("Not yet implemented")
    }

    override fun createGitManager(gitRoot: File): IGitManager {
        TODO("Not yet implemented")
    }

    override fun createGitManagerAndTrySearchParent(dir: File): IGitManager {
        TODO("Not yet implemented")
    }

    override fun createFileMatcher(): IFileMatcher {
        TODO("Not yet implemented")
    }

    override fun getIdeVersion(): String {
        TODO("Not yet implemented")
    }

    override fun isHasRelaunchActivityIssues(device: IDeviceAdb, logger: Logger): Boolean {
        TODO("Not yet implemented")
    }

    override fun call(rpcRequest: RpcRequest): RpcResponse {
        TODO("Not yet implemented")
    }
}