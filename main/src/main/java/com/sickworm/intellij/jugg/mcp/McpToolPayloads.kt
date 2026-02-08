package com.sickworm.intellij.jugg.mcp

data class McpProjectInfo(
    val projectDir: String,
    val initialized: Boolean,
)

data class McpDeviceInfo(
    val serial: String? = null,
    val name: String? = null,
    val isOnline: Boolean = false,
)
