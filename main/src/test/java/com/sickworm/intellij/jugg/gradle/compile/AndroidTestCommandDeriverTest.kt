package com.sickworm.intellij.jugg.gradle.compile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidTestCommandDeriverTest {

    // ---- compileCommand derivation ----

    @Test
    fun `basic assemble command gets androidTest task appended`() {
        val result = AndroidTestCommandDeriver.deriveCompileCommand("./gradlew :app:assembleDebug")
        assertEquals("./gradlew :app:assembleDebug :app:assembleDebugAndroidTest", result)
    }

    @Test
    fun `assemble command with flavor gets correct androidTest task`() {
        val result = AndroidTestCommandDeriver.deriveCompileCommand("./gradlew :app:assembleDevelopmentDebug")
        assertEquals("./gradlew :app:assembleDevelopmentDebug :app:assembleDevelopmentDebugAndroidTest", result)
    }

    @Test
    fun `idempotent - command already containing AndroidTest is not modified`() {
        val cmd = "./gradlew :app:assembleDebug :app:assembleDebugAndroidTest"
        val result = AndroidTestCommandDeriver.deriveCompileCommand(cmd)
        assertEquals(cmd, result)
    }

    @Test
    fun `bundle command falls back to appending assembleDebugAndroidTest with warning`() {
        val result = AndroidTestCommandDeriver.deriveCompileCommand("./gradlew :app:bundleDebug")
        assertTrue("Should contain original bundle task", result.contains(":app:bundleDebug"))
        assertTrue("Should append androidTest fallback", result.contains("AndroidTest"))
    }

    @Test
    fun `multi-module assemble command derives test task for first assemble module`() {
        val result = AndroidTestCommandDeriver.deriveCompileCommand("./gradlew :feature:assembleDebug :app:assembleDebug")
        assertTrue("Should contain original tasks", result.contains(":feature:assembleDebug"))
        assertTrue("Should append androidTest task", result.contains("AndroidTest"))
    }

    // ---- outputApkName derivation ----

    @Test
    fun `standard apk output path gets androidTest path appended`() {
        val result = AndroidTestCommandDeriver.deriveOutputApkName("app/build/outputs/apk/debug/*.apk")
        assertTrue("Should contain original glob", result.contains("app/build/outputs/apk/debug/*.apk"))
        assertTrue("Should append androidTest glob", result.contains("app/build/outputs/apk/androidTest/debug/*.apk"))
    }

    @Test
    fun `flavor output path gets correct androidTest path`() {
        val result = AndroidTestCommandDeriver.deriveOutputApkName("app/build/outputs/apk/development/debug/*.apk")
        assertTrue(result.contains("app/build/outputs/apk/development/debug/*.apk"))
        assertTrue(result.contains("app/build/outputs/apk/androidTest/development/debug/*.apk"))
    }

    @Test
    fun `multiple output paths separated by semicolon are each derived`() {
        val input = "app/build/outputs/apk/debug/*.apk;feature/build/outputs/apk/debug/*.apk"
        val result = AndroidTestCommandDeriver.deriveOutputApkName(input)
        assertTrue(result.contains("app/build/outputs/apk/androidTest/debug/*.apk"))
        assertTrue(result.contains("feature/build/outputs/apk/androidTest/debug/*.apk"))
    }

    @Test
    fun `idempotent - output name already containing androidTest path is not doubled`() {
        val input = "app/build/outputs/apk/debug/*.apk;app/build/outputs/apk/androidTest/debug/*.apk"
        val result = AndroidTestCommandDeriver.deriveOutputApkName(input)
        val count = result.split(";").count { it.contains("androidTest") }
        assertEquals("Should not duplicate androidTest path", 1, count)
    }
}
