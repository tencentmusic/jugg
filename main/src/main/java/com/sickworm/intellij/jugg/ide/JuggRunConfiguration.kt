package com.sickworm.intellij.jugg.ide

import com.android.tools.idea.uibuilder.handlers.motion.editor.adapters.Annotations.NotNull
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
import com.intellij.openapi.progress.util.ProgressIndicatorListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.NotNullLazyValue
import com.sickworm.intellij.jugg.remote.RemoteClient
import com.sickworm.intellij.jugg.remote.RemoteCompileClientInfo
import icons.StudioIcons
import org.jetbrains.kotlin.utils.addToStdlib.measureTimeMillisWithResult
import java.io.File
import java.io.OutputStream
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
        return JuggRunProfileState(project)
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

class JuggRunProfileState(val project: Project) : RunProfileState {

    override fun execute(executor: Executor?, runner: ProgramRunner<*>): ExecutionResult {
        val consoleView = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        // TODO use JuggManager
        // TODO support cancel
        val remoteClient = RemoteClient(project, project)
        val processHandler = SimpleProcessHandler(remoteClient)

        val task = JuggRunningTask(project, remoteClient, processHandler)
        consoleView.attachToProcess(processHandler)

        ProgressManager.getInstance().run(task)
        return DefaultExecutionResult(consoleView, processHandler)
    }

    @Suppress("DialogTitleCapitalization")
    class JuggRunningTask(
        project: Project,
        private val remoteClient: RemoteClient,
        private val processHandler: ProcessHandler,
    ) : Task.Backgroundable(project, "Running Jugg") {

        override fun run(indicator: ProgressIndicator) {
            remoteClient.terminalOutputListener = object : RemoteClient.TerminalOutputListener {
                override fun onOutput(line: String) {
                    processHandler.notifyTextAvailable(line + "\n", ProcessOutputType.STDOUT)
                }

                override fun onOutputErr(line: String) {
                    processHandler.notifyTextAvailable(line + "\n", ProcessOutputType.STDERR)
                }
            }
            object : ProgressIndicatorListener {
                override fun cancelled() { processHandler.detachProcess() }
                override fun stopped() { }
            }.installToProgressIfPossible(indicator)

            processHandler.startNotify()
            processHandler.notifyTextAvailable("Jugg compile started.\n", ProcessOutputType.STDOUT)
            indicator.text = "Compiling by Jugg..."
            indicator.isIndeterminate = true


            val (costTime, isSuccess) = measureTimeMillisWithResult {
                doRun()
            }

            val isCanceled = indicator.isCanceled || processHandler.isProcessTerminated
            if (isSuccess) {
                processHandler.notifyTextAvailable("\n\nBUILD SUCCESS in ${costTime / 1000}s.\n", ProcessOutputType.STDOUT)
            } else if (isCanceled) {
                processHandler.notifyTextAvailable("\n\nBUILD CANCELED in ${costTime / 1000}s.\n", ProcessOutputType.STDERR)
            } else {
                processHandler.notifyTextAvailable("\n\nBUILD FAILED in ${costTime / 1000}s.\n", ProcessOutputType.STDERR)
            }

            indicator.stop()
            if (!processHandler.isProcessTerminated) {
                processHandler.detachProcess()
            }
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

    private class SimpleProcessHandler(val remoteClient: RemoteClient) : ProcessHandler(), AnsiEscapeDecoder.ColoredTextAcceptor {

        private val myAnsiEscapeDecoder = AnsiEscapeDecoder()

        override fun destroyProcessImpl() {
            detachProcessImpl()
        }

        override fun detachProcessImpl() {
            remoteClient.cancelAction()
            notifyProcessTerminated(0)
        }

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
