package com.sickworm.intellij.jugg.ide.logic

import com.android.ddmlib.IDevice
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemJdkUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootManager
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.IdeaDeviceAdb
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.deploy.run.IdeVersion
import com.sickworm.intellij.jugg.ide.bean.ConfirmResult
import com.sickworm.intellij.jugg.ide.ui.UserAndPasswordInputDialog
import com.sickworm.intellij.jugg.ide.ui.CommonConfirmDialog
import com.sickworm.intellij.jugg.loader.JuggInitializer
import com.sickworm.intellij.jugg.ai.mcp.McpJsonRpcRequest
import com.sickworm.intellij.jugg.ai.mcp.McpJsonRpcResponse
import com.sickworm.intellij.jugg.platform.IPlatformApi
import com.sickworm.intellij.jugg.compiler.context.IdeaProjectModelSource
import com.sickworm.intellij.jugg.project.dependency.DependencyChangeDialogHelper
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResult
import com.sickworm.intellij.jugg.ai.mcp.IdeaMcpRuntime
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.io.File

class IdeaPlatformApi : IPlatformApi {

    override fun showDialog(
        title: String,
        content: String,
        okButtonText: String?,
        cancelButtonText: String?,
        isShowCancelButton: Boolean
    ): Boolean {
        return CommonConfirmDialog.showAndGetResult(title, content, okButtonText, cancelButtonText, isShowCancelButton)
    }

    override fun showChangeConfirmDialog(diffResult: DependencyDiffResult?, isRunLater: Boolean, logger: Logger): ConfirmResult {
        return DependencyChangeDialogHelper(logger).showChangeConfirmDialog(diffResult, isRunLater)
    }

    override fun showUserAndPasswordInputDialog(content: String, subTitle: String?, isPassword: Boolean, defaultInputText: String?, title: String?): String? {
        return UserAndPasswordInputDialog.showAndGetResult(content, subTitle, isPassword, defaultInputText, title)
    }

    override fun allAvailableJavaHomes(): List<String> {
        return ProjectJdkTable.getInstance().allJdks.filter { sdk ->
            if (sdk.sdkType != JavaSdk.getInstance()) {
                return@filter false
            }
            if (sdk.homePath == null) {
                return@filter false
            }
            return@filter true
        }.map {
            it.homePath!!
        }
    }

    override fun getGradleJdkPath(project: Project, logger: Logger): String? {
        val javaHome = System.getenv("JAVA_HOME")
        logger.debug("JAVA_HOME: $javaHome")

        var gradleJdkPath = getConfiguredGradleJdkPath(project, logger)
        val rootModule = AsDeployerCompat.getModuleManager(project).modules.find {
            it.name == project.name
        }
        if (gradleJdkPath == null && rootModule != null) {
            val moduleRootManager = ModuleRootManager.getInstance(rootModule)
            val jdk: Sdk? = moduleRootManager.sdk
            if (jdk != null && jdk.sdkType == JavaSdk.getInstance() && jdk.homePath != null) {
                logger.debug("found gradleJdkPath in root module: ${rootModule.name}, path: ${jdk.homePath}")
                gradleJdkPath = jdk.homePath
            }
        }
        if (gradleJdkPath == null) {
            gradleJdkPath = AsDeployerCompat.getModuleManager(project).modules.firstNotNullOfOrNull { module ->
                val moduleRootManager = ModuleRootManager.getInstance(module)
                val jdk: Sdk = moduleRootManager.sdk ?: return@firstNotNullOfOrNull null
                if (jdk.sdkType != JavaSdk.getInstance()) {
                    return@firstNotNullOfOrNull null
                }
                if (jdk.homePath == null) {
                    return@firstNotNullOfOrNull null
                }
                logger.debug("found gradleJdkPath in module: ${module.name}, path: ${jdk.homePath}")
                return@firstNotNullOfOrNull jdk.homePath!!
            }
        }

        if (gradleJdkPath == null) {
            logger.debug("can't find gradleJdkPath in modules, use JAVA_HOME $javaHome instead")
            gradleJdkPath = javaHome
        } else {
            logger.debug("final use gradleJdkPath: $gradleJdkPath")
        }
        return gradleJdkPath
    }

    private fun getConfiguredGradleJdkPath(project: Project, logger: Logger): String? {
        return try {
            val gradleSettings = GradleSettings.getInstance(project)
            val projectSettings = project.basePath?.let { gradleSettings.getLinkedProjectSettings(it) }
                ?: gradleSettings.linkedProjectsSettings.firstOrNull()
            val gradleJvm = projectSettings?.gradleJvm
            if (gradleJvm.isNullOrEmpty()) {
                logger.debug("Gradle JVM is not configured in IDE settings")
                null
            } else {
                val path = ExternalSystemJdkUtil.getJdk(project, gradleJvm)?.homePath
                logger.debug("found gradleJdkPath in IDE Gradle settings: $gradleJvm, path: $path")
                path
            }
        } catch (e: Exception) {
            logger.debug("can't resolve Gradle JVM from IDE settings", e)
            null
        }
    }

    override fun getAndroidHomePath(logger: Logger): String? {
        val androidHome = System.getenv("ANDROID_HOME")
        logger.debug("ANDROID_HOME: $androidHome")

        var androidHomePath = IdeaProjectModelSource.getAndroidSdkRootDir(logger)?.absolutePath
        if (androidHomePath == null) {
            logger.debug("can't find androidHomePath in modules, use ANDROID_HOME $androidHome instead")
            androidHomePath = androidHome
        } else {
            logger.debug("found androidHomePath $androidHomePath")
        }
        return androidHomePath
    }

    override fun getIdeVersion(): String {
        return AsDeployerCompat.ideVersion.toString()
    }

    override fun toDeviceAdb(device: IDevice): IDeviceAdb? {
        return IdeaDeviceAdb(device, Logger.getInstance("IdeaDeviceAdb"))
    }

    override fun isHasRelaunchActivityIssues(device: IDeviceAdb, logger: Logger): Boolean {
        // Android 15 has problem with relaunch activity
        val isAndroid15 = device.api >= 35
        val meerkatVersion = IdeVersion("Android Studio Meerkat", "IA", "243.22562.218")
        val isBelowAndroidStudioMeerkat = AsDeployerCompat.ideVersion < meerkatVersion
        val isHasRelaunchActivityIssues = isAndroid15 && isBelowAndroidStudioMeerkat
        logger.debug("isHasRelaunchActivityIssues $isHasRelaunchActivityIssues, " +
                "isAndroid15: $isAndroid15, " +
                "isBelowAndroidStudioMeerkat: $isBelowAndroidStudioMeerkat")
        return isHasRelaunchActivityIssues
    }

    override fun invokeMcp(request: McpJsonRpcRequest): McpJsonRpcResponse {
        return IdeaMcpRuntime.invokeMcp(request)
    }

    override fun getInitializedProjectDirs(): List<File> {
        return JuggInitializer.getInitializedProjectDirs().map { File(it) }
    }

    override fun executeGradleCompile(autoConfirm: Boolean, useCleanAndReinstall: Boolean) {

    }
}
