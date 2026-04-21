package com.sickworm.intellij.jugg.gradle.compile

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.sickworm.intellij.jugg.compiler.BuildTarget

/**
 * BaseBuildCmdRecord persists the last full-build command and its build target.
 *
 * Serialized as JSON: {"compileCommand":"...","buildTarget":"APP"}.
 * Legacy single-line plain-text format is accepted for backwards compatibility and treated as APP target.
 */
data class BaseBuildCmdRecord(
    val compileCommand: String,
    val buildTarget: BuildTarget,
) {

    fun toJson(): String = Gson().toJson(JsonData(compileCommand, buildTarget.name))

    companion object {

        /** Parses JSON or falls back to legacy plain-text (treated as APP). */
        fun fromJson(text: String): BaseBuildCmdRecord {
            return try {
                val data = Gson().fromJson(text, JsonData::class.java)
                if (data?.compileCommand != null) {
                    val target = runCatching { BuildTarget.valueOf(data.buildTarget ?: "") }
                        .getOrDefault(BuildTarget.APP)
                    BaseBuildCmdRecord(data.compileCommand, target)
                } else {
                    // malformed JSON object without expected fields – treat as legacy
                    BaseBuildCmdRecord(text, BuildTarget.APP)
                }
            } catch (_: JsonSyntaxException) {
                // legacy plain-text format
                BaseBuildCmdRecord(text, BuildTarget.APP)
            }
        }
    }

    private data class JsonData(val compileCommand: String?, val buildTarget: String?)
}
