package com.sickworm.intellij.jugg.ide

import com.intellij.execution.*
import com.intellij.execution.configurations.*
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.ui.IconManager
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.ide.ui.CommonConfirmDialog
import com.sickworm.intellij.jugg.ide.ui.SimpleProcessHandler
import com.sickworm.intellij.jugg.project.JuggPathManager
import java.io.File
import javax.swing.JComponent

/**
 * Implementation of [RunConfigurationBase], which is for managing config content.
 */
class JuggRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String
) : RunConfigurationBase<JuggRunConfigurationOptions>(project, factory, name) {

    private val editor = JuggSettingsEditor()

    init {
        AsDeployerCompat.setAllowSelectDevice(this)
    }

    override fun getType(): ConfigurationType {
        return JuggConfigurationType.getInstance()
    }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> {
        return editor
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        val pathManager = JuggPathManager(File(project.basePath!!))
        val gradleCompileOptions = state!!.toCompileOptions(pathManager)
        return JuggRunProfileState(project, gradleCompileOptions)
    }
}

/**
 * Implementation of [ConfigurationTypeBase], which is for creating a run configuration.
 */
class JuggConfigurationType : ConfigurationTypeBase(
    this::class.toString(),
    "Jugg",
    "Run Jugg compilation",
    NotNullLazyValue.createValue { IconManager.getInstance().getIcon("res/icon_run_configuration.svg", JuggConfigurationType::class.java) },
) {
    init {
        addFactory(object : ConfigurationFactory(this) {
            override fun createTemplateConfiguration(project: Project): RunConfiguration {
                return JuggRunConfiguration(project, this, "run Jugg")
            }

            override fun getOptionsClass(): Class<out BaseState> {
                return JuggRunConfigurationOptions::class.java
            }

            override fun getId(): String {
                return "Jugg"
            }

            override fun isEditableInDumbMode(): Boolean {
                return true
            }
        })
    }

    @Suppress("CompanionObjectInExtension")
    companion object {
        @JvmStatic
        fun getInstance(): JuggConfigurationType {
            return ConfigurationTypeUtil.findConfigurationType(JuggConfigurationType::class.java)
        }
    }
}

/**
 * Implementation of [RunConfigurationOptions], which is for handing config load/save.
 */
class JuggSettingsEditor : SettingsEditor<JuggRunConfiguration>() {

    override fun resetEditorFrom(s: JuggRunConfiguration) {
        val state = s.state ?: return
        (component as JuggRunSettingsComponent).updateUi(state, s.name)
        (component as JuggRunSettingsComponent).initUpload(s.project)
    }

    override fun applyEditorTo(s: JuggRunConfiguration) {
        val component = component as JuggRunSettingsComponent
        component.updateJuggRunConfigurationOptions(s.state)
    }

    override fun createEditor(): JComponent {
        return JuggRunSettingsComponent()
    }

}

/**
 * Implementation of [RunConfigurationOptions], which is for executing run configuration.
 */
class JuggRunProfileState(
    private val project: Project,
    private val juggGradleCompileOptions: JuggGradleCompileOptions,
) : RunProfileState {

    override fun execute(executor: Executor?, runner: ProgramRunner<*>): ExecutionResult {
        val juggManager = JuggInitializer.getManager(project) ?: return DefaultExecutionResult()
        val consoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        val processHandler = SimpleProcessHandler()
        consoleView.attachToProcess(processHandler)
        processHandler.startNotify()

        juggManager.cancelCurrentTask(processHandler) {
            val task = juggManager.createRunningTask(juggGradleCompileOptions, processHandler, forceFallbackNextTime)
            forceFallbackNextTime = false
            ProgressManager.getInstance().run(task)
        }

        return DefaultExecutionResult(consoleView, processHandler)
    }


    companion object {

        private var forceFallbackNextTime = false

        fun executeGradleCompile(project: Project) {
            val currentConfiguration = RunManager.getInstance(project).selectedConfiguration
            if (currentConfiguration?.configuration !is JuggRunConfiguration) {
                CommonConfirmDialog.showAndGetResult(
                    "Run failed", "Please select Jugg run configuration first.",
                    okButtonText = "I got it!"
                )
                return
            }
            val confirmResult = CommonConfirmDialog.showAndGetOrCancel(
                "Confirm fallback", "Jugg is going to fallback to gradle. Continue?",
                okButtonText = "Yes",
                negativeButtonText = "No",
                leftButtonText = "Just Reinstall",
            )
            when (confirmResult) {
                ConfirmResult.POSITIVE -> {
                    forceFallbackNextTime = true
                    ProgramRunnerUtil.executeConfiguration(currentConfiguration, DefaultRunExecutor.getRunExecutorInstance())
                }
                ConfirmResult.LEFT -> {
                    JuggInitializer.getManager(project)?.forceReInstallNextTime()
                    ProgramRunnerUtil.executeConfiguration(currentConfiguration, DefaultRunExecutor.getRunExecutorInstance())
                }
                else -> {
                    // no-op
                }
            }
        }
    }
}
