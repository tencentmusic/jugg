package com.sickworm.intellij.jugg.deploy

import com.android.tools.idea.run.DeploymentService
import com.android.tools.idea.run.deployable.Deployable
import com.android.tools.idea.run.tasks.JuggAbstractDeployTask
import com.android.tools.idea.run.ui.BaseAction
import com.intellij.execution.ExecutionManager
import com.intellij.execution.Executor
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.project.JuggLogger
import java.util.concurrent.ExecutionException

class DeployStateManager(
    private val project: Project,
    private val deployHistoryManager: IDeployHistoryManager,
    private val ideDeployStateHelper: IdeDeployStateHelper = IdeDeployStateHelper(project),
) {

    var deployState = JuggDeployState(isReadyRunFullBuild = false, isReadyCompile = false, isReadyDeploy = false,
        DisableMessage(DisableMessage.DisableMode.DISABLED, "not initialized", "jugg not initialized")
    )
        private set

    var isBuildGradleChanged = false

    fun onActionUpdate(): JuggDeployState {
        deployState = getNewDeployState()
        return deployState
    }

    private fun getNewDeployState(): JuggDeployState {
        val ideDeployState = ideDeployStateHelper.getIdeDeployState()
        if (!ideDeployState.isReadyRunFullBuild) {
            return ideDeployState
        }

        if (isBuildGradleChanged) {
            return ideDeployState.copy(isReadyCompile = false, isReadyDeploy = false)
        }

        if (!deployHistoryManager.hasBeenFullCompiled) {
            return ideDeployState.copy(isReadyDeploy = false)
        }

        return ideDeployState
    }
}

class IdeDeployStateHelper(
    private val project: Project,
) {

    private val logger = JuggLogger.getInstance(project, "#Jugg-IdeDeployStateHelper")

    fun getIdeDeployState(): JuggDeployState {
        val configSettings = RunManager.getInstance(project).selectedConfiguration
            ?: return JuggDeployState(DisableMessage(
                DisableMessage.DisableMode.DISABLED,
                "no configuration selected",
                "there is no configuration selected"
            ))
        val selectedRunConfig = configSettings.configuration
        if (!isApplyChangesRelevant(selectedRunConfig)) {
            return JuggDeployState(DisableMessage(
                DisableMessage.DisableMode.INVISIBLE, "unsupported configuration",
                "the selected configuration is not supported"
            ))
        }
        if (isExecutorStarting(project, selectedRunConfig)) {
            return JuggDeployState(DisableMessage(
                DisableMessage.DisableMode.DISABLED, "building and/or launching",
                "the selected configuration is currently building and/or launching"
            ))
        }
        val deployableProvider = DeploymentService.getInstance(project).deployableProvider
            ?: return JuggDeployState(DisableMessage(
                DisableMessage.DisableMode.DISABLED, "no deployment provider",
                "there is no deployment provider specified"
            ))
        if (!deployableProvider.isDependentOnUserInput) {
            val deployable: Deployable?
            try {
                deployable = deployableProvider.deployable
                if (deployable == null) {
                    return JuggDeployState(DisableMessage(
                        DisableMessage.DisableMode.DISABLED,
                        "selected device is invalid",
                        "the selected device is not valid"
                    ))
                }
                if (!deployable.isOnline) {
                    return if (deployable.isUnauthorized) {
                        JuggDeployState(DisableMessage(
                            DisableMessage.DisableMode.DISABLED, "device not authorized",
                            "the selected device is not authorized"
                        ))
                    } else {
                        JuggDeployState(DisableMessage(
                            DisableMessage.DisableMode.DISABLED,
                            "device not connected",
                            "the selected device is not connected"
                        ))
                    }
                }
                val versionFuture = deployable.version
                if (!versionFuture.isDone) {
                    // Don't stall the EDT - if the Future isn't ready, just return false.
                    return JuggDeployState(DisableMessage(
                        DisableMessage.DisableMode.DISABLED,
                        "unknown device API level",
                        "its API level is currently unknown"
                    ), true)
                }
                if (versionFuture.get().apiLevel < JuggAbstractDeployTask.MIN_API_VERSION) {
                    return JuggDeployState(DisableMessage(
                        DisableMessage.DisableMode.DISABLED, "incompatible device API level",
                        "its API level is lower than 30"
                    ))
                }
                if (deployable.searchClientsForPackage().isEmpty()) {
                    return JuggDeployState(DisableMessage(
                        DisableMessage.DisableMode.DISABLED, "app not detected",
                        "the app is not yet running or not debuggable"
                    ), true)
                }
            } catch (ex: InterruptedException) {
                logger.warn(ex)
                return JuggDeployState(DisableMessage(
                    DisableMessage.DisableMode.DISABLED,
                    "update interrupted",
                    "its status update was interrupted"
                ))
            } catch (ex: ExecutionException) {
                logger.warn(ex)
                return JuggDeployState(DisableMessage(
                    DisableMessage.DisableMode.DISABLED, "unknown device API level",
                    "its API level could not be determined"
                ), true)
            } catch (ex: Exception) {
                logger.warn(ex)
                return JuggDeployState(DisableMessage(
                    DisableMessage.DisableMode.DISABLED,
                    "unexpected exception",
                    "an unexpected exception was thrown: $ex"
                ))
            }
        }
        return JuggDeployState.READY
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