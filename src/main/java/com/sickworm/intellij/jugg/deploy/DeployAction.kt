@file:Suppress("UnstableApiUsage")

package com.sickworm.intellij.jugg.deploy

import com.android.tools.idea.run.DeploymentService
import com.android.tools.idea.run.deployable.Deployable
import com.android.tools.idea.run.tasks.AbstractDeployTask
import com.android.tools.idea.run.ui.BaseAction
import com.android.tools.idea.util.CommonAndroidUtil
import com.intellij.execution.ExecutionManager
import com.intellij.execution.Executor
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.runners.ProgramRunner
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.sickworm.intellij.jugg.toolWindow.JuggLogger
import com.sickworm.intellij.jugg.JuggManager
import java.util.concurrent.ExecutionException

private const val NAME = "Jugg Deploy"

private const val DESC = "Attempt to apply resource and code changes by Jugg."

class DeployAction: AnAction(
    NAME, DESC, AllIcons.Actions.Execute
) {

    override fun actionPerformed(event: AnActionEvent) {
        // Using the event, create and show a dialog
        val currentProject = event.project?: return
        // If an element is selected in the editor, add info about it.
        Messages.showMessageDialog(currentProject, currentText, "Jugg", Messages.getInformationIcon())
    }

    private var currentText: String = "unknown state"
    @Suppress("UnstableApiUsage")
    override fun update(e: AnActionEvent) {
        super.update(e)

        val project = e.project
        if (project == null || !CommonAndroidUtil.getInstance().isAndroidProject(project)) {
            return
        }

        if (logger == null) {
            logger = JuggLogger.getInstance(project, "#Jugg-DeployAction")
        }

        val deployState = getDisableMessage(project)
        currentText = deployState.msg

        val juggManager = JuggManager.getInstance(project)
        @Suppress("UnstableApiUsage")
        juggManager?.updateStatus(deployState)
    }

    private var logger: Logger? = null

    private fun getDisableMessage(project: Project): DeployState {
        val configSettings = RunManager.getInstance(project).selectedConfiguration
            ?: return DeployState(DisableMessage(
                DisableMessage.DisableMode.DISABLED,
                "no configuration selected",
                "there is no configuration selected"
            ))
        val selectedRunConfig = configSettings.configuration
        if (!isApplyChangesRelevant(selectedRunConfig)) {
            return DeployState(DisableMessage(
                DisableMessage.DisableMode.INVISIBLE, "unsupported configuration",
                "the selected configuration is not supported"
            ))
        }
        if (isExecutorStarting(project, selectedRunConfig)) {
            return DeployState(DisableMessage(
                DisableMessage.DisableMode.DISABLED, "building and/or launching",
                "the selected configuration is currently building and/or launching"
            ))
        }
        val deployableProvider = DeploymentService.getInstance(project).deployableProvider
            ?: return DeployState(DisableMessage(
                DisableMessage.DisableMode.DISABLED, "no deployment provider",
                "there is no deployment provider specified"
            ))
        if (!deployableProvider.isDependentOnUserInput) {
            val deployable: Deployable?
            try {
                deployable = deployableProvider.deployable
                if (deployable == null) {
                    return DeployState(DisableMessage(
                        DisableMessage.DisableMode.DISABLED,
                        "selected device is invalid",
                        "the selected device is not valid"
                    ))
                }
                if (!deployable.isOnline) {
                    return if (deployable.isUnauthorized) {
                        DeployState(DisableMessage(
                            DisableMessage.DisableMode.DISABLED, "device not authorized",
                            "the selected device is not authorized"
                        ))
                    } else {
                        DeployState(DisableMessage(
                            DisableMessage.DisableMode.DISABLED,
                            "device not connected",
                            "the selected device is not connected"
                        ))
                    }
                }
                val versionFuture = deployable.version
                if (!versionFuture.isDone) {
                    // Don't stall the EDT - if the Future isn't ready, just return false.
                    return DeployState(DisableMessage(
                        DisableMessage.DisableMode.DISABLED,
                        "unknown device API level",
                        "its API level is currently unknown"
                    ), true)
                }
                if (versionFuture.get().apiLevel < AbstractDeployTask.MIN_API_VERSION) {
                    return DeployState(DisableMessage(
                        DisableMessage.DisableMode.DISABLED, "incompatible device API level",
                        "its API level is lower than 26"
                    ))
                }
                if (deployable.searchClientsForPackage().isEmpty()) {
                    return DeployState(DisableMessage(
                        DisableMessage.DisableMode.DISABLED, "app not detected",
                        "the app is not yet running or not debuggable"
                    ), true)
                }
            } catch (ex: InterruptedException) {
                logger?.warn(ex)
                return DeployState(DisableMessage(
                    DisableMessage.DisableMode.DISABLED,
                    "update interrupted",
                    "its status update was interrupted"
                ))
            } catch (ex: ExecutionException) {
                logger?.warn(ex)
                return DeployState(DisableMessage(
                    DisableMessage.DisableMode.DISABLED, "unknown device API level",
                    "its API level could not be determined"
                ), true)
            } catch (ex: Exception) {
                logger?.warn(ex)
                return DeployState(DisableMessage(
                    DisableMessage.DisableMode.DISABLED,
                    "unexpected exception",
                    "an unexpected exception was thrown: $ex"
                ))
            }
        }
        return DeployState(isReadyInstall = true, isReadyApply = true, disableMessage = null)
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

class DisableMessage(
    val disableMode: DisableMode,
    val tooltip: String,
    val description: String
) {
    enum class DisableMode {
        INVISIBLE, DISABLED
    }

}