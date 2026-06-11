package com.sickworm.intellij.jugg.ide

import com.intellij.ide.SaveAndSyncHandler
import com.intellij.execution.ExecutionException
import com.intellij.execution.ExecutionResult
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.GenericProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project

/**
 * ProgramRunner for Jugg Debug actions.
 */
class JuggDebugProgramRunner : GenericProgramRunner<RunnerSettings> {

    private val runContentPresenter: IJuggDebugRunContentPresenter
    private val runPreflight: IJuggRunPreflight

    constructor() : this(JuggDebugRunContentPresenter(), JuggRunPreflight())

    internal constructor(runContentPresenter: IJuggDebugRunContentPresenter) : this(
        runContentPresenter,
        JuggRunPreflight(),
    )

    internal constructor(
        runContentPresenter: IJuggDebugRunContentPresenter,
        runPreflight: IJuggRunPreflight,
    ) : super() {
        this.runContentPresenter = runContentPresenter
        this.runPreflight = runPreflight
    }

    override fun getRunnerId(): String = "JuggDebugProgramRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean {
        return executorId == DefaultDebugExecutor.EXECUTOR_ID && profile is JuggRunConfiguration
    }

    @Throws(ExecutionException::class)
    override fun doExecute(state: RunProfileState, environment: ExecutionEnvironment): RunContentDescriptor? {
        runPreflight.prepare(environment.project)
        val executionResult = state.execute(environment.executor, this) ?: return null
        val descriptor = createRunContentDescriptor(executionResult, environment.runProfile.name)
        runContentPresenter.show(environment.project, descriptor)
        return null
    }

    internal fun juggConsoleExecutorId(): String = DefaultRunExecutor.EXECUTOR_ID

    private fun createRunContentDescriptor(
        executionResult: ExecutionResult,
        displayName: String,
    ): RunContentDescriptor {
        return RunContentDescriptor(
            executionResult.executionConsole,
            executionResult.processHandler,
            executionResult.executionConsole.component,
            displayName,
        )
    }
}

/**
 * Presents Jugg debug compile/deploy output independently from the Java debugger session.
 */
internal interface IJuggDebugRunContentPresenter {
    fun show(project: Project, descriptor: RunContentDescriptor)
}

/**
 * Prepares IDE file state before Jugg starts run/debug execution.
 */
internal interface IJuggRunPreflight {
    fun prepare(project: Project)
}

private class JuggDebugRunContentPresenter : IJuggDebugRunContentPresenter {
    override fun show(project: Project, descriptor: RunContentDescriptor) {
        RunContentManager.getInstance(project).showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)
    }
}

private class JuggRunPreflight : IJuggRunPreflight {
    override fun prepare(project: Project) {
        FileDocumentManager.getInstance().saveAllDocuments()
        val saveAndSyncHandler = SaveAndSyncHandler.getInstance()
        saveAndSyncHandler.refreshOpenFiles()
        saveAndSyncHandler.scheduleRefresh()
    }
}

internal fun shouldForceRestartAppForDebugExecutor(
    executorId: String?,
    hasAndroidTestRunSpec: Boolean,
): Boolean {
    return executorId == DefaultDebugExecutor.EXECUTOR_ID && !hasAndroidTestRunSpec
}
