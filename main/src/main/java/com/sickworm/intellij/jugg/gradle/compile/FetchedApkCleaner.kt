package com.sickworm.intellij.jugg.gradle.compile

import java.io.File

/**
 * FetchedApkCleaner keeps the local APK cache aligned with the APK files found in the current Gradle fetch.
 */
object FetchedApkCleaner {

    fun clean(apkDir: File, fetchedFiles: Collection<File>) {
        val currentFiles = fetchedFiles.map { it.canonicalFile }.toSet()
        apkDir.listFiles()
            ?.filter { it.isFile && it.canonicalFile !in currentFiles }
            ?.forEach { it.delete() }
    }
}
