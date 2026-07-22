package com.sickworm.intellij.jugg.ai.mcp

/**
 * McpErrorCode defines domain error-code identifiers for MCP tool failures.
 */
object McpErrorCode {
    const val INVALID_JSON_RPC = "INVALID_JSON_RPC"
    const val METHOD_NOT_SUPPORTED = "METHOD_NOT_SUPPORTED"
    const val TOOL_NOT_FOUND = "TOOL_NOT_FOUND"
    const val INVALID_PARAMS = "INVALID_PARAMS"
    const val INVALID_REGEX = "INVALID_REGEX"
    const val NO_DEPLOY_BASELINE = "NO_DEPLOY_BASELINE"
    const val PROJECT_NOT_INITIALIZED = "PROJECT_NOT_INITIALIZED"
    const val NO_DEVICE = "NO_DEVICE"
    const val DEVICE_NOT_INTERACTIVE = "DEVICE_NOT_INTERACTIVE"
    const val APP_NOT_FOREGROUND = "APP_NOT_FOREGROUND"
    const val FEATURE_NOT_SUPPORTED = "FEATURE_NOT_SUPPORTED"
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
}
