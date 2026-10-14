package com.sickworm.intellij.jugg.diagnostics

import com.google.gson.GsonBuilder
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.Locale
import java.util.UUID

/** Preserves independent local evidence and produces a prompt for a user's investigation Agent. */
class InvestigationSnapshotBuilder(
    private val outputDir: File,
    private val projectDir: File,
    private val logger: Logger,
) {
    /** Identifies an existing artifact, its local state, and its deployment ownership. */
    data class Source(
        val file: File,
        val category: String,
        val targetApkPaths: List<String> = emptyList(),
        val deployPath: String? = null,
    )

    /** Contains the persisted evidence location, copyable prompt, and collection failure count. */
    data class Snapshot(val directory: File, val prompt: String, val missingCount: Int)

    private val gson = GsonBuilder().setPrettyPrinting().create()

    /** Copies evidence before publishing the manifest; an individual failure preserves other usable files. */
    fun build(
        problemType: String,
        description: String,
        environment: Map<String, Any?>,
        sources: List<Source>,
        evidenceFailures: Map<String, String>,
        taskOutput: String,
        textEvidence: Map<String, String> = emptyMap(),
    ): Snapshot {
        val directory = File(outputDir, UUID.randomUUID().toString())
        check(directory.mkdirs()) { "Cannot create investigation directory: $directory" }
        val createdAt = OffsetDateTime.now().toString()
        val failures = evidenceFailures.toMutableMap()
        val files = sources.mapIndexedNotNull { index, source -> copyEvidence(directory, index, source, failures) }
        val generatedFiles = (textEvidence + ("task-output" to taskOutput)).mapNotNull { (name, content) ->
            require(name.matches(Regex("[a-zA-Z0-9_-]+"))) { "Invalid evidence name: $name" }
            saveTextEvidence(directory, name, content, failures)
        }
        val manifest = linkedMapOf(
            "schemaVersion" to 1, "createdAt" to createdAt, "projectDir" to projectDir.absolutePath,
            "problemType" to problemType, "description" to description, "environment" to environment,
            "files" to files, "textEvidence" to generatedFiles,
            "failures" to failures,
            "deploymentEvidenceBoundary" to "Local deployment records do not prove what the device loaded.",
        )
        File(directory, "manifest.json").writeText(gson.toJson(manifest))
        val prompt = buildPrompt(directory, problemType, description, createdAt, environment, failures)
        File(directory, "prompt.txt").writeText(prompt)
        logger.debug("Missing/failed collection items: ${failures.size}, " +
                "investigation directory: ${directory.absolutePath}")
        return Snapshot(directory, prompt, failures.size)
    }

    private fun saveTextEvidence(
        directory: File,
        name: String,
        content: String,
        failures: MutableMap<String, String>,
    ): Map<String, Any?>? = try {
        val file = File(directory, "$name.log")
        file.writeText(content)
        mapOf("path" to file.name, "size" to file.length(), "sha256" to sha256(file))
    } catch (e: IOException) {
        failures[name] = e.toString()
        logger.warn("Cannot save investigation text evidence: $name", e)
        null
    }

    private fun copyEvidence(
        directory: File,
        index: Int,
        source: Source,
        failures: MutableMap<String, String>,
    ): Map<String, Any?>? {
        require(source.category.matches(Regex("[a-z-]+"))) { "Invalid evidence category: ${source.category}" }
        val path = "${source.category}/${index.toString().padStart(5, '0')}-${source.file.name}"
        val target = File(directory, path)
        return try {
            if (!source.file.isFile) throw IOException("Evidence file is missing: ${source.file}")
            val originalSize = source.file.length()
            val originalModifiedAt = source.file.lastModified()
            source.file.copyTo(target)
            if (source.file.length() != originalSize || source.file.lastModified() != originalModifiedAt) {
                throw IOException("Evidence changed during collection: ${source.file}")
            }
            linkedMapOf(
                "path" to path, "source" to source.file.absolutePath, "category" to source.category,
                "targetApkPaths" to source.targetApkPaths, "deployPath" to source.deployPath,
                "sourceModifiedAt" to originalModifiedAt, "size" to target.length(), "sha256" to sha256(target),
            )
        } catch (e: IOException) {
            target.delete()
            failures["${source.category}:${source.file.absolutePath}"] = e.toString()
            logger.warn("Cannot preserve investigation evidence: ${source.file}", e)
            null
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(8192)
            var count = input.read(buffer)
            while (count != -1) {
                digest.update(buffer, 0, count)
                count = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun buildPrompt(
        directory: File,
        problemType: String,
        description: String,
        createdAt: String,
        environment: Map<String, Any?>,
        failures: Map<String, String>,
    ): String {
        val language = if (Locale.getDefault().language == "zh") "zh" else "en"
        val resource = "/diagnostics/ask-ai-$language.md"
        val template = checkNotNull(javaClass.getResourceAsStream(resource)) { "Missing investigation template: $resource" }
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val values = mapOf(
            "problemType" to problemType, "description" to description,
            "projectDir" to projectDir.absolutePath, "directory" to directory.absolutePath,
            "manifest" to File(directory, "manifest.json").absolutePath, "createdAt" to createdAt,
            "environment" to gson.toJson(environment), "failures" to gson.toJson(failures),
        )
        // Replace only template tokens; user-provided text must never be expanded again.
        return Regex("\\{\\{([a-zA-Z]+)\\}\\}").replace(template) { values.getValue(it.groupValues[1]) }
    }
}
