package com.sickworm.intellij.jugg.platform

import com.android.tools.deployer.model.Apk
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.git.IFileMatcher
import com.sickworm.intellij.jugg.git.IGitManager
import com.sickworm.intellij.jugg.ide.bean.ConfirmResult
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResult
import com.sickworm.intellij.jugg.rpc.RpcRequest
import com.sickworm.intellij.jugg.rpc.RpcResponse
import java.io.File

interface IPlatformApi {

    val pluginVersion: String

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

    fun parseApks(apkFiles: List<String>): List<Apk>

    fun allAvailableJavaHomes(): List<String>

    fun getGradleJdkPath(project: Project, logger: Logger): String?

    fun getAndroidHomePath(logger: Logger): String?

    fun createGitManager(gitRoot: File): IGitManager

    fun createGitManagerAndTrySearchParent(dir: File): IGitManager

    fun createFileMatcher(): IFileMatcher

    fun getIdeVersion(): String

    fun isHasRelaunchActivityIssues(device: IDeviceAdb, logger: Logger): Boolean

    fun call(rpcRequest: RpcRequest): RpcResponse
}