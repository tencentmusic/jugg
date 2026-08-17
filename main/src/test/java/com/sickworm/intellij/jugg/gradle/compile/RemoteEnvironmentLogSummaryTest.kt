package com.sickworm.intellij.jugg.gradle.compile

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteEnvironmentLogSummaryTest {

    @Test
    fun `summary exposes only approved environment values`() {
        val summary = summarizeRemoteEnvironmentVariables(
            "JAVA_HOME=/opt/jdk-17;ANDROID_HOME=/opt/android-sdk;ANDROID_SDK_ROOT=/opt/sdk-root;" +
                    "GRADLE_USER_HOME=/opt/gradle-home;PATH=/usr/local/private/bin;" +
                    "TOKEN=secret-token;CI=true",
        )

        assertTrue(summary.contains("JAVA_HOME=/opt/jdk-17"))
        assertTrue(summary.contains("ANDROID_HOME=/opt/android-sdk"))
        assertTrue(summary.contains("ANDROID_SDK_ROOT=/opt/sdk-root"))
        assertTrue(summary.contains("GRADLE_USER_HOME=/opt/gradle-home"))
        assertTrue(summary.contains("PATH=(configured)"))
        assertTrue(summary.contains("otherVariables=[CI, TOKEN]"))
        assertTrue(summary.contains("otherCount=2"))
        assertFalse(summary.contains("/usr/local/private/bin"))
        assertFalse(summary.contains("secret-token"))
        assertFalse(summary.contains("CI=true"))
    }
}
