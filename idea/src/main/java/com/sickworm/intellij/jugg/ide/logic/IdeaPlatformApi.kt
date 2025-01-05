package com.sickworm.intellij.jugg.ide.logic

import com.android.tools.deployer.model.Apk
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootManager
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.git.FileMatcher
import com.sickworm.intellij.jugg.git.GitManager
import com.sickworm.intellij.jugg.git.IFileMatcher
import com.sickworm.intellij.jugg.git.IGitManager
import com.sickworm.intellij.jugg.ide.bean.ConfirmResult
import com.sickworm.intellij.jugg.ide.ui.UserAndPasswordInputDialog
import com.sickworm.intellij.jugg.ide.ui.CommonConfirmDialog
import com.sickworm.intellij.jugg.platform.IPlatformApi
import com.sickworm.intellij.jugg.project.CompileContextManager
import com.sickworm.intellij.jugg.project.ProjectInfoReader
import com.sickworm.intellij.jugg.project.dependency.DependencyChangeDialogHelper
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResult
import java.io.File

class IdeaPlatformApi : IPlatformApi {

    override val pluginVersion: String by lazy {
        ProjectInfoReader.juggPluginInfoManifest?.mainAttributes?.getValue("Version") ?: "unknown"
    }

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

    override fun parseApks(apkFiles: List<String>): List<Apk> {
        return AsDeployerCompat.parseApks(apkFiles)
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
        var gradleJdkPath: String? = null

        val javaHome = System.getenv("JAVA_HOME")
        logger.debug("JAVA_HOME: $javaHome")

        val rootModule = AsDeployerCompat.getModuleManager(project).modules.find {
            it.name == project.name
        }
        if (rootModule != null) {
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

    override fun getAndroidHomePath(logger: Logger): String? {
        val androidHome = System.getenv("ANDROID_HOME")
        logger.debug("ANDROID_HOME: $androidHome")

        var androidHomePath = CompileContextManager.getAndroidSdkRootDir(logger)?.absolutePath
        if (androidHomePath == null) {
            logger.debug("can't find androidHomePath in modules, use ANDROID_HOME $androidHome instead")
            androidHomePath = androidHome
        } else {
            logger.debug("found androidHomePath $androidHomePath")
        }
        return androidHomePath
    }

    override fun createGitManager(gitRoot: File): IGitManager {
        return GitManager(gitRoot)
    }

    override fun createGitManagerAndTrySearchParent(dir: File): IGitManager {
        return GitManager.createGitManagerAndTrySearchParent(dir)
    }

    override fun createFileMatcher(): IFileMatcher {
        return FileMatcher()
    }

    override fun getIdeVersion(): String {
        return AsDeployerCompat.ideVersion.toString()
    }

}