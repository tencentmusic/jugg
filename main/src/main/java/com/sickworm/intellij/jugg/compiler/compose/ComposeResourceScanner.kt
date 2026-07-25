package com.sickworm.intellij.jugg.compiler.compose

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.nio.file.Paths

/** Resource types supported by Compose Multiplatform accessors. */
enum class ComposeResourceType {
    DRAWABLE,
    STRING,
    STRING_ARRAY,
    PLURAL_STRING,
    FONT,
}

/** ClassLoader-neutral input for Compose resource accessor generation. */
data class ComposeResourceItem(
    val type: ComposeResourceType,
    val qualifiers: List<String>,
    val name: String,
    val path: Path,
    val offset: Long,
    val size: Long,
    val contentHash: Int,
)

/** Scans Compose 1.7.3 prepared resources into ClassLoader-neutral descriptors. */
class ComposeResourceScanner {

    private val valueConverter = ComposeValueResourceConverter()

    fun scanLegacy(resourceRoot: File): Map<ComposeResourceType, Map<String, List<ComposeResourceItem>>> {
        if (!resourceRoot.exists()) return emptyMap()
        requireDirectory(resourceRoot)
        val items = scanFiles(resourceRoot, "") + visibleChildren(resourceRoot)
            .filter { it.isDirectory && it.name.substringBefore('-') == "values" }
            .flatMap { directory ->
                val qualifiers = parseDirectory(directory).second
                visibleChildren(directory).flatMap { file ->
                    require(file.isFile && file.extension.equals("xml", ignoreCase = true)) {
                        "Unsupported legacy Compose resource: ${file.absolutePath}"
                    }
                    valueConverter.readLegacyStringNames(file).map { name ->
                        ComposeResourceItem(
                            ComposeResourceType.STRING,
                            qualifiers,
                            name.asUnderscoredIdentifier(),
                            resourceRoot.toPath().relativize(file.toPath()),
                            -1,
                            -1,
                            java.util.Arrays.hashCode(file.readBytes()),
                        )
                    }
                }
            }
        return items.groupBy { it.type }.mapValues { (_, typedItems) -> typedItems.groupBy { it.name } }
    }

    fun scan(
        resourceRoot: File,
        preparedValuesRoot: File,
        assetRelativePath: String,
    ): Map<ComposeResourceType, Map<String, List<ComposeResourceItem>>> {
        if (!resourceRoot.exists()) return emptyMap()
        requireDirectory(resourceRoot)
        val items = scanFiles(resourceRoot, assetRelativePath) +
            if (preparedValuesRoot.exists()) {
                requireDirectory(preparedValuesRoot)
                scanValues(preparedValuesRoot, assetRelativePath)
            } else {
                emptyList()
            }
        return items.groupBy { it.type }.mapValues { (_, typedItems) ->
            typedItems.groupBy { it.name }
        }
    }

    private fun scanFiles(root: File, assetRelativePath: String): List<ComposeResourceItem> =
        visibleChildren(root).flatMap { directory ->
            require(directory.isDirectory) { "Unsupported Compose resource: ${directory.absolutePath}" }
            val (typeName, qualifiers) = parseDirectory(directory)
            when (typeName) {
                "values", "files" -> emptyList()
                "drawable", "font" -> scanFileDirectory(directory, root, typeName, qualifiers, assetRelativePath)
                else -> throw IllegalArgumentException("Unsupported Compose resource directory: ${directory.absolutePath}")
            }
        }

    private fun scanFileDirectory(
        directory: File,
        root: File,
        typeName: String,
        qualifiers: List<String>,
        assetRelativePath: String,
    ): List<ComposeResourceItem> = visibleChildren(directory).map { file ->
        require(file.isFile) { "Unsupported Compose resource: ${file.absolutePath}" }
        ComposeResourceItem(
            type = FILE_TYPES.getValue(typeName),
            qualifiers = qualifiers,
            name = file.nameWithoutExtension.asUnderscoredIdentifier(),
            path = assetPath(assetRelativePath, root, file),
            offset = -1,
            size = -1,
            contentHash = java.util.Arrays.hashCode(file.readBytes()),
        )
    }

    private fun scanValues(root: File, assetRelativePath: String): List<ComposeResourceItem> =
        visibleChildren(root).flatMap { directory ->
            require(directory.isDirectory) { "Unsupported Compose resource: ${directory.absolutePath}" }
            val (typeName, qualifiers) = parseDirectory(directory)
            require(typeName in SUPPORTED_DIRECTORIES) {
                "Unsupported Compose resource directory: ${directory.absolutePath}"
            }
            if (typeName != "values") emptyList() else visibleChildren(directory).flatMap { file ->
                require(file.isFile && file.extension.equals("cvr", ignoreCase = true)) {
                    "Malformed Compose value resource: ${file.absolutePath}"
                }
                parseCvr(file, qualifiers, assetPath(assetRelativePath, root, file))
            }
        }

    private fun parseCvr(file: File, qualifiers: List<String>, path: Path): List<ComposeResourceItem> {
        val lines = readByteLines(file)
        require(lines.firstOrNull()?.text == "version:0") {
            "Malformed Compose value resource version: ${file.absolutePath}"
        }
        return lines.drop(1).map { line ->
            val fields = line.text.split('|')
            require(fields.size == 3 && fields[1].isNotEmpty()) {
                "Malformed Compose value resource record: ${file.absolutePath}"
            }
            val type = VALUE_TYPES[fields[0]] ?: throw IllegalArgumentException(
                "Unsupported Compose value resource type '${fields[0]}': ${file.absolutePath}",
            )
            ComposeResourceItem(
                type,
                qualifiers,
                fields[1].asUnderscoredIdentifier(),
                path,
                line.offset,
                line.size,
                fields[2].hashCode(),
            )
        }
    }

    private fun readByteLines(file: File): List<ByteLine> {
        val bytes = file.readBytes()
        val lines = mutableListOf<ByteLine>()
        var start = 0
        for (index in bytes.indices) {
            if (bytes[index] == '\n'.code.toByte()) {
                lines.add(decodeLine(bytes, start, index, file))
                start = index + 1
            }
        }
        if (start < bytes.size) lines.add(decodeLine(bytes, start, bytes.size, file))
        return lines
    }

    private fun decodeLine(bytes: ByteArray, start: Int, end: Int, file: File): ByteLine {
        val content = bytes.copyOfRange(start, end)
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content))
                .toString()
        } catch (exception: Exception) {
            throw IllegalArgumentException("Malformed UTF-8 Compose value resource: ${file.absolutePath}", exception)
        }
        require(!text.endsWith('\r')) { "Malformed Compose value resource line ending: ${file.absolutePath}" }
        return ByteLine(text, start.toLong(), content.size.toLong())
    }

    private fun parseDirectory(directory: File): Pair<String, List<String>> {
        val parts = directory.name.split('-')
        require(parts.none { it.isEmpty() }) { "Malformed Compose resource directory: ${directory.absolutePath}" }
        val type = parts.first().lowercase()
        val qualifiers = parts.drop(1)
        require(type != "files" || qualifiers.isEmpty()) {
            "The Compose files directory does not support qualifiers: ${directory.absolutePath}"
        }
        return type to qualifiers
    }

    private fun assetPath(assetRelativePath: String, root: File, file: File): Path =
        Paths.get(assetRelativePath).resolve(root.toPath().relativize(file.toPath()))

    private fun visibleChildren(directory: File): List<File> =
        directory.listFiles()?.filterNot { it.isHidden || it.name.startsWith('.') }.orEmpty()

    private fun requireDirectory(directory: File) {
        require(directory.isDirectory) { "Compose resource root is not a directory: ${directory.absolutePath}" }
    }

    private fun String.asUnderscoredIdentifier(): String = replace('-', '_').let {
        if (it.firstOrNull()?.isDigit() == true) "_$it" else it
    }

    private data class ByteLine(val text: String, val offset: Long, val size: Long)

    private companion object {
        val FILE_TYPES = mapOf(
            "drawable" to ComposeResourceType.DRAWABLE,
            "font" to ComposeResourceType.FONT,
        )
        val VALUE_TYPES = mapOf(
            "string" to ComposeResourceType.STRING,
            "string-array" to ComposeResourceType.STRING_ARRAY,
            "plurals" to ComposeResourceType.PLURAL_STRING,
        )
        val SUPPORTED_DIRECTORIES = setOf("values", "files", "drawable", "font")
    }
}
