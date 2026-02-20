package com.sickworm.intellij.jugg.mcp

/**
 * McpProjectInfo carries projectDir and initialized.
 */
data class McpProjectInfo(
    val projectDir: String,
    val initialized: Boolean,
)
