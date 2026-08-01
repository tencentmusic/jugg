package com.sickworm.intellij.jugg.ide.ui

import com.sickworm.intellij.jugg.ide.controlpanel.JuggControlPanelModel
import com.sickworm.intellij.jugg.ide.controlpanel.JuggEvent

/** Builds review scenarios through the same model and event APIs used by production data. */
class MockJuggControlPanelModel {

    var model = JuggControlPanelModel()
        private set

    fun load(scenario: Scenario) {
        model = JuggControlPanelModel()
        when (scenario) {
            Scenario.READY -> loadReady(isRunning = false)
            Scenario.RUNNING -> loadReady(isRunning = true)
            Scenario.FAILURE -> loadFailure()
            Scenario.LARGE_FILE_SET -> loadReady(isRunning = false, fileCount = 12)
            Scenario.FALLBACK -> loadFallback()
            Scenario.EMPTY -> Unit
        }
    }

    private fun loadReady(isRunning: Boolean, fileCount: Int = 3) {
        val files = (1..fileCount).map { index ->
            JuggEvent.ChangedFileSnapshot(JuggEvent.ChangedFileCategory.KOTLIN,
                "idea/src/main/Mock$index.kt", "/mock/idea/src/main/Mock$index.kt", "idea")
        }
        model.updateContext(JuggControlPanelModel.Context(
            configuration = "Mock Jugg Run",
            buildTarget = "APP",
            packageName = "com.sickworm.demo",
            devices = listOf("Pixel 8 API 35"),
            changedFileCount = files.size,
            changedFiles = files,
            hasBaseline = true,
            isHistoryAvailable = true,
        ))
        if (isRunning) {
            model.record(JuggEvent(
                taskId = "mock-task",
                source = JuggEvent.Source.IDE,
                category = JuggEvent.Category.COMPILE,
                phase = JuggEvent.Phase.COMPILING,
                status = JuggEvent.Status.STARTED,
                level = JuggEvent.Level.INFO,
                title = "Compiling mock changes",
                changedFiles = files,
            ))
        }
    }

    private fun loadFailure() {
        loadReady(isRunning = false)
        model.record(mockEvent("failed", JuggEvent.Status.STARTED))
        model.record(mockEvent("failed", JuggEvent.Status.FAILED).copy(
            detail = "Unresolved reference: UserRepository", isTaskTerminal = true))
    }

    private fun loadFallback() {
        loadReady(isRunning = false)
        model.record(mockEvent("fallback", JuggEvent.Status.STARTED).copy(
            compileMode = JuggEvent.CompileMode.INCREMENTAL,
            changedFiles = model.snapshot().context.changedFiles))
        model.record(mockEvent("fallback", JuggEvent.Status.SUCCEEDED).copy(
            compileMode = JuggEvent.CompileMode.GRADLE,
            fallback = "Incremental failed → Gradle", isTaskTerminal = true))
    }

    private fun mockEvent(taskId: String, status: JuggEvent.Status): JuggEvent {
        return JuggEvent(taskId = taskId, source = JuggEvent.Source.IDE, category = JuggEvent.Category.COMPILE,
            phase = JuggEvent.Phase.COMPLETED, status = status, level = JuggEvent.Level.INFO, title = "Mock run")
    }

    /** Identifies a reusable control panel review state. */
    enum class Scenario {
        READY,
        RUNNING,
        FAILURE,
        LARGE_FILE_SET,
        FALLBACK,
        EMPTY,
    }
}
