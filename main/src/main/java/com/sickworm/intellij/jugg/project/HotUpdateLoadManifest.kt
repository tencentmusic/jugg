package com.sickworm.intellij.jugg.project

/**
 * Describes the complete shared runtime jar set for one embedded build.
 */
data class HotUpdateLoadManifest(
    val baseEmbeddedBuildTime: String,
    val jarFileNames: List<String>,
)
