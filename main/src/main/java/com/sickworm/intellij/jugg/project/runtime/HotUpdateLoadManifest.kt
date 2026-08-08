package com.sickworm.intellij.jugg.project.runtime

/**
 * Describes the complete shared runtime jar set for one embedded build.
 */
data class HotUpdateLoadManifest(
    val baseEmbeddedBuildTime: String,
    val jarFileNames: List<String>,
)

/** Describes the complete standalone runtime snapshot selected at daemon startup. */
data class StandaloneHotUpdateManifest(
    val schemaVersion: Int,
    val runtimeApiVersion: Int,
    val bootstrapApiVersion: Int,
    val targetVersion: String,
    val releaseBuildId: String,
    val releaseChannel: String,
    val toolingReleaseBuildId: String,
    val managedBy: String,
    val jarFileNames: List<String>,
    val jarSha256: Map<String, String>,
)
