package com.sickworm.intellij.jugg.compiler.source.kotlin

import com.sickworm.intellij.jugg.mock.TestGlobal
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * L1 test for [KotlinCompilerHostCompat].
 *
 * Uses the real shaded JavaVersion class from kotlin-compiler-embeddable on the test classpath.
 * Old compilers (< 2.1.20) cap the accepted java feature version below 25, so running on a
 * JDK 25+ host crashes with IllegalArgumentException in JavaVersion.parse. The broken host is
 * simulated by an unparsable `java.version` system property.
 */
class KotlinCompilerHostCompatTest {

    private val javaVersionClass =
        Class.forName("org.jetbrains.kotlin.com.intellij.util.lang.JavaVersion")
    private val currentField = javaVersionClass.getDeclaredField("current")
        .apply { isAccessible = true }
    private lateinit var originalJavaVersion: String

    @Before
    fun setUp() {
        originalJavaVersion = System.getProperty("java.version")
        currentField.set(null, null)
    }

    @After
    fun tearDown() {
        System.setProperty("java.version", originalJavaVersion)
        currentField.set(null, null)
    }

    private fun invokeCurrentFeature(): Int {
        val current = javaVersionClass.getMethod("current").invoke(null)
        return javaVersionClass.getField("feature").getInt(current)
    }

    @Test
    fun `presets shaded JavaVersion when host java version is not parsable`() {
        // any feature version above the shaded parse cap reproduces the JDK 25 host crash
        System.setProperty("java.version", "99.0.1")
        val before = runCatching { invokeCurrentFeature() }
        assertTrue("shaded JavaVersion should reject feature 99", before.isFailure)

        KotlinCompilerHostCompat.ensureShadedJavaVersionSupported(
            javaClass.classLoader, TestGlobal.logger)

        assertEquals(Runtime.version().feature(), invokeCurrentFeature())
    }

    @Test
    fun `keeps shaded JavaVersion working when host java version is parsable`() {
        KotlinCompilerHostCompat.ensureShadedJavaVersionSupported(
            javaClass.classLoader, TestGlobal.logger)

        assertEquals(Runtime.version().feature(), invokeCurrentFeature())
    }

    @Test
    fun `shouldUseNoJdk requires jdk 25 host and android jar in dependencies`() {
        val withAndroidJar = listOf(
            "/sdk/platforms/android-33/android.jar",
            "/repo/some-lib-1.0.jar",
        )
        val withoutAndroidJar = listOf("/repo/some-lib-1.0.jar")

        assertTrue(KotlinCompilerHostCompat.shouldUseNoJdk(25, withAndroidJar))
        assertTrue(KotlinCompilerHostCompat.shouldUseNoJdk(26, withAndroidJar))
        assertFalse(KotlinCompilerHostCompat.shouldUseNoJdk(21, withAndroidJar))
        assertFalse(KotlinCompilerHostCompat.shouldUseNoJdk(25, withoutAndroidJar))
    }

    @Test
    fun `recognizes IDE file system close conflict`() {
        val message = """
            exception: java.lang.UnsupportedOperationException
            \tat java.base/sun.nio.fs.WindowsFileSystem.close(WindowsFileSystem.java:120)
            \tat com.intellij.platform.core.nio.fs.DelegatingFileSystem.close(DelegatingFileSystem.java:68)
            \tat org.jetbrains.kotlin.com.intellij.ide.plugins.DescriptorLoadingContext.close(DescriptorLoadingContext.kt:45)
        """.trimIndent()

        assertTrue(KotlinCompilerHostCompat.isIdeFileSystemCloseConflict(message))
    }

    @Test
    fun `does not treat unrelated unsupported operation as IDE file system conflict`() {
        val message = """
            exception: java.lang.UnsupportedOperationException
            \tat com.example.FileSystem.close(FileSystem.kt:10)
        """.trimIndent()

        assertFalse(KotlinCompilerHostCompat.isIdeFileSystemCloseConflict(message))
    }
}
