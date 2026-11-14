package com.sickworm.intellij.jugg.compiler.source.apt

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Publishes complete generated source directories; hashing is used only by the processor JAR cache. */
class GeneratedSourceCheckpoint(val folder: File) {
    val sourceRoot = File(folder, "sources")
    /** Replaces the complete source directory after a successful aggregate generation. */
    fun publish(generated: File) {
        val pendingSources = File(folder, "sources.pending")
        check(!pendingSources.exists() || pendingSources.deleteRecursively()) { "Cannot clear $pendingSources" }
        generated.copyRecursively(pendingSources, overwrite = true)
        check(!sourceRoot.exists() || sourceRoot.deleteRecursively()) { "Cannot replace $sourceRoot" }
        Files.move(pendingSources.toPath(), sourceRoot.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    companion object {
        /** Hashes canonical paths and bytes, including same-size edits to local processor JARs. */
        fun digest(files: List<File>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            files.distinctBy { it.canonicalPath }.sortedBy { it.canonicalPath }.forEach { file ->
                digest.update(file.canonicalPath.toByteArray())
                file.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    var length = input.read(buffer)
                    while (length >= 0) {
                        digest.update(buffer, 0, length)
                        length = input.read(buffer)
                    }
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
