package com.sickworm.intellij.jugg.ide

import com.intellij.execution.Executor
import com.intellij.execution.configurations.*
import com.intellij.execution.process.*
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.icons.AllIcons
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.progress.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
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
        return JuggRunProfileState(project, environment)
    }
}

class JuggConfigurationType : ConfigurationTypeBase(
    this::class.toString(),
    "Jugg Configuration Type",
    "Run Jugg compilation",
    AllIcons.Toolwindows.ToolWindowRun,
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

class JuggRunProfileState(val project: Project, environment: ExecutionEnvironment) : CommandLineState(environment) {

    override fun startProcess(): ProcessHandler {
        // ; sleep 1;ls /Users/wormchen; sleep 1;ls /Users/wormchen; sleep 1;echo "done!"
        val command = "ls /Users/wormchen".split(" ")
        val cmd = GeneralCommandLine(command)
        val processHandler = ColoredProcessHandler(cmd)
        ProcessTerminatedListener.attach(processHandler)

        val progressManager = ProgressManager.getInstance()
        val task = object : Task.Backgroundable(project, "Running Jugg") {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Running command"
                indicator.isIndeterminate = true
                processHandler.addProcessListener(object : ProcessAdapter() {
                    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                        indicator.text = "Running command: $event"
                    }

                    override fun processTerminated(event: ProcessEvent) {
                    }
                })

                processHandler.startNotify()
                val exitCode = processHandler.waitFor()
                println("finish: $exitCode")
            }
        }

        val indicator = EmptyProgressIndicator()
        progressManager.runProcessWithProgressAsynchronously(task, indicator)
        return processHandler
    }
}