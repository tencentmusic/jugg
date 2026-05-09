package com.sickworm.intellij.jugg.ai.mcp

/**
 * McpProjectInfo carries projectDir, initialization status, and compile-history hint.
 */
data class McpProjectInfo(
    val projectDir: String,
    val initialized: Boolean,
    val hasCompiledBefore: Boolean = false,
)
