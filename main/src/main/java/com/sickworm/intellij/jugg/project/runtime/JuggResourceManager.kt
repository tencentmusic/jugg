package com.sickworm.intellij.jugg.project.runtime

import com.google.gson.Gson
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Describes one file embedded in a runtime resource bundle. */
data class RuntimeResourceFile(
    val path: String,
    val executable: Boolean = false,
)

/** Describes the protocol and files that form one runtime resource bundle. */
data class RuntimeResourceMetadata(
    val schemaVersion: Int,
    val protocolVersion: String,
    val files: List<RuntimeResourceFile>,
)

/** Returns the prepared target directory together with its embedded metadata. */
data class PreparedRuntimeResource(
    val directory: File,
    val metadata: RuntimeResourceMetadata,
)

/** Refreshes classpath runtime resources under the shared global write lock. */
class JuggResourceManager(
    private val classLoader: ClassLoader = JuggResourceManager::class.java.classLoader,
    private val globalRootDir: File = JuggGlobalPathManager.rootDir,
) {

    fun prepare(resourceRoot: String): PreparedRuntimeResource {
        check(!Path.of(resourceRoot).isAbsolute) { "Runtime resource root is absolute: $resourceRoot" }
        val normalizedRoot = resourceRoot.trim('/')
        check(normalizedRoot.isNotBlank()) { "Runtime resource root is empty" }
        val resourcePath = Path.of(normalizedRoot)
        check(resourcePath.none { it.toString() == "." || it.toString() == ".." }) {
            "Runtime resource root contains traversal: $resourceRoot"
        }
        val metadata = readMetadata(normalizedRoot)
        validateMetadata(metadata)
        return withGlobalResourceLock("Prepare runtime resource", globalRootDir) {
            val targetDir = resolveTarget(normalizedRoot)
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
            check(it.path.isNotBlank()) { "Invalid runtime resource entry: ${it.path}" }
        }
    }

    private fun resolveTarget(resourceRoot: String): File {
        val configuredResourcesPath = JuggGlobalPathManager.resourceFile("", globalRootDir).absoluteFile.toPath().normalize()
        check(!Files.isSymbolicLink(configuredResourcesPath)) { "Shared runtime resource directory is a symbolic link" }
        Files.createDirectories(configuredResourcesPath)
        val resourcesPath = configuredResourcesPath.toRealPath()
        val targetPath = resourcesPath.resolve(resourceRoot).normalize()
        check(targetPath != resourcesPath && targetPath.startsWith(resourcesPath)) {
            "Runtime resource target escapes the shared resource directory"
        }
        createDirectoriesWithoutSymbolicLinks(resourcesPath, targetPath)
        return targetPath.toFile()
    }

    private fun prepareFile(resourceRoot: String, targetDir: File, entry: RuntimeResourceFile) {
        val targetRoot = targetDir.toPath()
        val targetPath = targetRoot.resolve(entry.path).normalize()
        check(targetPath != targetRoot && targetPath.startsWith(targetRoot)) {
            "Runtime resource path escapes target directory: ${entry.path}"
        }
        createDirectoriesWithoutSymbolicLinks(targetRoot, checkNotNull(targetPath.parent))
        val target = targetPath.toFile()
        val resourcePath = "$resourceRoot/${entry.path}"
        val input = classLoader.getResourceAsStream(resourcePath)
            ?: throw IllegalStateException("Runtime resource not found: $resourcePath")
        val tempFile = File(target.parentFile, "${target.name}.${UUID.randomUUID()}.tmp")
        try {
            input.use { source -> tempFile.outputStream().use { output -> source.copyTo(output) } }
            if (entry.executable) check(tempFile.setExecutable(true, true)) {
                "Cannot make runtime resource executable: ${entry.path}"
            }
            moveAtomically(tempFile, target)
        } finally {
            tempFile.delete()
        }
    }

    private fun createDirectoriesWithoutSymbolicLinks(root: Path, target: Path) {
        check(target.startsWith(root)) { "Runtime resource directory escapes the shared resource directory" }
        var current = root
        root.relativize(target).forEach { name ->
            current = current.resolve(name)
            check(!Files.isSymbolicLink(current)) { "Runtime resource directory is a symbolic link: $current" }
            Files.createDirectories(current)
        }
    }

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val SCHEMA_VERSION = 1
    }
}
