package com.sickworm.intellij.jugg.gradle.compile

import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FetchedApkCleanerTest {

    @Test
    fun cleanDeletesFilesNotFetchedInCurrentRound() {
        val apkDir = Files.createTempDirectory("jugg-apk-cleaner").toFile()
        try {
            val currentApk = apkDir.resolve("app-debug.apk").apply { writeText("current") }
            val indexedCurrentApk = apkDir.resolve("1_app-debug-androidTest.apk").apply { writeText("test") }
            val staleApk = apkDir.resolve("old-app-debug.apk").apply { writeText("stale") }
            val staleMetadata = apkDir.resolve("old-app-debug.json").apply { writeText("stale") }

            FetchedApkCleaner.clean(apkDir, listOf(currentApk, indexedCurrentApk))

            assertTrue(currentApk.exists())
            assertTrue(indexedCurrentApk.exists())
            assertFalse(staleApk.exists())
            assertFalse(staleMetadata.exists())
        } finally {
            apkDir.deleteRecursively()
        }
    }
}
