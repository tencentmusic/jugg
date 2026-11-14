package com.sickworm.intellij.jugg.project

import com.sickworm.intellij.jugg.mock.StdLogger
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Verifies classpath probing across archives and directories without ZIP directory entries. */
class FrameworkJarDetectorTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val logger = StdLogger("FrameworkJarDetectorTest")

    @Test fun `detects platform classes in archives without directory entries`() {
        val jar = temporaryFolder.newFile("framework.jar")
        ZipOutputStream(jar.outputStream()).use {
            it.putNextEntry(ZipEntry("android/net/wifi/WifiManager.class"))
            it.write(byteArrayOf(0))
            it.closeEntry()
        }
        assertTrue(FrameworkJarDetector.containsPlatformClasses(jar, logger))
    }

    @Test fun `detects directory classes but ignores resources and application classes`() {
        val root = temporaryFolder.newFolder()
        val resource = File(root, "android/net/wifi/data.txt")
        resource.parentFile.mkdirs()
        resource.writeText("resource")
        val appClass = File(root, "com/example/Activity.class")
        appClass.parentFile.mkdirs()
        appClass.writeBytes(byteArrayOf(0))
        assertFalse(FrameworkJarDetector.containsPlatformClasses(root, logger))
        File(resource.parentFile, "WifiManager.class").writeBytes(byteArrayOf(0))
        assertTrue(FrameworkJarDetector.containsPlatformClasses(root, logger))
    }

    @Test fun `unreadable archive does not prevent other candidates being inspected`() {
        val invalid = temporaryFolder.newFile("invalid.jar")
        invalid.writeText("invalid zip")
        assertFalse(FrameworkJarDetector.containsPlatformClasses(invalid, logger))
        assertFalse(FrameworkJarDetector.containsPlatformClasses(File(invalid.parentFile, "missing.jar"), logger))
    }
}
