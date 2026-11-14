package com.sickworm.intellij.jugg.project

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** Finds compile-only platform stubs without loading classes or depending on compiler APIs. */
internal object FrameworkJarDetector {
    // First package segments collected from Android SDK 37.2 android.jar.
    private val platformPackages = setOf("android", "androidx", "dalvik", "java", "javax", "org")

    fun containsPlatformClasses(file: File, logger: Logger): Boolean {
        if (!file.exists()) return false
        return try {
            if (file.isDirectory) {
                platformPackages.any { prefix ->
                    File(file, prefix).walkTopDown().any { it.isFile && it.extension == "class" }
                }
            } else {
                ZipFile(file).use { zip ->
                    zip.entries().asSequence().any { entry ->
                        !entry.isDirectory && entry.name.endsWith(".class") &&
                                entry.name.substringBefore('/') in platformPackages
                    }
                }
            }
        } catch (e: IOException) {
            logger.warn("Cannot inspect compile-only framework dependency: $file", e)
            false
        }
    }
}
