package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.diagnostic.Logger
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GradleWrapperRepairerTest {

    @Test
    fun repairIfNeededSkipsWhenWrapperPropertiesMissing() {
        val projectDir = Files.createTempDirectory("jugg-wrapper-missing-props").toFile()
        try {
            val result = GradleWrapperRepairer(TEST_LOGGER).repairIfNeeded(projectDir, "./gradlew :app:assembleDebug")

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

            val result = GradleWrapperRepairer(TEST_LOGGER).repairIfNeeded(projectDir, "gradle :app:assembleDebug")

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

            val result = GradleWrapperRepairer(TEST_LOGGER).repairIfNeeded(projectDir, "./gradlew :app:assembleDebug")

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

            val result = GradleWrapperRepairer(TEST_LOGGER).repairIfNeeded(projectDir, "./gradlew :app:assembleDebug")

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

            val result = GradleWrapperRepairer(TEST_LOGGER).repairIfNeeded(projectDir, "android/gradlew :app:assembleDebug")

            assertEquals(GradleWrapperRepairResult.Repaired, result)
            assertTrue(androidDir.resolve("gradlew").exists())
            assertTrue(androidDir.resolve("gradlew.bat").exists())
            assertTrue(androidDir.resolve("gradle/wrapper/gradle-wrapper.jar").exists())
        } finally {
            projectDir.deleteRecursively()
        }
    }

    private companion object {
        val TEST_LOGGER: Logger = Logger.getInstance(GradleWrapperRepairerTest::class.java)
    }
}
