package com.sickworm.intellij.jugg.gradle.script

import groovy.json.JsonSlurper
import java.io.File

/** Native target sources and include roots discovered from structured native build metadata. */
class NativeBuildInputs(
    val sourceFiles: List<File>,
    val includeDirs: List<File>,
)

/**
 * Reads the native inputs CMake File API and AGP already generated instead of parsing CMake or GNU
 * Make sources. Candidates are filtered by the current build variant, so a stale reply of another
 * variant or ABI is ignored, and missing or unreadable metadata simply yields no inputs: the caller
 * then keeps the broad source roots instead of guessing.
 */
object NativeBuildMetadataReader {

    fun read(buildVariant: String, searchRoots: List<File>): NativeBuildInputs {
        val sourceFiles = linkedSetOf<File>()
        val includeDirs = linkedSetOf<File>()
        val isVariantMatched = { file: File ->
            buildVariant.isNotEmpty() && file.absolutePath.contains(buildVariant, ignoreCase = true)
        }
        searchRoots.forEach { root ->
            if (!root.isDirectory) return@forEach
            root.listNativeMetadata(maxDepth = MAX_SEARCH_DEPTH) { file ->
                file.isDirectory && file.name == "reply" && file.parentFile?.name == "v1" &&
                        file.parentFile?.parentFile?.name == "api" && isVariantMatched(file)
            }.forEach { replyDir ->
                readNativeCmakeReply(replyDir, sourceFiles, includeDirs)
            }
            root.listNativeMetadata(maxDepth = MAX_SEARCH_DEPTH) { file ->
                file.isFile && file.name == NDK_BUILD_MODEL_FILE_NAME && isVariantMatched(file)
            }.forEach { modelFile ->
                readNativeNdkBuildModel(modelFile, sourceFiles, includeDirs)
            }
        }
        return NativeBuildInputs(sourceFiles.toList(), includeDirs.toList())
    }

    /** Recursively collects the first matching entries; a matched directory is not searched further. */
    private fun File.listNativeMetadata(maxDepth: Int, predicate: (File) -> Boolean): List<File> {
        val result = mutableListOf<File>()
        fun visit(directory: File, depth: Int) {
            if (depth > maxDepth) return
            directory.listFiles()?.forEach { child ->
                if (predicate(child)) {
                    result.add(child)
                } else if (child.isDirectory) {
                    visit(child, depth + 1)
                }
            }
        }
        visit(this, 0)
        return result
    }

    private fun readNativeCmakeReply(replyDir: File, sourceFiles: MutableSet<File>, includeDirs: MutableSet<File>) {
        replyDir.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(CMAKE_CODEMODEL_FILE_PREFIX) && it.name.endsWith(".json") }
            .forEach { codemodelFile ->
                val configurations = parseNativeMetadataJson(codemodelFile)?.get("configurations") as? List<*> ?: return@forEach
                configurations.forEach { configuration ->
                    val targets = (configuration as? Map<*, *>)?.get("targets") as? List<*> ?: return@forEach
                    targets.forEach { target ->
                        val jsonFile = (target as? Map<*, *>)?.get("jsonFile") as? String ?: return@forEach
                        readNativeCmakeTarget(File(replyDir, jsonFile), sourceFiles, includeDirs)
                    }
                }
            }
    }

    private fun readNativeCmakeTarget(targetFile: File, sourceFiles: MutableSet<File>, includeDirs: MutableSet<File>) {
        val target = parseNativeMetadataJson(targetFile) ?: return
        (target["sources"] as? List<*>)?.forEach { source ->
            nativeMetadataFile((source as? Map<*, *>)?.get("path"))?.let { sourceFiles.add(it) }
        }
        (target["compileGroups"] as? List<*>)?.forEach { group ->
            val includes = (group as? Map<*, *>)?.get("includes") as? List<*> ?: return@forEach
            includes.forEach { include ->
                nativeMetadataFile((include as? Map<*, *>)?.get("path"))?.let { includeDirs.add(it) }
            }
        }
    }

    private fun readNativeNdkBuildModel(modelFile: File, sourceFiles: MutableSet<File>, includeDirs: MutableSet<File>) {
        val model = parseNativeMetadataJson(modelFile) ?: return
        (model["files"] as? List<*>)?.forEach { entry ->
            val entryMap = entry as? Map<*, *> ?: return@forEach
            nativeMetadataFile(entryMap["src"])?.let { sourceFiles.add(it) }
            (entryMap["flags"] as? List<*>)?.forEach { flag ->
                (flag as? String)?.takeIf { it.startsWith("-I") && it.length > 2 }
                    ?.let { nativeMetadataFile(it.substring(2))?.let { dir -> includeDirs.add(dir) } }
            }
        }
    }

    /** Reads an absolute path from native build metadata; relative or malformed values are ignored. */
    private fun nativeMetadataFile(value: Any?): File? {
        val path = value as? String ?: return null
        if (path.isEmpty()) return null
        val file = File(path)
        return if (file.isAbsolute) file else null
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseNativeMetadataJson(file: File): Map<String, Any?>? {
        if (!file.isFile) return null
        return try {
            file.inputStream().use { inputStream ->
                JsonSlurper().parse(inputStream) as? Map<String, Any?>
            }
        } catch (e: Throwable) {
            println("Jugg: read native build metadata $file failed: $e")
            null
        }
    }

    private const val NDK_BUILD_MODEL_FILE_NAME = "android_gradle_build.json"
    private const val CMAKE_CODEMODEL_FILE_PREFIX = "codemodel-v2"
    private const val MAX_SEARCH_DEPTH = 8
}
