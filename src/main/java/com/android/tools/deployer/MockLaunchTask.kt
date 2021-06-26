package com.android.tools.deployer

import com.android.tools.idea.run.ConsolePrinter
import com.android.tools.idea.run.util.LaunchStatus
import com.intellij.execution.Executor
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.sickworm.intellij.aidp.AidpLogger
import java.util.function.BooleanSupplier
import javax.swing.Icon

/**
 * Looks like [Executor] is the "run" button
 */
class MockExecutor(private val toolWindow: ToolWindow): Executor() {

    override fun getToolWindowId(): String {
        return toolWindow.id;
    }

    override fun getToolWindowIcon(): Icon {
        return toolWindow.icon!!
    }

    override fun getIcon(): Icon {
        return toolWindow.icon!!
    }

    override fun getDisabledIcon(): Icon {
        return toolWindow.icon!!
    }

    override fun getDescription(): String {
        return "AidpExecutor"
    }

    override fun getActionName(): String {
        return "AidpExecutorApply"
    }

    override fun getId(): String {
        return "AidpExecutor getId"
    }

    override fun getStartActionText(): String {
        @Suppress("DialogTitleCapitalization")
        return "AidpExecutor getStartActionText"
    }

    override fun getContextActionId(): String {
        return "AidpExecutor getContextActionId"
    }

    override fun getHelpId(): String {
        return toolWindow.helpId
    }

}

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

    val logger = AidpLogger.getInstance(project, "#AIDP-ConsolePrinter")

    override fun stdout(message: String) {
        logger.info("stdout $message")
    }

    override fun stderr(message: String) {
        logger.error("stderr $message")
    }
}