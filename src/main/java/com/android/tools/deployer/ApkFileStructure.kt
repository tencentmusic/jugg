package com.android.tools.deployer

data class ApkFileStructure(
    val classFiles: Map<String, AidpFileInfo>,
    val overlayFiles: Map<String, AidpFileInfo>
)

data class AidpFileInfo(
    val name: String,
    val checksum: Long
)