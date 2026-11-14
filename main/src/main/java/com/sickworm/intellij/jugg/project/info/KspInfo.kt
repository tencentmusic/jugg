package com.sickworm.intellij.jugg.project.info

/** The selected KSP task's processor classpath, execution engine, and effective options. */
data class KspInfo(
    val processorDependencies: List<LibraryDependency> = emptyList(),
    /** Engine artifacts are kept separate from the project processor's dependencies. */
    val runtimeDependencies: List<LibraryDependency> = emptyList(),
    val arguments: Map<String, String> = emptyMap(),
    /** Null means only the legacy configuration was available, without task metadata. */
    val useKsp2: Boolean? = null,
    val languageVersion: String? = null,
    val apiVersion: String? = null,
)
