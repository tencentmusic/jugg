package com.sickworm.intellij.jugg.ide.logic

import com.intellij.execution.ExecutionResult
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.openapi.util.Key
import com.sickworm.intellij.jugg.deploy.instrument.InstrumentationEvent
import com.sickworm.intellij.jugg.ide.bean.IProcessHandler
import com.sickworm.intellij.jugg.ide.ui.ProcessHandlerLoggerWrapper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import javax.swing.JPanel

class JuggConfigurationRunnerTest {

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
    fun `androidTest project log listener does not write plugin logs into SM runner process output`() {
        val processHandler = CapturingProcessHandler()
        val listener = ProcessHandlerLoggerWrapper(processHandler, isOutputEnabled = false)

        listener.info("Show Jugg androidTest gutter: path=/tmp/AppUiInstrumentedTest.kt")
        listener.warn("Plugin warning", null)

        assertFalse(processHandler.lines.any { it.contains("Show Jugg androidTest gutter") })
        assertFalse(processHandler.lines.any { it.contains("Plugin warning") })
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
