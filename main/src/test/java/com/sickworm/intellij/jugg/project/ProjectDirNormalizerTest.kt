package com.sickworm.intellij.jugg.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * L1 tests for MCP/CLI projectDir canonicalization across native Windows and POSIX-style paths.
 */
class ProjectDirNormalizerTest {

    @Test
    fun convertPosixStyleWindowsPath_msysDrive_mapsToDriveLetterPath() {
        assertEquals(
            "D:/GitHub/jugg/android_demo_project",
            ProjectDirNormalizer.convertPosixStyleWindowsPath(
                "/d/GitHub/jugg/android_demo_project",
                msysDriveEnabled = true,
            ),
        )
    }

    @Test
    fun convertPosixStyleWindowsPath_wslMount_mapsToDriveLetterPath() {
        assertEquals(
            "D:/GitHub/jugg/android_demo_project",
            ProjectDirNormalizer.convertPosixStyleWindowsPath("/mnt/d/GitHub/jugg/android_demo_project"),
        )
    }

    @Test
    fun convertPosixStyleWindowsPath_cygwinDrive_mapsToDriveLetterPath() {
        assertEquals(
            "D:/GitHub/jugg/android_demo_project",
            ProjectDirNormalizer.convertPosixStyleWindowsPath("/cygdrive/d/GitHub/jugg/android_demo_project"),
        )
    }

    @Test
    fun convertPosixStyleWindowsPath_unixPath_isUnchanged() {
        assertEquals(
            "/tmp/projectA",
            ProjectDirNormalizer.convertPosixStyleWindowsPath("/tmp/projectA", msysDriveEnabled = true),
        )
    }

    @Test
    fun convertPosixStyleWindowsPath_msysDisabled_skipsSingleLetterDrive() {
        assertEquals(
            "/d/GitHub/jugg",
            ProjectDirNormalizer.convertPosixStyleWindowsPath(
                "/d/GitHub/jugg",
                msysDriveEnabled = false,
            ),
        )
    }

    @Test
    fun normalizeProjectDir_replacesBackslashes() {
        val dir = File(System.getProperty("java.io.tmpdir"), "jugg-project-dir-normalize").absoluteFile
        dir.mkdirs()
        val input = dir.path.replace('/', '\\')
        assertEquals(
            ProjectDirNormalizer.normalizeProjectDir(dir.absolutePath),
            ProjectDirNormalizer.normalizeProjectDir(input),
        )
    }

    @Test
    fun projectDirEquals_ignoresSeparatorAndDriveLetterCaseOnWindowsPaths() {
        if (!System.getProperty("os.name").lowercase().contains("win")) {
            return
        }
        assertTrue(
            ProjectDirNormalizer.projectDirEquals(
                "D:\\GitHub\\jugg\\android_demo_project",
                "d:/github/jugg/android_demo_project",
            ),
        )
    }

    @Test
    fun projectDirEquals_msysPath_matchesNativeWindowsPath() {
        if (!System.getProperty("os.name").lowercase().contains("win")) {
            return
        }
        val msysPath = ProjectDirNormalizer.convertPosixStyleWindowsPath(
            "/d/GitHub/jugg/android_demo_project",
            msysDriveEnabled = true,
        )
        assertTrue(
            ProjectDirNormalizer.projectDirEquals(
                msysPath,
                "D:/GitHub/jugg/android_demo_project",
            ),
        )
    }
}
