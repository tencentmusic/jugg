package com.sickworm.intellij.jugg.compiler.source.apt

import java.io.File
import java.util.jar.JarFile

/** Reads JSR 269 service entries while leaving processor selection and failure policy to callers. */
object AnnotationProcessorClasspath {
    fun names(file: File): List<String> {
        if (!file.isFile || !file.extension.equals("jar", ignoreCase = true)) return emptyList()
        return JarFile(file).use { jar ->
            val entry = jar.getJarEntry("META-INF/services/javax.annotation.processing.Processor") ?: return@use emptyList()
            jar.getInputStream(entry).bufferedReader().use { reader ->
                reader.readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.distinct()
            }
        }
    }

    fun contains(file: File, entry: String): Boolean = when {
        file.isDirectory -> File(file, entry).isFile
        file.isFile && file.extension.equals("jar", ignoreCase = true) -> JarFile(file).use { it.getJarEntry(entry) != null }
        else -> false
    }
}
