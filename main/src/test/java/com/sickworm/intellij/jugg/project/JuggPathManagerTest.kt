package com.sickworm.intellij.jugg.project

import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals

class JuggPathManagerTest {

    @Test
    fun initGradleFiles_shouldUseStableDirectoryUnderDotGradle() {
        val projectDir = Files.createTempDirectory("jugg_path_manager").toFile()
        try {
            val pathManager = JuggPathManager(projectDir)

            assertEquals(File(projectDir, "build/jugg").absolutePath, pathManager.juggRootDir.absolutePath)
            assertEquals(File(projectDir, "build/jugg/config").absolutePath, pathManager.configDir.absolutePath)
            assertEquals(
                File(JuggGlobalPathManager.rootDir, "const_ref/const_ref_shared.db").absolutePath,
                pathManager.constRefSharedDbFile.absolutePath,
            )
            assertEquals(
                File(projectDir, ".gradle/jugg/readProjectInfo.gradle.kts").absolutePath,
                pathManager.initGradleFilePath.absolutePath,
            )
            assertEquals(
                File(projectDir, ".gradle/jugg/jugg-runtime.jar").absolutePath,
                pathManager.runtimeJarFilePath.absolutePath,
            )
            // TODO: initGradleFileRelativePath field was removed; re-enable once re-added
            // assertEquals(
            //     File(projectDir, ".gradle/jugg/readProjectInfo.gradle.kts").relativeTo(projectDir).path,
            //     pathManager.initGradleFileRelativePath,
            // )
        } finally {
            projectDir.deleteRecursively()
        }
    }

    @Test
    fun libraryTestBuildRecordDir_shouldUseGlobalJuggRootDir() {
        val projectDir = Files.createTempDirectory("jugg_path_manager").toFile()
        val globalJuggRootDir = Files.createTempDirectory("jugg_global_path_manager").toFile()
        try {
            val pathManager = JuggPathManager(projectDir, globalJuggRootDir = globalJuggRootDir)

            assertEquals(
                File(globalJuggRootDir, "library_test_build_records").absolutePath,
                pathManager.libraryTestBuildRecordDir.absolutePath,
            )
        } finally {
            projectDir.deleteRecursively()
            globalJuggRootDir.deleteRecursively()
        }
    }
}
