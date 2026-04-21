package com.sickworm.intellij.jugg.gradle.compile

import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.project.JuggPathManager

/**
 * Persists and reloads the last full-build command used for baseline compile fallback.
 *
 * Storage format: JSON [BaseBuildCmdRecord]. Reads legacy plain-text format transparently.
 */
class BaseBuildCommandHelper(pathManager: JuggPathManager) {

    private val recordFile = pathManager.baseBuildCmdFile

    val hasBaseBuildCmd: Boolean get() = recordFile.exists()

    /** Records the compile command together with its [buildTarget]. */
    fun recordBaseBuildCmd(options: JuggGradleCompileOptions, buildTarget: BuildTarget = BuildTarget.APP) {
        val record = BaseBuildCmdRecord(options.compileCommand, buildTarget)
        recordFile.parentFile.mkdirs()
        recordFile.delete()
        recordFile.writeText(record.toJson())
    }

    /** Returns the persisted [BaseBuildCmdRecord], or null when no record exists. */
    fun getBaseBuildCmdRecord(): BaseBuildCmdRecord? {
        if (!recordFile.exists()) return null
        return BaseBuildCmdRecord.fromJson(recordFile.readText())
    }

    /** Convenience accessor kept for call-sites that only need the raw command string. */
    fun getBaseBuildCmd(): String? = getBaseBuildCmdRecord()?.compileCommand

    /**
     * Returns true when the [options] build target differs from the last recorded build target,
     * which means a full Gradle compile is required to switch modes.
     * Returns false when no record exists (first run), treating it as same-target.
     */
    fun isBuildTargetChanged(options: JuggGradleCompileOptions): Boolean {
        val record = getBaseBuildCmdRecord() ?: return false
        return record.buildTarget != options.buildTarget
    }
}
