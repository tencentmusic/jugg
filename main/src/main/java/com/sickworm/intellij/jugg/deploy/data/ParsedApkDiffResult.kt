package com.sickworm.intellij.jugg.deploy.data

data class ParsedApkDiffResult(
    val updatedApkInfos: Int = 0,

    val addedOverlayFiles: List<String> = emptyList(),
    val removedOverlayFiles: List<String> = emptyList(),
    val updatedOverlayFiles: List<String> = emptyList(),

    val addedDexFiles: List<String> = emptyList(),
    val removedDexFiles: List<String> = emptyList(),
    val updatedDexFiles: List<String> = emptyList(),
)