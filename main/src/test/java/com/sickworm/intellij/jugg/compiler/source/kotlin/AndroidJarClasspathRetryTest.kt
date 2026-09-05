package com.sickworm.intellij.jugg.compiler.source.kotlin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L1 test for [AndroidJarClasspathRetry].
 *
 * Diagnostic samples are real compiler output: the simple-name rendering comes from Kotlin 2.2.10
 * and 2.3.0, the fully qualified rendering comes from Jugg report 06f3d8fa on an older compiler.
 */
class AndroidJarClasspathRetryTest {

    @Test
    fun `matches simple name supertype diagnostic of kotlin 2_2 and above`() {
        val messages = listOf(
            "UseK.kt:1:1: error: cannot access 'Inner' which is a supertype of 'UseK'. " +
                    "Check your module classpath for missing or conflicting dependencies.",
            "UseK.kt:2:5: error: 'onStateChanged' overrides nothing.",
        )

        assertTrue(AndroidJarClasspathRetry.isShadowedSupertypeDiagnostic(messages))
    }

    @Test
    fun `matches fully qualified supertype diagnostic of older kotlin`() {
        val messages = listOf(
            "MainPage.kt:10:1: error: cannot access 'android.net.wifi.WifiManager.SoftApCallback' " +
                    "which is a supertype of MegaWifiManager.SoftApCallback",
        )

        assertTrue(AndroidJarClasspathRetry.isShadowedSupertypeDiagnostic(messages))
    }

    @Test
    fun `matches unresolved supertypes diagnostic`() {
        val messages = listOf(
            "MainPage.kt:10:1: error: unresolved supertypes: android.net.wifi.WifiManager.SoftApCallback",
        )

        assertTrue(AndroidJarClasspathRetry.isShadowedSupertypeDiagnostic(messages))
    }

    @Test
    fun `ignores unresolved reference and symbol not found diagnostics`() {
        assertFalse(AndroidJarClasspathRetry.isShadowedSupertypeDiagnostic(
            listOf("MainPage.kt:10:1: error: unresolved reference: android.view.View")))
        assertFalse(AndroidJarClasspathRetry.isShadowedSupertypeDiagnostic(
            listOf("compiler.err.cant.resolve.location MainPage.java:10: error: cannot find symbol")))
        assertFalse(AndroidJarClasspathRetry.isShadowedSupertypeDiagnostic(emptyList()))
    }

    @Test
    fun `moves the first android jar to the end and keeps other entries in order`() {
        val classpath = listOf(
            "/sdk/platforms/android-33/android.jar",
            "/repo/a.jar",
            "/repo/mega.nexus.framework.source.jar",
            "/repo/b.jar",
        )

        assertEquals(
            listOf(
                "/repo/a.jar",
                "/repo/mega.nexus.framework.source.jar",
                "/repo/b.jar",
                "/sdk/platforms/android-33/android.jar",
            ),
            AndroidJarClasspathRetry.postponeSdkAndroidJar(classpath),
        )
    }

    @Test
    fun `keeps classpath unchanged when android jar is already last or absent`() {
        val alreadyLast = listOf("/repo/a.jar", "/sdk/platforms/android-33/android.jar")
        val absent = listOf("/repo/a.jar", "/repo/b.jar")

        assertEquals(alreadyLast, AndroidJarClasspathRetry.postponeSdkAndroidJar(alreadyLast))
        assertEquals(absent, AndroidJarClasspathRetry.postponeSdkAndroidJar(absent))
    }
}
