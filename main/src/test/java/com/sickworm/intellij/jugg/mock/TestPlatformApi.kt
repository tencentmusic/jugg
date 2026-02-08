package com.sickworm.intellij.jugg.mock

import com.android.ddmlib.IDevice
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.ide.bean.ConfirmResult
import com.sickworm.intellij.jugg.mcp.McpJsonRpcRequest
import com.sickworm.intellij.jugg.mcp.McpJsonRpcResponse
import com.sickworm.intellij.jugg.platform.IPlatformApi
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResult
import com.sickworm.intellij.jugg.rpc.RpcRequest
import com.sickworm.intellij.jugg.rpc.RpcResponse

class TestPlatformApi : IPlatformApi {
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
        return listOf(TestGlobal.javaHome.path)
    }

    override fun getGradleJdkPath(
        project: Project,
        logger: Logger
    ): String? {
        TODO("Not yet implemented")
    }

    override fun getAndroidHomePath(logger: Logger): String? {
        return TestGlobal.androidHome.path
    }

    override fun getIdeVersion(): String {
        TODO("Not yet implemented")
    }

    override fun toDeviceAdb(device: IDevice): IDeviceAdb? {
        return null
    }

    override fun isHasRelaunchActivityIssues(
        device: IDeviceAdb,
        logger: Logger
    ): Boolean {
        TODO("Not yet implemented")
    }

    override fun invokeMcp(request: McpJsonRpcRequest): McpJsonRpcResponse {
        TODO("Not yet implemented")
    }

    override fun call(rpcRequest: RpcRequest): RpcResponse {
        TODO("Not yet implemented")
    }
}
