package com.android.tools.deployer

import com.android.tools.idea.run.ConsolePrinter
import com.android.tools.idea.run.util.LaunchStatus
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.toolWindow.JuggLogger
import java.util.function.BooleanSupplier

class MockLaunchStatus: LaunchStatus {
    override fun isLaunchTerminated(): Boolean {
        return false
    }

    override fun addLaunchTerminationCondition(launchTerminatedCondition: BooleanSupplier?) {
    }

    override fun getProcessHandler(): ProcessHandler {
        throw IllegalAccessException("no getProcessHandler")
    }

    override fun terminateLaunch(errorMessage: String?, destroyProcess: Boolean) {
    }

}

class MockConsolePrinter(val project: Project): ConsolePrinter {

    val logger = JuggLogger.getInstance(project, "#Jugg-ConsolePrinter")

    override fun stdout(message: String) {
        logger.info("stdout $message")
    }

    override fun stderr(message: String) {
        logger.error("stderr $message")
    }
}