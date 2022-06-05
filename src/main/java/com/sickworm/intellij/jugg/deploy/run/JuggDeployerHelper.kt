package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.idea.run.ConsolePrinter
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.project.JuggException
import com.sickworm.intellij.jugg.project.JuggInternalException
import org.jetbrains.android.download.AndroidProfilerDownloader
import org.jetbrains.annotations.TestOnly
import java.io.File

/**
 * Create a deploy task.
 *
 * @see [com.android.tools.idea.run.AndroidRunConfigurationBase.getState]
 * @see [com.android.tools.idea.run.LaunchTaskRunner.run]
 */
class JuggDeployerHelper(
    private val project: Project,
    private val deployTargetManager: IDeployTargetManager,
) {

    @TestOnly
    var installPathProvider: Computable<String> = Computable<String> {
        findEmbeddedInstaller()
    }

    fun runTask(data: JuggDeployData, isInstall: Boolean = false) {
        if (data.apks.isEmpty()) {
            throw JuggInternalException.apkNotFound(data)
        }

        val type = when {
            isInstall -> JuggDeployType.INSTALL
            data.isNeedRestartApp -> JuggDeployType.HOT_FIX
            else -> JuggDeployType.HOT_RELOAD
        }
        val task = JuggDeployTask(project, installPathProvider, type, data)

        // TODO ConsolePrinter
        val consolePrinter = MockConsolePrinter(project)
        // TODO try ExecutionManager
        val device = deployTargetManager.getDevice()
        val launchContext = LaunchContext(consolePrinter, device)
        val launchResult = task.run(launchContext)
        if (!launchResult.success) {
            throw JuggException.applyChangesFailed(launchResult)
        }

        if (data.isNeedRestartApp || isInstall) {
            deployTargetManager.restartApp()
        }
    }

    // private in Android Studio 4.1.2，so I copied it out
    private fun findEmbeddedInstaller(): String? {
        val path = "plugins/android/resources/installer"
        val file = File(PathManager.getHomePath(), path)
        if (file.exists()) {
            return file.absolutePath
        }
        AndroidProfilerDownloader.getInstance().makeSureComponentIsInPlace()
        val dir = AndroidProfilerDownloader.getInstance().getHostDir(path)
        return if (dir.exists()) {
            dir.absolutePath
        } else File(
            PathManager.getHomePath(),
            "../../bazel-genfiles/tools/base/deploy/installer/android-installer"
        ).absolutePath
        // Development mode
    }

}

class MockConsolePrinter(val project: Project): ConsolePrinter {

    val logger = JuggLogger.getInstance(project, "ConsolePrinter")

    override fun stdout(message: String) {
        logger.info(message)
    }

    override fun stderr(message: String) {
        logger.error(message)
    }
}