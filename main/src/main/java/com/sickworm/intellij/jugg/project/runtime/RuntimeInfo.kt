package com.sickworm.intellij.jugg.project.runtime

/** Describes the runtime and host information shared by server, locks, and updates. */
data class RuntimeInfo(
    val runtimeType: String,
    val runtimeVersion: String,
    val hostVersion: String,
    val buildTime: String,
)
