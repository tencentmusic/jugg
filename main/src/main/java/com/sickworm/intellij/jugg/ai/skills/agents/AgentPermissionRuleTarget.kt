package com.sickworm.intellij.jugg.ai.skills.agents

import java.io.File

/**
 * Describes one Codex permission rules file and the command prefix to allow.
 */
data class AgentPermissionRuleTarget(
    val rulesFile: File,
    val prefixPattern: List<String>,
)
