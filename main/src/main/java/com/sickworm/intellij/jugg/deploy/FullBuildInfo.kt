package com.sickworm.intellij.jugg.deploy

import com.google.gson.Gson
import com.sickworm.intellij.jugg.compiler.BuildTarget

/**
 * FullBuildInfo records metadata of the latest successful Gradle full build baseline.
 */
data class FullBuildInfo(
    val compileCommand: String?,
    val buildTarget: BuildTarget,
    val createdAt: Long,
)

/**
 * FullBuildInfoSerializer converts [FullBuildInfo] to a versioned JSON payload.
 */
class FullBuildInfoSerializer {

    fun serialize(info: FullBuildInfo): String {
        return Gson().toJson(JsonData(VERSION, info.compileCommand, info.buildTarget.name, info.createdAt))
    }

    fun deserialize(json: String): FullBuildInfo {
        val data = Gson().fromJson(json, JsonData::class.java)
        val buildTarget = runCatching { BuildTarget.valueOf(data.buildTarget ?: "") }
            .getOrDefault(BuildTarget.APP)
        return FullBuildInfo(
            compileCommand = data.compileCommand,
            buildTarget = buildTarget,
            createdAt = data.createdAt ?: 0L,
        )
    }

    private data class JsonData(
        val version: Int? = VERSION,
        val compileCommand: String?,
        val buildTarget: String?,
        val createdAt: Long?,
    )

    companion object {
        private const val VERSION = 1
    }
}
