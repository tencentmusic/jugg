package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.diagnostic.Logger
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GradleWrapperRepairerTest {

    @Test
    fun repairIfNeededSkipsWhenWrapperPropertiesMissing() {
        val projectDir = Files.createTempDirectory("jugg-wrapper-missing-props").toFile()
        try {
            val result = GradleWrapperRepairer(TEST_LOGGER).repairIfNeeded(
                projectDir,
                "./gradlew :app:assembleDebug",
                normalizeGradlewLineEndings = true,
            )

            assertEquals(GradleWrapperRepairResult.Skipped, result)
            assertFalse(projectDir.resolve("gradlew").exists())
            assertFalse(projectDir.resolve("gradlew.bat").exists())
            assertFalse(projectDir.resolve("gradle/wrapper/gradle-wrapper.jar").exists())
        } finally {
            projectDir.deleteRecursively()
        }
    }

    @Test
    fun repairIfNeededSkipsNonGradlewCommand() {
        val projectDir = Files.createTempDirectory("jugg-wrapper-non-gradlew").toFile()
        try {
            projectDir.resolve("gradle/wrapper").mkdirs()
            projectDir.resolve("gradle/wrapper/gradle-wrapper.properties").writeText(
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-7.3.3-bin.zip\n"
            )

            val result = GradleWrapperRepairer(TEST_LOGGER).repairIfNeeded(
                projectDir,
                "gradle :app:assembleDebug",
                normalizeGradlewLineEndings = false,
            )

            assertEquals(GradleWrapperRepairResult.Skipped, result)
            assertFalse(projectDir.resolve("gradlew").exists())
            assertFalse(projectDir.resolve("gradlew.bat").exists())
            assertFalse(projectDir.resolve("gradle/wrapper/gradle-wrapper.jar").exists())
        } finally {
            projectDir.deleteRecursively()
        }
    }

    @Test
    fun repairIfNeededFillsMissingWrapperFilesWhenPropertiesExist() {
        val projectDir = Files.createTempDirectory("jugg-wrapper-repair").toFile()
        try {
            projectDir.resolve("gradle/wrapper").mkdirs()
            projectDir.resolve("gradle/wrapper/gradle-wrapper.properties").writeText(
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-7.3.3-bin.zip\n"
            )

            val result = GradleWrapperRepairer(TEST_LOGGER).repairIfNeeded(
                projectDir,
                "./gradlew :app:assembleDebug",
                normalizeGradlewLineEndings = false,
            )

            assertEquals(GradleWrapperRepairResult.Repaired, result)
            assertTrue(projectDir.resolve("gradlew").exists())
            assertTrue(projectDir.resolve("gradlew").canExecute())
            assertTrue(projectDir.resolve("gradlew.bat").exists())
            assertTrue(projectDir.resolve("gradle/wrapper/gradle-wrapper.jar").exists())
        } finally {
            projectDir.deleteRecursively()
        }
    }

    @Test
    fun repairIfNeededDoesNotOverwriteExistingFiles() {
        val projectDir = Files.createTempDirectory("jugg-wrapper-preserve").toFile()
        try {
            projectDir.resolve("gradle/wrapper").mkdirs()
            projectDir.resolve("gradle/wrapper/gradle-wrapper.properties").writeText(
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-7.3.3-bin.zip\n"
            )
            val gradlew = projectDir.resolve("gradlew").apply { writeText("custom gradlew") }
            val gradlewBat = projectDir.resolve("gradlew.bat").apply { writeText("custom gradlew.bat") }
            val wrapperJar = projectDir.resolve("gradle/wrapper/gradle-wrapper.jar").apply { writeBytes(byteArrayOf(1, 2, 3)) }

            val result = GradleWrapperRepairer(TEST_LOGGER).repairIfNeeded(
                projectDir,
                "./gradlew :app:assembleDebug",
                normalizeGradlewLineEndings = false,
            )

            assertEquals(GradleWrapperRepairResult.Skipped, result)
            assertEquals("custom gradlew", gradlew.readText())
            assertEquals("custom gradlew.bat", gradlewBat.readText())
            assertEquals(listOf<Byte>(1, 2, 3), wrapperJar.readBytes().toList())
        } finally {
            projectDir.deleteRecursively()
        }
    }

    @Test
    fun repairIfNeededSupportsSubdirectoryGradlew() {
        val projectDir = Files.createTempDirectory("jugg-wrapper-subdir").toFile()
        try {
            val androidDir = projectDir.resolve("android")
            androidDir.resolve("gradle/wrapper").mkdirs()
            androidDir.resolve("gradle/wrapper/gradle-wrapper.properties").writeText(
                "distributionUrl=https\\://services.gradle.org/distributions/gradle-7.3.3-bin.zip\n"
            )

            val result = GradleWrapperRepairer(TEST_LOGGER).repairIfNeeded(
                projectDir,
                "android/gradlew :app:assembleDebug",
                normalizeGradlewLineEndings = false,
            )

            assertEquals(GradleWrapperRepairResult.Repaired, result)
            assertTrue(androidDir.resolve("gradlew").exists())
            assertTrue(androidDir.resolve("gradlew.bat").exists())
            assertTrue(androidDir.resolve("gradle/wrapper/gradle-wrapper.jar").exists())
        } finally {
            projectDir.deleteRecursively()
        }
    }

    @Test
    fun repairIfNeededNormalizesGradlewLineEndings() {
        val projectDir = createCompleteWrapper("#!/usr/bin/env sh\r\necho first\r\nprintf 'keep\rvalue'\n")
        try {
            val result = GradleWrapperRepairer(TEST_LOGGER).repairIfNeeded(
                projectDir,
                "./gradlew :app:assembleDebug",
                normalizeGradlewLineEndings = true,
            )

            assertEquals(GradleWrapperRepairResult.Repaired, result)
            assertEquals(
                "#!/usr/bin/env sh\necho first\nprintf 'keep\rvalue'\n",
                projectDir.resolve("gradlew").readText(),
            )
        } finally {
            projectDir.deleteRecursively()
        }
    }

    @Test
    fun repairIfNeededSkipsGradlewLineEndingNormalizationWhenDisabled() {
        val content = "#!/usr/bin/env sh\r\necho first\r\n"
        val projectDir = createCompleteWrapper(content)
        try {
            val result = GradleWrapperRepairer(TEST_LOGGER).repairIfNeeded(
                projectDir,
                "./gradlew :app:assembleDebug",
                normalizeGradlewLineEndings = false,
            )

            assertEquals(GradleWrapperRepairResult.Skipped, result)
            assertEquals(content, projectDir.resolve("gradlew").readText())
        } finally {
            projectDir.deleteRecursively()
        }
    }

    @Test
    fun repairIfNeededSkipsGradlewLineEndingNormalizationForLfFile() {
        val content = "#!/usr/bin/env sh\necho first\n"
        val projectDir = createCompleteWrapper(content)
        try {
            val result = GradleWrapperRepairer(TEST_LOGGER).repairIfNeeded(
                projectDir,
                "./gradlew :app:assembleDebug",
                normalizeGradlewLineEndings = true,
            )

            assertEquals(GradleWrapperRepairResult.Skipped, result)
            assertEquals(content, projectDir.resolve("gradlew").readText())
        } finally {
            projectDir.deleteRecursively()
        }
    }

    @Test
    fun repairIfNeededNormalizesSubdirectoryGradlewLineEndings() {
        val projectDir = Files.createTempDirectory("jugg-wrapper-subdir-crlf").toFile()
        try {
            val androidDir = createCompleteWrapper("#!/usr/bin/env sh\r\necho first\r\n", projectDir.resolve("android"))

            val result = GradleWrapperRepairer(TEST_LOGGER).repairIfNeeded(
                projectDir,
                "android/gradlew :app:assembleDebug",
                normalizeGradlewLineEndings = true,
            )

            assertEquals(GradleWrapperRepairResult.Repaired, result)
            assertEquals("#!/usr/bin/env sh\necho first\n", androidDir.resolve("gradlew").readText())
        } finally {
            projectDir.deleteRecursively()
        }
    }

    @Test
    fun repairIfNeededDoesNotNormalizeGradlewForBatCommand() {
        val content = "#!/usr/bin/env sh\r\necho first\r\n"
        val projectDir = createCompleteWrapper(content)
        try {
            val result = GradleWrapperRepairer(TEST_LOGGER).repairIfNeeded(
                projectDir,
                "gradlew.bat :app:assembleDebug",
                normalizeGradlewLineEndings = true,
            )

            assertEquals(GradleWrapperRepairResult.Skipped, result)
            assertEquals(content, projectDir.resolve("gradlew").readText())
        } finally {
            projectDir.deleteRecursively()
        }
    }

    private fun createCompleteWrapper(
        gradlewContent: String,
        projectDir: File = Files.createTempDirectory("jugg-wrapper-line-ending").toFile(),
    ): File {
        projectDir.resolve("gradle/wrapper").mkdirs()
        projectDir.resolve("gradle/wrapper/gradle-wrapper.properties").writeText(
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-7.3.3-bin.zip\n"
        )
        projectDir.resolve("gradle/wrapper/gradle-wrapper.jar").writeBytes(byteArrayOf(1, 2, 3))
        projectDir.resolve("gradlew").writeText(gradlewContent)
        projectDir.resolve("gradlew.bat").writeText("@echo off\r\n")
        return projectDir
    }

    private companion object {
        val TEST_LOGGER: Logger = Logger.getInstance(GradleWrapperRepairerTest::class.java)
    }
}
