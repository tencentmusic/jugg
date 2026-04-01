package com.sickworm.intellij.jugg.project.data

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModuleBuildPathInfoTest {

    @Test
    fun `usageFile should resolve to mapping usage output and be synced`() {
        val projectRootDir = File("/tmp/jugg-project")
        val moduleRootDir = File(projectRootDir, "app")
        val info = ModuleBuildPathInfo(projectRootDir, moduleRootDir, "release")

        assertEquals(
            File(moduleRootDir, "build/outputs/mapping/release/usage.txt").path,
            info.usageFile.path
        )
        assertTrue(
            info.allBuildPathRelative.any { it.path == File("build/outputs/mapping/release/usage.txt").path },
            "usage.txt should be included in synced build outputs"
        )
    }
}
