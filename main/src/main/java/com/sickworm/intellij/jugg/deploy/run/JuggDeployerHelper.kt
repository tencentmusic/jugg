package com.sickworm.intellij.jugg.deploy.run

import com.android.tools.idea.gradle.util.EmbeddedDistributionPaths
import com.android.tools.idea.run.ConsolePrinter
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.project.JuggException
import com.sickworm.intellij.jugg.project.JuggInternalException
import org.jetbrains.annotations.TestOnly

/**
 * Create a deploy task.
 *
 * @see [com.android.tools.idea.run.AndroidRunConfigurationBase.getState]
 * @see [com.android.tools.idea.run.LaunchTaskRunner.run]
 */
class JuggDeployerHelper(
    private val project: Project,
    private val deployTargetManager: IDeployTargetManager,
    private val logger: Logger = JuggLogger.getInstance(project, "JuggDeployerHelper"),
) {

    @TestOnly
    var installPathProvider: Computable<String> = Computable<String> {
        EmbeddedDistributionPaths.getInstance().findEmbeddedInstaller()
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
        val consolePrinter = MockConsolePrinter(logger)
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

}

class MockConsolePrinter(private val logger: Logger): ConsolePrinter {

    override fun stdout(message: String) {
        logger.info(message)
    }

    override fun stderr(message: String) {
        logger.error(message)
    }
}