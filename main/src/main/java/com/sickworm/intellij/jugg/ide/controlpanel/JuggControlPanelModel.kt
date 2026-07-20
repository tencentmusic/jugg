package com.sickworm.intellij.jugg.ide.controlpanel

import java.util.concurrent.CopyOnWriteArrayList

/**
 * JuggControlPanelModel records structured execution events and exposes a bounded immutable snapshot.
 * It deliberately has no Project or Swing dependency so IDE and CLI runtimes can share it.
 */
class JuggControlPanelModel(
    private val maxRecentEvents: Int = DEFAULT_MAX_RECENT_EVENTS,
) {

    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()
    private val terminalTaskIds = LinkedHashSet<String>()
    private var nextEventId = 1L
    private var state = Snapshot()

    init {
        require(maxRecentEvents > 0) { "maxRecentEvents must be positive" }
    }

    @Synchronized
    fun snapshot(): Snapshot = state

    fun subscribe(listener: (Snapshot) -> Unit): AutoCloseable {
        listeners.add(listener)
        listener(snapshot())
        return AutoCloseable { listeners.remove(listener) }
    }

    fun updateContext(context: Context) {
        updateState { current ->
            if (current.context == context) current else current.copy(context = context)
        }
    }

    fun updateSettings(settings: Settings) {
        updateState { current ->
            if (current.settings == settings) current else current.copy(settings = settings)
        }
    }

    fun updateHealth(items: List<HealthItem>) {
        val immutableItems = items.toList()
        updateState { current ->
            if (current.healthItems == immutableItems) current else current.copy(healthItems = immutableItems)
        }
    }

    fun record(event: JuggEvent) {
        val newState = synchronized(this) {
            val ownsCurrentTask = event.ownsCurrentTask()
            if (ownsCurrentTask && event.isTaskTerminal && event.taskId in terminalTaskIds) {
                return
            }

            var recentEvents = state.recentEvents
            var currentTask = state.currentTask
            val taskId = event.taskId
            if (ownsCurrentTask && taskId != null && event.status == JuggEvent.Status.STARTED &&
                currentTask?.taskId != taskId
            ) {
                currentTask?.let { active ->
                    val canceled = normalizeEvent(JuggEvent(
                        taskId = active.taskId,
                        source = active.source,
                        category = event.category,
                        phase = JuggEvent.Phase.COMPLETED,
                        status = JuggEvent.Status.CANCELED,
                        level = JuggEvent.Level.WARN,
                        title = "Task canceled by a newer task",
                        timestamp = event.timestamp,
                        isTaskTerminal = true,
                    ))
                    recentEvents = appendBounded(recentEvents, canceled)
                    rememberTerminalTask(active.taskId)
                }
            }

            val normalizedEvent = normalizeEvent(event)
            recentEvents = appendBounded(recentEvents, normalizedEvent)
            if (ownsCurrentTask) {
                currentTask = reduceCurrentTask(currentTask, normalizedEvent)
            }
            if (ownsCurrentTask && normalizedEvent.isTaskTerminal) {
                normalizedEvent.taskId?.let(::rememberTerminalTask)
            }

            val lastDeploy = if (normalizedEvent.category == JuggEvent.Category.DEPLOY && normalizedEvent.isTaskTerminal) {
                DeploySummary(
                    taskId = normalizedEvent.taskId,
                    status = normalizedEvent.status,
                    title = normalizedEvent.title,
                    detail = normalizedEvent.detail,
                    timestamp = normalizedEvent.timestamp,
                    durationMillis = normalizedEvent.durationMillis,
                )
            } else {
                state.lastDeploy
            }

            state.copy(
                currentTask = currentTask,
                lastDeploy = lastDeploy,
                recentEvents = recentEvents,
                version = state.version + 1,
            ).also { state = it }
        }
        notifyListeners(newState)
    }

    private fun JuggEvent.ownsCurrentTask(): Boolean {
        return category == JuggEvent.Category.COMPILE || category == JuggEvent.Category.DEPLOY
    }

    private fun reduceCurrentTask(current: TaskSnapshot?, event: JuggEvent): TaskSnapshot? {
        val taskId = event.taskId ?: return current
        if (event.isTaskTerminal) {
            return if (current?.taskId == taskId) null else current
        }
        if (current?.taskId == taskId) {
            return current.copy(
                phase = event.phase ?: current.phase,
                title = event.title,
                updatedAt = event.timestamp,
            )
        }
        return TaskSnapshot(
            taskId = taskId,
            source = event.source,
            phase = event.phase,
            title = event.title,
            startedAt = event.timestamp,
            updatedAt = event.timestamp,
        )
    }

    private fun normalizeEvent(event: JuggEvent): JuggEvent {
        if (event.id > 0) {
            nextEventId = maxOf(nextEventId, event.id + 1)
            return event
        }
        return event.copy(id = nextEventId++)
    }

    private fun appendBounded(events: List<JuggEvent>, event: JuggEvent): List<JuggEvent> {
        val result = events + event
        return if (result.size <= maxRecentEvents) result else result.takeLast(maxRecentEvents)
    }

    private fun rememberTerminalTask(taskId: String) {
        terminalTaskIds.add(taskId)
        while (terminalTaskIds.size > maxRecentEvents) {
            terminalTaskIds.remove(terminalTaskIds.first())
        }
    }

    private fun updateState(transform: (Snapshot) -> Snapshot) {
        val newState = synchronized(this) {
            val transformed = transform(state)
            if (transformed === state || transformed == state) {
                return
            }
            transformed.copy(version = state.version + 1).also { state = it }
        }
        notifyListeners(newState)
    }

    private fun notifyListeners(snapshot: Snapshot) {
        listeners.forEach { listener -> runCatching { listener(snapshot) } }
    }

    companion object {
        private const val DEFAULT_MAX_RECENT_EVENTS = 200
    }

    /** Provides the current project facts rendered by Jugg status consumers. */
    data class Context(
        val configuration: String = "",
        val buildTarget: String = "",
        val packageName: String = "",
        val devices: List<String> = emptyList(),
        val changedFileCount: Int = 0,
        val hasBaseline: Boolean = false,
        val isHistoryAvailable: Boolean = false,
    )

    /** Provides persisted Jugg switches rendered and edited by the control panel. */
    data class Settings(
        val confirmFallbackWhenNoFileChanges: Boolean = true,
        val alwaysRestartAppAfterDeployment: Boolean = false,
        val quickDeploy: Boolean = true,
        val autoFallbackAfterDeployFailure: Boolean = false,
        val embedChangesIntoApk: Boolean = false,
        val useProjectKotlinCompiler: Boolean = true,
        val backupClasspath: Boolean = false,
    )

    /** Summarizes the only task currently running in a Jugg runtime. */
    data class TaskSnapshot(
        val taskId: String,
        val source: JuggEvent.Source,
        val phase: JuggEvent.Phase?,
        val title: String,
        val startedAt: Long,
        val updatedAt: Long,
    )

    /** Summarizes the latest deploy result without retaining device runtime objects. */
    data class DeploySummary(
        val taskId: String?,
        val status: JuggEvent.Status,
        val title: String,
        val detail: String?,
        val timestamp: Long,
        val durationMillis: Long?,
    )

    /** Describes a project problem that the user can act on. */
    data class HealthItem(
        val level: JuggEvent.Level,
        val message: String,
        val action: String? = null,
    )

    /** Immutable projection consumed by IDE and CLI status views. */
    data class Snapshot(
        val context: Context = Context(),
        val currentTask: TaskSnapshot? = null,
        val lastDeploy: DeploySummary? = null,
        val healthItems: List<HealthItem> = emptyList(),
        val recentEvents: List<JuggEvent> = emptyList(),
        val settings: Settings = Settings(),
        val version: Long = 0,
    )
}
