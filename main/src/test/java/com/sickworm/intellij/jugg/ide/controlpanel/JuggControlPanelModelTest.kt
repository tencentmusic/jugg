package com.sickworm.intellij.jugg.ide.controlpanel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.sickworm.intellij.jugg.deploy.run.JuggDeployData

class JuggControlPanelModelTest {

    @Test
    fun `initial snapshot is empty`() {
        val snapshot = JuggControlPanelModel().snapshot()

        assertEquals(0, snapshot.version)
        assertTrue(snapshot.recentEvents.isEmpty())
        assertNull(snapshot.currentTask)
        assertNull(snapshot.lastDeploy)
    }

    @Test
    fun `task events update active task and keep one terminal event`() {
        val model = JuggControlPanelModel()

        model.record(event(taskId = "task-1", status = JuggEvent.Status.STARTED))
        assertEquals("task-1", model.snapshot().currentTask?.taskId)

        model.record(event(
            taskId = "task-1",
            phase = JuggEvent.Phase.COMPLETED,
            status = JuggEvent.Status.SUCCEEDED,
            isTaskTerminal = true,
        ))
        model.record(event(
            taskId = "task-1",
            phase = JuggEvent.Phase.COMPLETED,
            status = JuggEvent.Status.FAILED,
            level = JuggEvent.Level.ERROR,
            isTaskTerminal = true,
        ))

        val snapshot = model.snapshot()
        assertNull(snapshot.currentTask)
        assertEquals(2, snapshot.recentEvents.size)
        assertEquals(JuggEvent.Status.SUCCEEDED, snapshot.recentEvents.last().status)
    }

    @Test
    fun `starting a new task cancels the active task`() {
        val model = JuggControlPanelModel()

        model.record(event(taskId = "task-1", status = JuggEvent.Status.STARTED))
        model.record(event(taskId = "task-2", status = JuggEvent.Status.STARTED))

        val snapshot = model.snapshot()
        assertEquals("task-2", snapshot.currentTask?.taskId)
        assertTrue(snapshot.recentEvents.any {
            it.taskId == "task-1" && it.status == JuggEvent.Status.CANCELED && it.isTaskTerminal
        })
    }

    @Test
    fun `non compile events do not replace an active compile task`() {
        val model = JuggControlPanelModel()

        model.record(event(taskId = "compile", status = JuggEvent.Status.STARTED))
        model.record(event(
            taskId = "mcp",
            category = JuggEvent.Category.MCP,
            status = JuggEvent.Status.STARTED,
        ))
        model.record(event(
            taskId = "mcp",
            category = JuggEvent.Category.MCP,
            phase = JuggEvent.Phase.COMPLETED,
            status = JuggEvent.Status.SUCCEEDED,
            isTaskTerminal = true,
        ))

        assertEquals("compile", model.snapshot().currentTask?.taskId)
        assertTrue(model.snapshot().recentEvents.none {
            it.taskId == "compile" && it.status == JuggEvent.Status.CANCELED
        })
    }

    @Test
    fun `deploy completion is retained after non compile events`() {
        val model = JuggControlPanelModel()

        model.record(event(taskId = "run", status = JuggEvent.Status.STARTED))
        model.record(event(
            taskId = "sync",
            category = JuggEvent.Category.SYNC,
            status = JuggEvent.Status.STARTED,
        ))
        model.record(event(
            taskId = "run",
            category = JuggEvent.Category.DEPLOY,
            phase = JuggEvent.Phase.COMPLETED,
            status = JuggEvent.Status.SUCCEEDED,
            title = "Deploy completed",
            isTaskTerminal = true,
        ))

        assertNull(model.snapshot().currentTask)
        assertEquals("Deploy completed", model.snapshot().lastDeploy?.title)
    }

    @Test
    fun `compile category owns current task regardless of source`() {
        val model = JuggControlPanelModel()

        model.record(event(
            taskId = "mcp-compile",
            source = JuggEvent.Source.MCP,
            status = JuggEvent.Status.STARTED,
        ))

        assertEquals("mcp-compile", model.snapshot().currentTask?.taskId)
    }

    @Test
    fun `recent events keep the bounded window`() {
        val model = JuggControlPanelModel(maxRecentEvents = 3)

        repeat(5) { index ->
            model.record(event(title = "event-$index"))
        }

        assertEquals(listOf("event-2", "event-3", "event-4"), model.snapshot().recentEvents.map { it.title })
    }

    @Test
    fun `last deploy changes only after the task reaches a terminal event`() {
        val model = JuggControlPanelModel()

        model.record(event(
            taskId = "task-1",
            category = JuggEvent.Category.DEPLOY,
            status = JuggEvent.Status.SUCCEEDED,
            title = "Device deployed",
        ))
        assertNull(model.snapshot().lastDeploy)

        model.record(event(
            taskId = "task-1",
            category = JuggEvent.Category.DEPLOY,
            status = JuggEvent.Status.SUCCEEDED,
            title = "Deploy completed",
            isTaskTerminal = true,
        ))

        assertEquals("Deploy completed", model.snapshot().lastDeploy?.title)
    }

    @Test
    fun `subscription receives current state and stops after close`() {
        val model = JuggControlPanelModel()
        val versions = mutableListOf<Long>()

        val subscription = model.subscribe { versions += it.version }
        model.record(event(title = "first"))
        subscription.close()
        model.record(event(title = "second"))

        assertEquals(listOf(0L, 1L), versions)
    }

    @Test
    fun `listener failure does not interrupt event recording or other listeners`() {
        val model = JuggControlPanelModel()
        val versions = mutableListOf<Long>()
        model.subscribe { if (it.version > 0) error("broken listener") }
        model.subscribe { versions += it.version }

        model.record(event(title = "recorded"))

        assertEquals("recorded", model.snapshot().recentEvents.single().title)
        assertEquals(listOf(0L, 1L), versions)
    }

    @Test
    fun `context and settings remain facts outside event history`() {
        val model = JuggControlPanelModel()
        val context = JuggControlPanelModel.Context(
            configuration = "run Jugg",
            packageName = "com.sickworm.demo",
            devices = listOf("Pixel 8 API 35"),
            changedFileCount = 3,
            hasBaseline = true,
        )
        val settings = JuggControlPanelModel.Settings(
            confirmFallbackWhenNoFileChanges = false,
            quickDeploy = true,
        )

        model.updateContext(context)
        model.updateSettings(settings)

        val snapshot = model.snapshot()
        assertEquals(context, snapshot.context)
        assertEquals(settings, snapshot.settings)
        assertTrue(snapshot.recentEvents.isEmpty())
    }

    @Test
    fun `terminal task creates one run summary and preserves raw deploy type`() {
        val model = JuggControlPanelModel()
        val files = listOf(JuggEvent.ChangedFileSnapshot(
            JuggEvent.ChangedFileCategory.KOTLIN, "idea/Test.kt", "/project/idea/Test.kt", "idea"))
        model.record(event(taskId = "run", status = JuggEvent.Status.STARTED).copy(
            compileMode = JuggEvent.CompileMode.INCREMENTAL, changedFiles = files))
        model.record(event(taskId = "run", status = JuggEvent.Status.SUCCEEDED).copy(durationMillis = 1200))
        model.record(event(taskId = "run", category = JuggEvent.Category.DEPLOY,
            status = JuggEvent.Status.SUCCEEDED, isTaskTerminal = true).copy(
            deployType = JuggDeployData.DeployType.COMPAT_HOT_FIX, durationMillis = 3000))

        val snapshot = model.snapshot()
        assertNull(snapshot.currentTask)
        assertEquals(1, snapshot.recentRuns.size)
        assertEquals(JuggDeployData.DeployType.COMPAT_HOT_FIX, snapshot.recentRuns.single().deployType)
        assertEquals(files, snapshot.recentRuns.single().changedFiles)
        assertEquals(1, snapshot.sessionStats.compiles)
        assertEquals(1, snapshot.sessionStats.hotFixes)
    }

    @Test
    fun `failed and canceled runs do not increase successful session counters`() {
        val model = JuggControlPanelModel()
        model.record(event(taskId = "failed", status = JuggEvent.Status.STARTED))
        model.record(event(taskId = "failed", status = JuggEvent.Status.FAILED,
            isTaskTerminal = true).copy(deployType = JuggDeployData.DeployType.INSTALL))
        model.record(event(taskId = "canceled", status = JuggEvent.Status.STARTED))
        model.record(event(taskId = "canceled", status = JuggEvent.Status.CANCELED,
            isTaskTerminal = true).copy(deployType = JuggDeployData.DeployType.HOT_RELOAD))

        assertEquals(JuggControlPanelModel.SessionStats(), model.snapshot().sessionStats)
        assertEquals(2, model.snapshot().recentRuns.size)
    }

    @Test
    fun `recent runs keep their own bounded window`() {
        val model = JuggControlPanelModel(maxRecentRuns = 2)
        repeat(3) { index ->
            model.record(event(taskId = "run-$index", status = JuggEvent.Status.STARTED))
            model.record(event(taskId = "run-$index", status = JuggEvent.Status.SUCCEEDED,
                isTaskTerminal = true))
        }

        assertEquals(listOf("run-2", "run-1"), model.snapshot().recentRuns.map { it.taskId })
    }

    private fun event(
        taskId: String? = null,
        source: JuggEvent.Source = JuggEvent.Source.IDE,
        category: JuggEvent.Category = JuggEvent.Category.COMPILE,
        phase: JuggEvent.Phase? = null,
        status: JuggEvent.Status = JuggEvent.Status.SUCCEEDED,
        level: JuggEvent.Level = JuggEvent.Level.INFO,
        title: String = "event",
        isTaskTerminal: Boolean = false,
    ): JuggEvent {
        return JuggEvent(
            taskId = taskId,
            source = source,
            category = category,
            phase = phase,
            status = status,
            level = level,
            title = title,
            isTaskTerminal = isTaskTerminal,
            timestamp = 1L,
        )
    }
}
