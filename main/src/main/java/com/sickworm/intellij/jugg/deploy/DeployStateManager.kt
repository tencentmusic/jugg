package com.sickworm.intellij.jugg.deploy

import com.android.tools.idea.run.deployable.Deployable
import com.android.tools.idea.run.deployable.DeployableProvider
import com.android.tools.idea.run.tasks.AbstractDeployTask
import com.android.tools.idea.run.ui.BaseAction
import com.android.tools.idea.run.ui.BaseAction.DisableMessage
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.openapi.project.Project
import java.lang.reflect.Field
import java.util.concurrent.ExecutionException

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

class IdeDeployStateHelper(
    private val project: Project,
) : IIdeDeployStateHelper {

    override fun getIdeDeployState(): JuggDeployState {
        val disableMessage = getDisableMessage(project)
        if (disableMessage != null) {
            return canNotIncrementalDeploy(disableMessage)
        }

        return JuggDeployState.READY
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

    /**
     * @see [com.android.tools.idea.run.ui.BaseAction.getDisableMessage]
     */
    private fun getDisableMessage(project: Project): DisableMessage? {
        val selectedRunConfig = RunManager.getInstance(project).allConfigurationsList.firstOrNull {
            return@firstOrNull isApplyChangesRelevant(it)
        } ?: return DisableMessage(
                DisableMessage.DisableMode.INVISIBLE, "no available supported configuration",
                "all configuration is not supported"
            )

        val deployableProvider = DeployableProvider.getInstance(project)
            ?: return DisableMessage(
                DisableMessage.DisableMode.DISABLED, "no deployment provider",
                "there is no deployment provider specified"
            )
        val deployable: Deployable?
        try {
            deployable = deployableProvider.getDeployable(selectedRunConfig)
            if (deployable == null) {
                return DisableMessage(
                    DisableMessage.DisableMode.DISABLED,
                    "selected device is invalid",
                    "the selected device is not valid"
                )
            }
            if (!deployable.isOnline) {
                if (deployable.isUnauthorized) {
                    return DisableMessage(
                        DisableMessage.DisableMode.DISABLED, "device not authorized",
                        "the selected device is not authorized"
                    )
                } else {
                    return DisableMessage(
                        DisableMessage.DisableMode.DISABLED,
                        "device not connected",
                        "the selected device is not connected"
                    )
                }
            }
            val versionFuture = deployable.version
            if (!versionFuture.isDone) {
                // Don't stall the EDT - if the Future isn't ready, just return false.
                return DisableMessage(
                    DisableMessage.DisableMode.DISABLED,
                    "unknown device API level",
                    "its API level is currently unknown"
                )
            }
            if (versionFuture.get().apiLevel < AbstractDeployTask.MIN_API_VERSION) {
                return DisableMessage(
                    DisableMessage.DisableMode.DISABLED, "incompatible device API level",
                    "its API level is lower than 26"
                )
            }
            if (deployable.searchClientsForPackage().isEmpty()) {
                return DisableMessage(
                    DisableMessage.DisableMode.DISABLED, "app not detected",
                    "the app is not yet running or not debuggable"
                )
            }
        } catch (ex: InterruptedException) {
            return DisableMessage(
                DisableMessage.DisableMode.DISABLED,
                "update interrupted",
                "its status update was interrupted"
            )
        } catch (ex: ExecutionException) {
            return DisableMessage(
                DisableMessage.DisableMode.DISABLED, "unknown device API level",
                "its API level could not be determined"
            )
        } catch (ex: Exception) {
            return DisableMessage(
                DisableMessage.DisableMode.DISABLED, "unexpected exception",
                "an unexpected exception was thrown: $ex"
            )
        }
        return null
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
}