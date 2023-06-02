package com.sickworm.intellij.jugg.deploy

import com.android.tools.idea.run.ui.BaseAction
import com.android.tools.idea.run.ui.BaseAction.DisableMessage
import com.intellij.execution.ExecutionManager
import com.intellij.execution.Executor
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.project.Project
import java.lang.reflect.Field

/**
 * Manage [JuggDeployState].
 */
class DeployStateManager(
    private val project: Project,
    private val deployHistoryManager: IDeployHistoryManager,
    private val ideDeployStateHelper: IIdeDeployStateHelper = IdeDeployStateHelper(project),
) {

    var deployState = JuggDeployState(
        JuggDeployState.State.NOTHING_CAN_DO,
        "jugg not initialized"
    )
        private set

    var isBuildGradleChanged = false

    var isResourceFileChanged = false

    /**
     * Invoke when project need to update [JuggDeployState].
     */
    fun updateDeployState(): JuggDeployState {
        deployState = getNewDeployState()
        return deployState
    }

    private fun getNewDeployState(): JuggDeployState {
        val ideDeployState = ideDeployStateHelper.getIdeDeployState()
        if (!ideDeployState.isReadyRunFullBuild) {
            return ideDeployState
        }

        if (isBuildGradleChanged) {
            return JuggDeployState(JuggDeployState.State.READY_FULL_COMPILE, "build.gradle changed")
        }

        if (isResourceFileChanged) {
            return JuggDeployState(JuggDeployState.State.READY_FULL_COMPILE, "XML file changed")
        }

        if (!deployHistoryManager.hasBeenFullCompiled) {
            return JuggDeployState(JuggDeployState.State.READY_FULL_COMPILE, "need full compile")
        }

        return ideDeployState
    }
}

interface IIdeDeployStateHelper {
    fun getIdeDeployState(): JuggDeployState
}

/**
 * @see [com.android.tools.idea.run.ui.BaseAction]
 */
class IdeDeployStateHelper(
    private val project: Project,
) : IIdeDeployStateHelper {

    override fun getIdeDeployState(): JuggDeployState {
        val disableMessage = BaseAction.getDisableMessage(project)
        if (disableMessage != null) {
            return canNotIncrementalDeploy(disableMessage)
        }

        return JuggDeployState.READY
    }

    private fun building(disableMessage: DisableMessage): JuggDeployState {
        val toolTip = getToolTipField()
        return JuggDeployState(JuggDeployState.State.GRADLE_BUILDING, toolTip.get(disableMessage) as String)
    }

    private fun canNotFullBuild(disableMessage: DisableMessage): JuggDeployState {
        val toolTip = getToolTipField()
        return JuggDeployState(JuggDeployState.State.NOTHING_CAN_DO, toolTip.get(disableMessage) as String)
    }

    private fun canNotIncrementalDeploy(disableMessage: DisableMessage): JuggDeployState {
        val toolTip = getToolTipField()
        return JuggDeployState(JuggDeployState.State.READY_INCREMENTAL_COMPILE, toolTip.get(disableMessage) as String)
    }

    private var toolTipField: Field? = null

    private fun getToolTipField(): Field {
        toolTipField?.let { return it }
        val toolTipField = DisableMessage::class.java.getDeclaredField("myTooltip")
        toolTipField.isAccessible = true
        this.toolTipField = toolTipField
        return toolTipField
    }

    private fun isApplyChangesRelevant(runConfiguration: RunConfiguration): Boolean {
        if (runConfiguration is RunConfigurationBase<*>) {
            return runConfiguration.putUserDataIfAbsent(
                BaseAction.SHOW_APPLY_CHANGES_UI,
                false
            ) // This is needed to prevent a NPE if the boolean isn't set.
        }
        return false
    }

    /**
     * Check if there are any executors of the current [RunConfiguration] that is starting up. We should not swap when this is true.
     */
    private fun isExecutorStarting(project: Project, runConfiguration: RunConfiguration): Boolean {
        // Check if any executors are starting up (e.g. if the user JUST clicked on an executor, and deployment hasn't finished).
        for (executor in Executor.EXECUTOR_EXTENSION_NAME.extensionList) {
            val programRunner =
                ProgramRunner.getRunner(executor.id, runConfiguration) ?: continue
            if (ExecutionManager.getInstance(project).isStarting(executor.id, programRunner.runnerId)) {
                return true
            }
        }
        return false
    }
}