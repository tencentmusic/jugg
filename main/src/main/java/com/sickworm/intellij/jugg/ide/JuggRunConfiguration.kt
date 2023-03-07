package com.sickworm.intellij.jugg.ide

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.*
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.icons.AllIcons
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import javax.swing.JComponent


class JuggRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String
) : RunConfigurationBase<JuggGradleCompileOptions>(project, factory, name) {

    private val options = JuggGradleCompileOptions(project.name)
    private val editor = JuggSettingsEditor(options)

    override fun getType(): ConfigurationType {
        return JuggConfigurationType.getInstance()
    }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> {
        return editor
    }

    override fun getOptions(): RunConfigurationOptions {
        return options
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        return JuggRunProfileState(project, state!!)
    }
}

class JuggConfigurationType : ConfigurationTypeBase(
    this::class.toString(),
    "Jugg",
    "Run Jugg compilation",
    NotNullLazyValue.createValue { AllIcons.Providers.Openedge },
) {
    init {
        addFactory(object : ConfigurationFactory(this) {
            override fun createTemplateConfiguration(project: Project): RunConfiguration {
                return JuggRunConfiguration(project, this, "run Jugg")
            }
        })
    }

    companion object {
        @JvmStatic
        fun getInstance(): JuggConfigurationType {
            return ConfigurationTypeUtil.findConfigurationType(JuggConfigurationType::class.java)
        }
    }
}

class JuggSettingsEditor(currentOptions: JuggGradleCompileOptions) : SettingsEditor<JuggRunConfiguration>() {

    init {
        resetEditorFrom(currentOptions)
    }

    override fun resetEditorFrom(s: JuggRunConfiguration) {
        s.state?.let {
            resetEditorFrom(it)
        }
    }

    private fun resetEditorFrom(options: JuggGradleCompileOptions) {
        (component as JuggRunSettingsComponent).updateUi(options)
    }

    override fun applyEditorTo(s: JuggRunConfiguration) {
        val component = component as JuggRunSettingsComponent
        s.state?.run {
            compileCommand = component.compileCommandTextField.text
            outputApkName = component.outputApkNameTextField.text
            isRemoteCompile = component.enableRemoteCompileCheckBox.isSelected
            remoteSshUser = component.userTextField.text
            remoteSshPassword = component.passwordTextField.password.joinToString("")
            remoteSshIp = component.ipTextField.text
            remoteSshPort = component.portTextField.text.toInt()
            localToRemoteIftConfigName = component.localToRemoteIftConfigNameTextField.text
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

class JuggRunProfileState(
    private val project: Project,
    private val juggGradleCompileOptions: JuggGradleCompileOptions,
) : RunProfileState {

    override fun execute(executor: Executor?, runner: ProgramRunner<*>): ExecutionResult {
        val juggManager = JuggInitializer.getManager(project)
            ?: // TODO error toast
            return DefaultExecutionResult()

        return juggManager.deploy(juggGradleCompileOptions)
    }

}
