package com.sickworm.intellij.jugg.ide

import com.google.gson.Gson
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.*
import com.intellij.execution.process.*
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.icons.AllIcons
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.progress.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NotNullLazyValue
import com.sickworm.intellij.jugg.gradle.compile.RemoteGradleCompileClient
import java.io.File
import javax.swing.JComponent
import javax.swing.JTextField


class JuggRunConfiguration(
    project: Project,
    factory: ConfigurationFactory,
    name: String
) : RunConfigurationBase<Unit>(project, factory, name) {

    override fun getType(): ConfigurationType {
        return JuggConfigurationType.getInstance()
    }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> {
        return JuggSettingsEditor()
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        // TODO read from editor
        val homeDir: String = System.getProperty("user.home")
        val clientInfoFile = File("$homeDir/Downloads/remote_compile_client_info.json")
        val gradleCompileSettings = Gson().fromJson(clientInfoFile.readText(), GradleCompileSettings::class.java)
        return JuggRunProfileState(project, gradleCompileSettings)
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

class JuggSettingsEditor : SettingsEditor<JuggRunConfiguration>() {

    override fun resetEditorFrom(s: JuggRunConfiguration) {
    }

    override fun applyEditorTo(s: JuggRunConfiguration) {
    }

    override fun createEditor(): JComponent {
        return JTextField("nothing to do")
    }

}

class JuggRunProfileState(
    private val project: Project,
    private val gradleCompileSettings: GradleCompileSettings,
) : RunProfileState {

    override fun execute(executor: Executor?, runner: ProgramRunner<*>): ExecutionResult {
        val juggManager = JuggInitializer.getManager(project)
            ?: // TODO error toast
            return DefaultExecutionResult()

        return juggManager.deploy(gradleCompileSettings)
    }

}
