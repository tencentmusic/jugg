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
}
