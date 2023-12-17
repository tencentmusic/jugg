package com.sickworm.intellij.jugg.ide

import com.android.tools.idea.run.deployment.DeviceAndSnapshotComboBoxAction
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.*
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.ui.IconManager
import com.sickworm.intellij.jugg.server.toRunConfigurationTemplate
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
        putUserData(DeviceAndSnapshotComboBoxAction.DEPLOYS_TO_LOCAL_DEVICE, true)
    }

    override fun getType(): ConfigurationType {
        return JuggConfigurationType.getInstance()
    }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> {
        editor.resetEditorFrom(state!!, project)
        return editor
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        val gradleCompileOptions = JuggGradleCompileOptions.fromOptions(project.basePath!!, state!!)
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
        s.state?.let {
            resetEditorFrom(it, s.project)
        }
    }

    fun resetEditorFrom(options: JuggRunConfigurationOptions, project: Project) {
        (component as JuggRunSettingsComponent).updateUi(options)
        (component as JuggRunSettingsComponent).initUpload(project)
    }

    override fun applyEditorTo(s: JuggRunConfiguration) {
        val component = component as JuggRunSettingsComponent
        s.state?.run {
            compileCommand = component.compileCommandTextField.text
            outputApkName = component.outputApkNameTextField.text
            isRemoteCompile = component.enableRemoteCompileCheckBox.isSelected
            isSyncAllProjects = component.enableSyncAllProjectsCheckBox.isSelected
            remoteSshUser = component.userTextField.text
            remoteSshPassword = component.passwordTextField.password.joinToString("")
            remoteSshIp = component.ipTextField.text
            remoteSshPort = component.portTextField.text.toInt()
            localToRemoteIftConfigName = component.localToRemoteIftConfigNameTextField.text
            localToRemoteSyncPath = component.localToRemoteSyncPathTextField.text
            remoteSyncPath = component.remoteSyncPathTextField.text
            remoteToLocalIftConfigName = component.remoteToLocalIftConfigNameTextField.text
            remoteToLocalSyncPath = component.remoteToLocalSyncPathTextField.text
            httpProxyIp = component.httpProxyIpTextField.text
            httpProxyPort = component.httpProxyPortTextField.text.toIntOrNull() ?: 0
        }
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
            val task = juggManager.createRunningTask(juggGradleCompileOptions, processHandler)
            ProgressManager.getInstance().run(task)
        }

        return DefaultExecutionResult(consoleView, processHandler)
    }
}
