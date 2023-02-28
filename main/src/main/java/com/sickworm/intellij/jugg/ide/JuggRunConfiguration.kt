package com.sickworm.intellij.jugg.ide

import com.google.gson.Gson
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.*
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.*
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.icons.AllIcons
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.progress.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.sickworm.intellij.jugg.remote.RemoteClient
import com.sickworm.intellij.jugg.remote.RemoteCompileClientInfo
import org.jetbrains.kotlin.utils.addToStdlib.measureTimeMillisWithResult
import java.io.File
import java.io.OutputStream
import javax.swing.JComponent
import javax.swing.JTextField
import kotlin.system.measureTimeMillis
import kotlin.time.measureTime


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
        return JuggRunProfileState(project)
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

class JuggRunProfileState(val project: Project) : RunProfileState {

    val logKey = Key.create<String>("Jugg Terminal")

    override fun execute(executor: Executor?, runner: ProgramRunner<*>): ExecutionResult {
        // TODO support cancel
        val remoteClient = RemoteClient(project, project)
        val consoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        val processHandler = SimpleProcessHandler()
        remoteClient.terminalOutputListener = object : RemoteClient.TerminalOutputListener {
            override fun onOutput(line: String) {
                processHandler.notifyTextAvailable(line + "\n", logKey)
            }
        }

        @Suppress("DialogTitleCapitalization")
        val task = object : Task.Backgroundable(project, "Running Jugg") {
            override fun run(indicator: ProgressIndicator) {
                processHandler.notifyTextAvailable("\n\n[Jugg] Compile finished.\n", logKey)
                indicator.text = "Jugg compiling..."
                indicator.isIndeterminate = true

                val (costTime, isSuccess) = measureTimeMillisWithResult(::doRun)

                indicator.stop()
                val result = if (isSuccess) "SUCCESS" else "FAILED"
                processHandler.notifyTextAvailable("\n\n[Jugg] BUILD $result in ${costTime / 1000}s.\n", logKey)
            }

            private fun doRun(): Boolean {
                val homeDir = System.getProperty("user.home")
                val clientInfoFile = File("$homeDir/Downloads/remote_compile_client_info.json")
                val clientInfo = Gson().fromJson(clientInfoFile.readText(), RemoteCompileClientInfo::class.java)

                remoteClient.login(clientInfo)
                val remoteCompileResult = remoteClient.compileAndFetchResult()
                return remoteCompileResult.isSuccess
            }
        }

        consoleView.attachToProcess(processHandler)

        ProgressManager.getInstance().run(task)
        return DefaultExecutionResult(consoleView, processHandler)
    }

    private class SimpleProcessHandler : ProcessHandler(), AnsiEscapeDecoder.ColoredTextAcceptor {
        private val myAnsiEscapeDecoder = AnsiEscapeDecoder()

        override fun destroyProcessImpl() = Unit

        override fun detachProcessImpl() = Unit

        override fun detachIsDefault() = true

        override fun waitFor() = true

        override fun waitFor(timeoutInMilliseconds: Long) = true

        override fun getProcessInput(): OutputStream? {
            return null
        }

        override fun notifyTextAvailable(text: String, outputType: Key<*>) {
            myAnsiEscapeDecoder.escapeText(text, outputType, this)
        }

        override fun coloredTextAvailable(text: String, attributes: Key<*>) {
            super.notifyTextAvailable(text, attributes)
        }
    }
}
