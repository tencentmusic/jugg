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
            Scenario.EMPTY -> Unit
        }
    }

    private fun loadReady(isRunning: Boolean) {
        model.updateContext(JuggControlPanelModel.Context(
            configuration = "Mock Jugg Run",
            buildTarget = "APP",
            packageName = "com.sickworm.demo",
            devices = listOf("Pixel 8 API 35"),
            changedFileCount = 3,
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
            ))
        }
    }

    /** Identifies a reusable control panel review state. */
    enum class Scenario {
        READY,
        RUNNING,
        EMPTY,
    }
}
