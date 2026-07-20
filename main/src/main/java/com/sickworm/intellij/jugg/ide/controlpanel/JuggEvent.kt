package com.sickworm.intellij.jugg.ide.controlpanel

/**
 * JuggEvent is a structured, user-readable execution fact shared by IDE and CLI consumers.
 */
data class JuggEvent(
    val id: Long = 0,
    val taskId: String? = null,
    val source: Source,
    val category: Category,
    val phase: Phase? = null,
    val status: Status,
    val level: Level,
    val title: String,
    val detail: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val durationMillis: Long? = null,
    val isTaskTerminal: Boolean = false,
) {
    /** Identifies where a user-visible Jugg event originated. */
    enum class Source {
        IDE,
        CLI,
        MCP,
    }

    /** Groups user-visible Jugg events by product capability. */
    enum class Category {
        SYNC,
        COMPILE,
        DEPLOY,
        APP,
        CLI,
        MCP,
    }

    /** Describes the execution phase represented by a Jugg event. */
    enum class Phase {
        PREPARING,
        DETECTING_CHANGES,
        COMPILING,
        DEPLOYING,
        LAUNCHING,
        RESUMING,
        INSTRUMENTING,
        COMPLETED,
    }

    /** Describes the business result of a Jugg event. */
    enum class Status {
        STARTED,
        SUCCEEDED,
        FAILED,
        CANCELED,
        WARNING,
        SKIPPED,
    }

    /** Controls the severity filter used by event consumers. */
    enum class Level {
        INFO,
        WARN,
        ERROR,
    }
}
