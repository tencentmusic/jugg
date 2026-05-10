package com.sickworm.intellij.jugg.ai.mcp

/**
 * McpProjectInfo carries projectDir, initialization status, and full-compile state.
 */
data class McpProjectInfo(
    val projectDir: String,
    val initialized: Boolean,
    val hasBeenFullCompiled: Boolean = false,
)
