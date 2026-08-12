package com.sickworm.intellij.jugg.ide.controlpanel

import java.util.concurrent.CopyOnWriteArrayList

/**
 * JuggControlPanelModel records structured execution events and exposes a bounded immutable snapshot.
 * It deliberately has no Project or Swing dependency so IDE and CLI runtimes can share it.
 */
class JuggControlPanelModel(
    private val maxRecentEvents: Int = DEFAULT_MAX_RECENT_EVENTS,
    private val maxRecentRuns: Int = DEFAULT_MAX_RECENT_RUNS,
) {

    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()
    private val terminalTaskIds = LinkedHashSet<String>()
    private var nextEventId = 1L
    private var state = Snapshot()

    init {
        require(maxRecentEvents > 0) { "maxRecentEvents must be positive" }
        require(maxRecentRuns > 0) { "maxRecentRuns must be positive" }
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
            var recentRuns = state.recentRuns
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
                    recentRuns = appendRun(recentRuns, active.toRunSummary(canceled))
                    rememberTerminalTask(active.taskId)
                }
            }

            val normalizedEvent = normalizeEvent(event)
            recentEvents = appendBounded(recentEvents, normalizedEvent)
            if (ownsCurrentTask) {
                currentTask = reduceCurrentTask(currentTask, normalizedEvent)
            }
            val completedTask = if (ownsCurrentTask && normalizedEvent.isTaskTerminal &&
                currentTask == null && state.currentTask?.taskId == normalizedEvent.taskId
            ) state.currentTask else null
            if (completedTask != null) {
                recentRuns = appendRun(recentRuns, completedTask.toRunSummary(normalizedEvent))
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
                recentRuns = recentRuns,
                sessionStats = reduceSessionStats(state.sessionStats, normalizedEvent),
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
                compileMode = event.compileMode ?: current.compileMode,
                deployType = event.deployType ?: current.deployType,
                compileDurationMillis = if (event.category == JuggEvent.Category.COMPILE &&
                    event.status == JuggEvent.Status.SUCCEEDED
                ) event.durationMillis ?: current.compileDurationMillis else current.compileDurationMillis,
                deployDurationMillis = if (event.category == JuggEvent.Category.DEPLOY &&
                    event.status == JuggEvent.Status.SUCCEEDED
                ) (current.deployDurationMillis ?: 0) + (event.durationMillis ?: 0) else current.deployDurationMillis,
                fallback = event.fallback ?: current.fallback,
                changedFiles = if (event.changedFiles.isNotEmpty()) event.changedFiles else current.changedFiles,
            )
        }
        return TaskSnapshot(
            taskId = taskId,
            source = event.source,
            phase = event.phase,
            title = event.title,
            startedAt = event.timestamp,
            updatedAt = event.timestamp,
            compileMode = event.compileMode,
            deployType = event.deployType,
            fallback = event.fallback,
            changedFiles = event.changedFiles,
        )
    }

    private fun TaskSnapshot.toRunSummary(terminalEvent: JuggEvent): RunSummary {
        return RunSummary(
            taskId = taskId,
            startedAt = startedAt,
            completedAt = terminalEvent.timestamp,
            compileMode = terminalEvent.compileMode ?: compileMode,
            deployType = terminalEvent.deployType ?: deployType,
            terminalCategory = terminalEvent.category,
            status = terminalEvent.status,
            compileDurationMillis = compileDurationMillis,
            deployDurationMillis = deployDurationMillis,
            totalDurationMillis = terminalEvent.durationMillis ?: (terminalEvent.timestamp - startedAt),
            fallback = terminalEvent.fallback ?: fallback,
            failureReason = terminalEvent.detail,
            changedFiles = if (terminalEvent.changedFiles.isNotEmpty()) terminalEvent.changedFiles else changedFiles,
        )
    }

    private fun appendRun(runs: List<RunSummary>, run: RunSummary): List<RunSummary> {
        val result = listOf(run) + runs.filterNot { it.taskId == run.taskId }
        return result.take(maxRecentRuns)
    }

    private fun reduceSessionStats(stats: SessionStats, event: JuggEvent): SessionStats {
        if (event.status != JuggEvent.Status.SUCCEEDED) return stats
        if (event.category == JuggEvent.Category.COMPILE && !event.isTaskTerminal) {
            return stats.copy(compiles = stats.compiles + 1)
        }
        if (!event.isTaskTerminal) return stats
        if (event.didInstall) return stats.copy(installs = stats.installs + 1)
        return when (event.deployType) {
            com.sickworm.intellij.jugg.deploy.run.JuggDeployData.DeployType.HOT_RELOAD -> stats.copy(hotReloads = stats.hotReloads + 1)
            com.sickworm.intellij.jugg.deploy.run.JuggDeployData.DeployType.HOT_FIX,
            com.sickworm.intellij.jugg.deploy.run.JuggDeployData.DeployType.COMPAT_HOT_FIX -> stats.copy(hotFixes = stats.hotFixes + 1)
            com.sickworm.intellij.jugg.deploy.run.JuggDeployData.DeployType.INSTALL,
            com.sickworm.intellij.jugg.deploy.run.JuggDeployData.DeployType.EMBEDDED -> stats.copy(installs = stats.installs + 1)
            else -> stats
        }
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
        private const val DEFAULT_MAX_RECENT_RUNS = 20
    }

    /** Provides the current project facts rendered by Jugg status consumers. */
    data class Context(
        val configuration: String = "",
        val buildTarget: String = "",
        val packageName: String = "",
        val devices: List<String> = emptyList(),
        val changedFileCount: Int = 0,
        val changedFiles: List<JuggEvent.ChangedFileSnapshot> = emptyList(),
        val hasBaseline: Boolean = false,
        val isHistoryAvailable: Boolean = false,
    )

    /** Provides persisted Jugg switches rendered and edited by the control panel. */
    data class Settings(
        val confirmFallbackWhenNoFileChanges: Boolean = true,
        val alwaysRestartAppAfterDeployment: Boolean = false,
        val compatibleDeployment: Boolean = true,
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
        val compileMode: JuggEvent.CompileMode? = null,
        val deployType: com.sickworm.intellij.jugg.deploy.run.JuggDeployData.DeployType? = null,
        val compileDurationMillis: Long? = null,
        val deployDurationMillis: Long? = null,
        val fallback: String? = null,
        val changedFiles: List<JuggEvent.ChangedFileSnapshot> = emptyList(),
    )

    /** Summarizes one completed user run for the current manager session. */
    data class RunSummary(
        val taskId: String,
        val startedAt: Long,
        val completedAt: Long,
        val compileMode: JuggEvent.CompileMode?,
        val deployType: com.sickworm.intellij.jugg.deploy.run.JuggDeployData.DeployType?,
        val terminalCategory: JuggEvent.Category,
        val status: JuggEvent.Status,
        val compileDurationMillis: Long?,
        val deployDurationMillis: Long?,
        val totalDurationMillis: Long,
        val fallback: String?,
        val failureReason: String?,
        val changedFiles: List<JuggEvent.ChangedFileSnapshot>,
    )

    /** Counts successful outcomes in the current manager session. */
    data class SessionStats(
        val compiles: Int = 0,
        val hotReloads: Int = 0,
        val hotFixes: Int = 0,
        val installs: Int = 0,
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
        val recentRuns: List<RunSummary> = emptyList(),
        val sessionStats: SessionStats = SessionStats(),
        val settings: Settings = Settings(),
        val version: Long = 0,
    )
}
