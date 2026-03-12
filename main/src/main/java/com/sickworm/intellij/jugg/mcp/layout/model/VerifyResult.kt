package com.sickworm.intellij.jugg.mcp.layout.model

/**
 * Verification result
 */
data class VerifyResult(
    val match: Boolean,
    val expected: String,
    val actual: String,
    val diff: String? = null
)
