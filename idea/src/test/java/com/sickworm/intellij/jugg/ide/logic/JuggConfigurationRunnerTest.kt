package com.sickworm.intellij.jugg.ide.logic

import com.intellij.execution.ExecutionResult
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.util.Key
import com.sickworm.intellij.jugg.ai.mcp.RunLogCollector
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.deploy.FullBuildInfo
import com.sickworm.intellij.jugg.deploy.instrument.InstrumentationEvent
import com.sickworm.intellij.jugg.ide.JuggRunConfiguration
import com.sickworm.intellij.jugg.ide.JuggRunConfigurationOptions
import com.sickworm.intellij.jugg.ide.bean.IProcessHandler
import com.sickworm.intellij.jugg.ide.ui.ProcessHandlerLoggerWrapper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import javax.swing.JPanel

class JuggConfigurationRunnerTest {

    @Test
    fun `selected Jugg configuration is used`() {
        val settings = createJuggSettings("jugg:app:qaDebug", "./gradlew :app:assembleQaDebug")
        val historySettings = createJuggSettings("jugg:app:prodDebug", "./gradlew :app:assembleProdDebug")

        val result = findJuggRunConfiguration(
            settings,
            listOf(historySettings, settings),
            FullBuildInfo("./gradlew :app:assembleProdDebug", BuildTarget.APP, 1L),
            buildTargetOverride = null,
        )

        assertSame(settings, result?.first)
    }

    @Test
    fun `last full build command and target match is preferred`() {
        val appSettings = createJuggSettings("jugg:app", "./gradlew :app:assembleDebug")
        val androidTestSettings = createJuggSettings(
            "jugg:app:androidTest",
            "./gradlew :app:assembleDebug",
            BuildTarget.ANDROID_TEST,
        )

        val result = findJuggRunConfiguration(
            selectedSettings = null,
            candidateSettings = listOf(appSettings, androidTestSettings),
            fullBuildInfo = FullBuildInfo("./gradlew :app:assembleDebug", BuildTarget.ANDROID_TEST, 1L),
            buildTargetOverride = null,
        )

        assertSame(androidTestSettings, result?.first)
    }

    @Test
    fun `last full build command match is used when target does not match`() {
        val firstSettings = createJuggSettings("jugg:first", "./gradlew :first:assembleDebug")
        val commandSettings = createJuggSettings("jugg:app", "./gradlew :app:assembleDebug")

        val result = findJuggRunConfiguration(
            selectedSettings = null,
            candidateSettings = listOf(firstSettings, commandSettings),
            fullBuildInfo = FullBuildInfo("./gradlew :app:assembleDebug", BuildTarget.ANDROID_TEST, 1L),
            buildTargetOverride = null,
        )

        assertSame(commandSettings, result?.first)
    }

    @Test
    fun `first Jugg configuration is used when selection and history do not match`() {
        val selectedSettings = mock<RunnerAndConfigurationSettings>()
        val firstSettings = createJuggSettings("jugg:first", "./gradlew :first:assembleDebug")
        val secondSettings = createJuggSettings("jugg:second", "./gradlew :second:assembleDebug")
        whenever(selectedSettings.configuration).thenReturn(mock<RunConfiguration>())

        val result = findJuggRunConfiguration(
            selectedSettings,
            listOf(firstSettings, secondSettings),
            FullBuildInfo("./gradlew :missing:assembleDebug", BuildTarget.APP, 1L),
            buildTargetOverride = null,
        )

        assertSame(firstSettings, result?.first)
    }

    private fun createJuggSettings(
        name: String,
        compileCommand: String,
        buildTarget: BuildTarget = BuildTarget.APP,
    ): RunnerAndConfigurationSettings {
        val runConfiguration = mock<JuggRunConfiguration>()
        val settings = mock<RunnerAndConfigurationSettings>()
        val options = JuggRunConfigurationOptions().apply {
            this.compileCommand = compileCommand
            enableAndroidTest = buildTarget == BuildTarget.ANDROID_TEST
        }
        whenever(settings.name).thenReturn(name)
        whenever(settings.configuration).thenReturn(runConfiguration)
        whenever(runConfiguration.state).thenReturn(options)

        return settings
    }

    @Test
    fun `androidTest sink hides device suite`() {
        val processHandler = CapturingProcessHandler()
        val bridge = createAndroidTestBridge(processHandler)
        val sink = createAndroidTestEventSink(bridge, "Pixel_9", showDeviceSuite = false)

        sink(InstrumentationEvent.TestStarted("com.example.FooTest", "testBar"))
        sink(InstrumentationEvent.TestFinished("com.example.FooTest", "testBar", InstrumentationEvent.TestResult.OK, null))
        sink(InstrumentationEvent.SuiteFinished(1, 0, 0))

        assertFalse(processHandler.lines.any { it.contains("name='Pixel_9'") })
        assertTrue(processHandler.lines.any { it.contains("name='FooTest'") })
        assertTrue(processHandler.lines.any { it.contains("locationHint='java:suite://com.example.FooTest'") })
    }

    @Test
    fun `androidTest sink shows device suite when enabled`() {
        val processHandler = CapturingProcessHandler()
        val bridge = createAndroidTestBridge(processHandler)
        val sink = createAndroidTestEventSink(bridge, "Pixel_9", showDeviceSuite = true)

        sink(InstrumentationEvent.TestStarted("com.example.FooTest", "testBar"))
        sink(InstrumentationEvent.TestFinished("com.example.FooTest", "testBar", InstrumentationEvent.TestResult.OK, null))
        sink(InstrumentationEvent.SuiteFinished(1, 0, 0))

        assertTrue(processHandler.lines.any { it.contains("testSuiteStarted") && it.contains("name='Pixel_9'") })
        assertTrue(processHandler.lines.any { it.contains("testSuiteFinished") && it.contains("name='Pixel_9'") })
    }

    @Test
    fun `run content descriptor does not activate tool window when added`() {
        val consoleView = Mockito.mock(ConsoleView::class.java)
        val processHandler = Mockito.mock(ProcessHandler::class.java)
        val executionResult = Mockito.mock(ExecutionResult::class.java)
        Mockito.`when`(consoleView.component).thenReturn(JPanel())
        Mockito.`when`(executionResult.executionConsole).thenReturn(consoleView)
        Mockito.`when`(executionResult.processHandler).thenReturn(processHandler)

        val descriptor = createRunContentDescriptor(executionResult, "jugg:default")

        assertFalse(descriptor.isActivateToolWindowWhenAdded)
    }

    @Test
    fun `disabled project log listener does not write plugin logs into process output`() {
        val processHandler = CapturingProcessHandler()
        val listener = ProcessHandlerLoggerWrapper(processHandler, isOutputEnabled = false)

        listener.info("Show Jugg androidTest gutter: path=/tmp/AppUiInstrumentedTest.kt")
        listener.warn("Plugin warning", null)

        assertFalse(processHandler.lines.any { it.contains("Show Jugg androidTest gutter") })
        assertFalse(processHandler.lines.any { it.contains("Plugin warning") })
    }

    @Test
    fun `run project log listener writes compile logs into process output`() {
        val processHandler = CapturingProcessHandler()
        val listener = createRunProjectLogListener(processHandler)

        listener.info("Jugg compile started.")
        listener.info("Compile files:\nKotlin:TestCaseScope.kt")
        listener.warn("/tmp/TestCaseScope.kt:18:25: error: unresolved reference 'PreconditionFailedException'.", null)
        listener.info("Compile finished in 1s, all: 8, success: 0, failure: 8.")
        listener.warn("\nFound incremental compile error. Please see logs for details.", null)

        assertTrue(processHandler.lines.any { it.contains("Jugg compile started.") })
        assertTrue(processHandler.lines.any { it.contains("Kotlin:TestCaseScope.kt") })
        assertTrue(processHandler.lines.any { it.contains("unresolved reference") })
        assertTrue(processHandler.lines.any { it.contains("Compile finished in 1s") })
        assertTrue(processHandler.lines.any { it.contains("Found incremental compile error") })
    }

    @Test
    fun `mcp log collector keeps logs without source-specific filtering`() {
        val collector = RunLogCollector()

        collector.warn("\nFound incremental compile error. Please see logs for details.", null)
        collector.warn("Run again directly will fall back to gradle compile.\n", null)

        val logs = collector.getAllLogs()
        assertTrue(logs.contains("Found incremental compile error"))
        assertTrue(logs.contains("Run again directly will fall back to gradle compile."))
    }

    private class CapturingProcessHandler : IProcessHandler {
        val lines = mutableListOf<String>()
        override var isCanceledByNextTask: Boolean = false
        override val isCanceled: Boolean = false
        override var cancelAction: (() -> Unit)? = null
        override fun notifyTextAvailable(text: String, outputType: Key<*>) {
            lines += text
        }
        override fun detachProcess() = Unit
        override fun destroyProcess() = Unit
    }
}
