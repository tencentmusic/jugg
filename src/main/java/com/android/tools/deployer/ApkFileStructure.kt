package com.android.tools.deployer

data class ApkFileStructure(
    val classFiles: Map<String, JuggFileInfo>,
    val overlayFiles: Map<String, JuggFileInfo>
)

data class JuggFileInfo(
    val name: String,
    val checksum: Long
)