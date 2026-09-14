package com.sickworm.intellij.jugg.project

import com.sickworm.intellij.jugg.project.data.ExternalBuildInfo
import com.sickworm.intellij.jugg.project.data.ExternalBuildInfoUpdate
import com.sickworm.intellij.jugg.project.data.ExternalBuildType
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExternalBuildInfoUpdaterTest {

    @Test
    fun `replaces only the requested external build record`() {
        val moduleRoot = File("/project/app")
        val flutter = ExternalBuildInfo(
            type = ExternalBuildType.Flutter,
            inputDirs = listOf(File(moduleRoot, "flutter")),
            taskPath = ":app:oldFlutterTask",
            assetsOutputDir = File(moduleRoot, "build/old-assets"),
            nativeOutput = File(moduleRoot, "build/old-native.jar"),
        )
        val cpp = ExternalBuildInfo(
            type = ExternalBuildType.Cpp,
            inputDirs = listOf(File(moduleRoot, "src/main/cpp")),
            taskPath = ":app:mergeDebugNativeLibs",
            assetsOutputDir = null,
            nativeOutput = File(moduleRoot, "build/native"),
        )
        val module = ModuleInfo.virtualModule.copy(
            name = "app",
            moduleRootDir = moduleRoot,
            buildVariant = "debug",
            namespace = "com.example.app",
            externalBuildInfos = listOf(flutter, cpp),
        )
        val unrelated = ModuleInfo.virtualModule.copy(name = "library")
        val refreshed = flutter.copy(
            inputDirs = listOf(File(moduleRoot, "flutter"), File("/project/shared_flutter")),
            taskPath = ":app:newFlutterTask",
            assetsOutputDir = File(moduleRoot, "build/new-assets"),
        )

        val result = mergeExternalBuildInfoUpdates(
            modules = mapOf(module.name to module, unrelated.name to unrelated),
            updates = listOf(ExternalBuildInfoUpdate(
                moduleName = module.name,
                moduleRootDir = module.moduleRootDir,
                buildVariant = module.buildVariant,
                previousTaskPath = flutter.taskPath!!,
                externalBuildInfo = refreshed,
            )),
        )

        assertNotNull(result)
        assertEquals(listOf(refreshed, cpp), result.getValue(module.name).externalBuildInfos)
        assertEquals(module.namespace, result.getValue(module.name).namespace)
        assertEquals(unrelated, result.getValue(unrelated.name))
    }

    @Test
    fun `rejects a patch whose previous task is no longer present`() {
        val module = ModuleInfo.virtualModule.copy(name = "app")

        val result = mergeExternalBuildInfoUpdates(
            modules = mapOf(module.name to module),
            updates = listOf(ExternalBuildInfoUpdate(
                moduleName = module.name,
                moduleRootDir = module.moduleRootDir,
                buildVariant = module.buildVariant,
                previousTaskPath = ":app:missingTask",
                externalBuildInfo = ExternalBuildInfo(
                    type = ExternalBuildType.Cpp,
                    inputDirs = listOf(module.moduleRootDir),
                    taskPath = ":app:newTask",
                    assetsOutputDir = null,
                    nativeOutput = File(module.moduleRootDir, "build/native"),
                ),
            )),
        )

        assertNull(result)
    }
}
