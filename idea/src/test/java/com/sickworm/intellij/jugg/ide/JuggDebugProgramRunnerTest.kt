package com.sickworm.intellij.jugg.ide

import com.intellij.execution.ExecutionResult
import com.intellij.execution.Executor
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.project.Project
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import javax.swing.JPanel

class JuggDebugProgramRunnerTest {

    @Test
    fun `can run jugg configuration with debug executor`() {
        val runner = JuggDebugProgramRunner()
        val profile = Mockito.mock(JuggRunConfiguration::class.java)

        assertTrue(runner.canRun(DefaultDebugExecutor.EXECUTOR_ID, profile))
    }

    @Test
    fun `does not handle jugg configuration with run executor`() {
        val runner = JuggDebugProgramRunner()
        val profile = Mockito.mock(JuggRunConfiguration::class.java)

        assertFalse(runner.canRun(DefaultRunExecutor.EXECUTOR_ID, profile))
    }

    @Test
    fun `does not handle non jugg profile`() {
        val runner = JuggDebugProgramRunner()
        val profile = Mockito.mock(RunProfile::class.java)

        assertFalse(runner.canRun(DefaultDebugExecutor.EXECUTOR_ID, profile))
    }

    @Test
    fun `debug runner displays jugg console in run executor`() {
        val runner = JuggDebugProgramRunner()

        assertTrue(runner.juggConsoleExecutorId() == DefaultRunExecutor.EXECUTOR_ID)
    }

    @Test
    fun `debug executor forces app restart before debugger attach`() {
        assertTrue(shouldForceRestartAppForDebugExecutor(DefaultDebugExecutor.EXECUTOR_ID, hasAndroidTestRunSpec = false))
        assertFalse(shouldForceRestartAppForDebugExecutor(DefaultRunExecutor.EXECUTOR_ID, hasAndroidTestRunSpec = false))
        assertFalse(shouldForceRestartAppForDebugExecutor(DefaultDebugExecutor.EXECUTOR_ID, hasAndroidTestRunSpec = true))
    }

    @Test
    fun `debug runner does not return jugg console descriptor to debug executor lifecycle`() {
        val presenter = CapturingDebugRunContentPresenter()
        val runner = JuggDebugProgramRunner(presenter)
        val state = Mockito.mock(RunProfileState::class.java)
        val environment = Mockito.mock(ExecutionEnvironment::class.java)
        val project = Mockito.mock(Project::class.java)
        val executor = Mockito.mock(Executor::class.java)
        val profile = Mockito.mock(RunProfile::class.java)
        val executionResult = Mockito.mock(ExecutionResult::class.java)
        val consoleView = Mockito.mock(ConsoleView::class.java)
        val processHandler = Mockito.mock(ProcessHandler::class.java)
        Mockito.`when`(environment.executor).thenReturn(executor)
        Mockito.`when`(environment.project).thenReturn(project)
        Mockito.`when`(environment.runProfile).thenReturn(profile)
        Mockito.`when`(profile.name).thenReturn("jugg:app")
        Mockito.`when`(consoleView.component).thenReturn(JPanel())
        Mockito.`when`(executionResult.executionConsole).thenReturn(consoleView)
        Mockito.`when`(executionResult.processHandler).thenReturn(processHandler)
        Mockito.`when`(state.execute(environment.executor, runner)).thenReturn(executionResult)

        val returnedDescriptor = invokeDoExecute(runner, state, environment)

        assertNull(returnedDescriptor)
        assertTrue(presenter.project === project)
        assertTrue(presenter.descriptor?.displayName == "jugg:app")
    }

    private fun invokeDoExecute(
        runner: JuggDebugProgramRunner,
        state: RunProfileState,
        environment: ExecutionEnvironment,
    ): RunContentDescriptor? {
        val method = JuggDebugProgramRunner::class.java.getDeclaredMethod(
            "doExecute",
            RunProfileState::class.java,
            ExecutionEnvironment::class.java,
        )
        method.isAccessible = true
        return method.invoke(runner, state, environment) as? RunContentDescriptor
    }

    private class CapturingDebugRunContentPresenter : IJuggDebugRunContentPresenter {
        var project: Project? = null
        var descriptor: RunContentDescriptor? = null

        override fun show(project: Project, descriptor: RunContentDescriptor) {
            this.project = project
            this.descriptor = descriptor
        }
    }
}
