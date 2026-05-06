package com.sickworm.intellij.jugg.gradle.compile

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidTestCommandDeriverTest {

    // ---- compileCommand derivation ----

    @Test
    fun `compile command is no longer modified because init script injects androidTest task`() {
        val command = "./gradlew :app:customDebugTask"
        val result = AndroidTestCommandDeriver.deriveCompileCommand(command)
        assertEquals(command, result)
    }

    // ---- APK lookup derivation ----

    @Test
    fun `compile client derives androidTest apk pattern from resolved app apk path`() {
        val result = LocalGradleCompileClient.deriveAndroidTestApkPattern("app/build/outputs/apk/debug/app-debug.apk")
        assertEquals("app/build/outputs/apk/androidTest/debug/*.apk", result)
    }

    @Test
    fun `compile client derives flavor androidTest apk pattern from resolved app apk path`() {
        val result = LocalGradleCompileClient.deriveAndroidTestApkPattern("app/build/outputs/apk/development/debug/app-development-debug.apk")
        assertEquals("app/build/outputs/apk/androidTest/development/debug/*.apk", result)
    }

    @Test
    fun `compile client ignores already androidTest apk path`() {
        val result = LocalGradleCompileClient.deriveAndroidTestApkPattern("app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
        assertEquals(null, result)
    }
}
