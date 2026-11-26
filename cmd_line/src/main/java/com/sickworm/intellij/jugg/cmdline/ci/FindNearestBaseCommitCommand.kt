package com.sickworm.intellij.jugg.cmdline.ci

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.sickworm.intellij.jugg.cmdline.logger.CmdLineLogger
import com.sickworm.intellij.jugg.git.GitManager
import java.io.File

object FindNearestBaseCommitCommand {
    fun run(args: Array<String>): Boolean = try {
        val params = args.associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val git = GitManager(File(params.required("projectDir")))
        val target = params.required("targetCommit")
        val gson = GsonBuilder().serializeNulls().create()
        val input = gson.fromJson(File(params.required("candidateCommitsFile")).readText(), JsonElement::class.java)
        val entries = if (input.isJsonObject) input.asJsonObject.getAsJsonArray("commits") else input.asJsonArray
        val candidates = entries.map {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isString) { "Expected a candidate commit string" }
            it.asString
        }
        val resolved = git.resolveCommit(target)
        val match = git.findNearestFirstParentCommit(resolved, candidates.toSet())
        val result = mapOf(
            "schemaVersion" to 1,
            "targetCommit" to target,
            "resolvedTargetCommit" to resolved,
            "candidateCommits" to candidates,
            "matchedCommit" to match?.matchedCommit,
            "matchStrategy" to "first-parent",
            "matchedDistance" to match?.distance,
        )
        CiJsonWriter.write(File(params.required("output")), gson.toJson(result))
        match != null
    } catch (e: Exception) {
        CmdLineLogger.stdLogger.warn("findNearestBaseCommit failed: ${e.message}")
        false
    }

    private fun Map<String, String>.required(key: String): String =
        get(key)?.takeIf { it.isNotEmpty() } ?: error("Param '$key' not found")
}
