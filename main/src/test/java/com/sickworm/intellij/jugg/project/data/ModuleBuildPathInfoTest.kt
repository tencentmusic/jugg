package com.sickworm.intellij.jugg.project.data

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModuleBuildPathInfoTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

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

    /**
     * AGP 9.0+ renamed compile_and_runtime_not_namespaced_r_class_jar to compile_and_runtime_r_class_jar.
     * Jugg should resolve R.jar correctly for the new namespaced path.
     */
    @Test
    fun `rFilePath resolves AGP 9 compile_and_runtime_r_class_jar path`() {
        val moduleRootDir = tempFolder.newFolder("app")
        val info = ModuleBuildPathInfo(moduleRootDir.parentFile, moduleRootDir, "debug")

        // AGP 9.0+: compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar
        val agp9RJar = File(
            moduleRootDir,
            "build/intermediates/compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar"
        )
        agp9RJar.parentFile.mkdirs()
        agp9RJar.createNewFile()

        assertEquals(agp9RJar.canonicalPath, info.rFilePath.canonicalPath,
            "rFilePath should resolve to AGP 9+ compile_and_runtime_r_class_jar path")
    }

    @Test
    fun `rFilePath resolves AGP 8 compile_and_runtime_not_namespaced_r_class_jar path`() {
        val moduleRootDir = tempFolder.newFolder("app")
        val info = ModuleBuildPathInfo(moduleRootDir.parentFile, moduleRootDir, "debug")

        // AGP 8.x: compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar
        val agp8RJar = File(
            moduleRootDir,
            "build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar"
        )
        agp8RJar.parentFile.mkdirs()
        agp8RJar.createNewFile()

        assertEquals(agp8RJar.canonicalPath, info.rFilePath.canonicalPath,
            "rFilePath should resolve to AGP 8 compile_and_runtime_not_namespaced_r_class_jar path")
    }
}
