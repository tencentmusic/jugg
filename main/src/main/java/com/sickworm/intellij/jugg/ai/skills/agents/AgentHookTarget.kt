package com.sickworm.intellij.jugg.ai.skills.agents

import java.io.File

/**
 * Describes one hook config target with its concrete config style and event mapping.
 */
data class AgentHookTarget(
    val settingsFile: File,
    val style: AgentHookConfigStyle,
    val startEventName: String,
    val stopEventName: String,
    val clientArgument: String,
    val editEventName: String? = null,
    val commandEventName: String? = null,
    val editMatcher: String? = null,
    val commandMatcher: String? = null,
)

/**
 * Supported hook config styles across different agent clients.
 */
enum class AgentHookConfigStyle {
    NESTED_EVENT_HOOKS,
    FLAT_EVENT_COMMANDS,
}
