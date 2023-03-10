package com.sickworm.intellij.jugg.ide

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.*
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.intellij.ui.IconManager
import java.io.File
import javax.swing.JComponent


class JuggRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String
) : RunConfigurationBase<JuggRunConfigurationOptions>(project, factory, name) {

    private val editor = JuggSettingsEditor()

    override fun getType(): ConfigurationType {
        return JuggConfigurationType.getInstance()
    }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> {
        editor.resetEditorFrom(state!!)
        return editor
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        val gradleCompileOptions = JuggGradleCompileOptions.fromOptions(File(project.basePath!!).name, state!!)
        return JuggRunProfileState(project, gradleCompileOptions)
    }
}

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
        })
    }

    companion object {
        @JvmStatic
        fun getInstance(): JuggConfigurationType {
            return ConfigurationTypeUtil.findConfigurationType(JuggConfigurationType::class.java)
        }
    }
}

class JuggSettingsEditor : SettingsEditor<JuggRunConfiguration>() {

    override fun resetEditorFrom(s: JuggRunConfiguration) {
        s.state?.let {
            resetEditorFrom(it)
        }
    }

    fun resetEditorFrom(options: JuggRunConfigurationOptions) {
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
        val juggManager = JuggInitializer.getManager(project) ?: return DefaultExecutionResult()
        // TODO use deploy
        return juggManager.deployFull(juggGradleCompileOptions)
    }

}
