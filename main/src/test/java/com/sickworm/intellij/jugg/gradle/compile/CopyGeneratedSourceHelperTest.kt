package com.sickworm.intellij.jugg.gradle.compile

import com.sickworm.intellij.jugg.project.info.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CopyGeneratedSourceHelperTest {

    @Test
    fun calculateSyncToLocalPaths_mapsCustomSyncFilesFromClasspathBackupToLocalBuildDir() {
        val localProjectDir = File("/local/JOOX_Android")
        val localModuleDir = File(localProjectDir, "module_libs/common/protocol")
        val classpathProjectDir = File("/local/JOOX_Android/build/jugg/classpath/root/JOOX_Android")
        val classpathModuleDir = File(classpathProjectDir, "module_libs/common/protocol")
        val customSyncPath = "build/outputs/jar/protocol-debug.jar"
        val customClasspath = "libs/compile-only.jar"
        val module = ModuleInfo.virtualModule.copy(
            name = "common.protocol",
            projectRootDir = localProjectDir,
            moduleRootDir = localModuleDir,
            buildPathInfo = ModuleBuildPathInfo(
                classpathProjectDir,
                classpathModuleDir,
                "debug",
                customClasspath = listOf(customClasspath),
                customSyncFilePath = listOf(customSyncPath),
                buildDirRelativePath = "build/module_libs/common/protocol",
            ),
        )

        val syncPairs = calculateSyncToLocalPaths(listOf(module))

        assertTrue(
            syncPairs.contains(
                File(classpathModuleDir, customSyncPath) to File(localModuleDir, customSyncPath)
            )
        )
        assertFalse(
            syncPairs.any { (source, target) ->
                source.path.endsWith(customClasspath) || target.path.endsWith(customClasspath)
            }
        )
        assertTrue(
            syncPairs.contains(
                File(classpathProjectDir, "build/module_libs/common/protocol/generated") to
                    File(localProjectDir, "build/module_libs/common/protocol/generated")
            )
        )
    }
}
