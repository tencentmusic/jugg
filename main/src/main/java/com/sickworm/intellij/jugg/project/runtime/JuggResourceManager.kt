package com.sickworm.intellij.jugg.project.runtime

import com.google.gson.Gson
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Describes one verified file embedded in a versioned runtime resource bundle. */
data class RuntimeResourceFile(
    val path: String,
    val sha256: String,
    val executable: Boolean = false,
)

/** Describes one classpath JAR whose protocol classes must match the runtime bundle. */
data class RuntimeProtocolDependency(
    val path: String,
    val sha256: String,
)

/** Describes the protocol and files that form one runtime resource bundle. */
data class RuntimeResourceMetadata(
    val schemaVersion: Int,
    val protocolVersion: String,
    val files: List<RuntimeResourceFile>,
    val protocolDependencies: List<RuntimeProtocolDependency>,
)

/** Returns the verified target directory together with the metadata used to prepare it. */
data class PreparedRuntimeResource(
    val directory: File,
    val metadata: RuntimeResourceMetadata,
)

/** Extracts classpath runtime resources under the shared global write lock and verifies SHA-256. */
class JuggResourceManager(
    private val classLoader: ClassLoader = JuggResourceManager::class.java.classLoader,
    private val globalRootDir: File = JuggGlobalPathManager.rootDir,
) {

    fun prepare(resourceRoot: String, targetRelativePath: String): PreparedRuntimeResource {
        val normalizedRoot = resourceRoot.trim('/')
        val metadata = readMetadata(normalizedRoot)
        validateMetadata(metadata)
        return TaskRunnerManager.runGlobalWriteLocked("Prepare runtime resource", globalRootDir) {
            val targetDir = resolveTarget(targetRelativePath)
            metadata.files.forEach { prepareFile(normalizedRoot, targetDir, it) }
            PreparedRuntimeResource(targetDir, metadata)
        }
    }

    private fun readMetadata(resourceRoot: String): RuntimeResourceMetadata {
        val path = "$resourceRoot/metadata.json"
        val stream = classLoader.getResourceAsStream(path)
            ?: throw IllegalStateException("Runtime resource metadata not found: $path")
        return stream.reader(Charsets.UTF_8).use { Gson().fromJson(it, RuntimeResourceMetadata::class.java) }
            ?: throw IllegalStateException("Runtime resource metadata is empty: $path")
    }

    private fun validateMetadata(metadata: RuntimeResourceMetadata) {
        check(metadata.schemaVersion == SCHEMA_VERSION) {
            "Unsupported runtime resource schema: ${metadata.schemaVersion}"
        }
        check(metadata.protocolVersion.isNotBlank()) { "Runtime resource protocol version is empty" }
        check(metadata.files.isNotEmpty()) { "Runtime resource file list is empty" }
        metadata.files.forEach {
            check(it.path.isNotBlank() && it.sha256.matches(SHA_256_PATTERN)) {
                "Invalid runtime resource entry: ${it.path}"
            }
        }
        metadata.protocolDependencies.forEach {
            check(it.path.isNotBlank() && it.sha256.matches(SHA_256_PATTERN)) {
                "Invalid runtime protocol dependency: ${it.path}"
            }
        }
    }

    private fun resolveTarget(targetRelativePath: String): File {
        val targetDir = File(globalRootDir, targetRelativePath).canonicalFile
        val root = globalRootDir.canonicalFile
        check(targetDir.toPath().startsWith(root.toPath())) { "Runtime resource target escapes Jugg home" }
        targetDir.mkdirs()
        return targetDir
    }

    private fun prepareFile(resourceRoot: String, targetDir: File, entry: RuntimeResourceFile) {
        val target = File(targetDir, entry.path).canonicalFile
        check(target.toPath().startsWith(targetDir.canonicalFile.toPath())) {
            "Runtime resource path escapes target directory: ${entry.path}"
        }
        if (target.isFile && target.sha256() == entry.sha256) {
            if (entry.executable) check(target.setExecutable(true, true)) {
                "Cannot make runtime resource executable: ${entry.path}"
            }
            return
        }
        val resourcePath = "$resourceRoot/${entry.path}"
        val input = classLoader.getResourceAsStream(resourcePath)
            ?: throw IllegalStateException("Runtime resource not found: $resourcePath")
        target.parentFile.mkdirs()
        val tempFile = File(target.parentFile, "${target.name}.${UUID.randomUUID()}.tmp")
        try {
            input.use { source -> tempFile.outputStream().use { output -> source.copyTo(output) } }
            check(tempFile.sha256() == entry.sha256) { "Runtime resource checksum mismatch: ${entry.path}" }
            moveAtomically(tempFile, target)
            if (entry.executable) check(target.setExecutable(true, true)) {
                "Cannot make runtime resource executable: ${entry.path}"
            }
        } finally {
            tempFile.delete()
        }
    }

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val SCHEMA_VERSION = 1
        val SHA_256_PATTERN = Regex("[0-9a-f]{64}")
    }
}
