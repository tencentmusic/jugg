package com.sickworm.intellij.jugg.compiler.context

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.custom.CustomCompilerManager
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.project.change.ChangedFile
import com.sickworm.intellij.jugg.project.IExternalBuildInfoUpdater
import com.sickworm.intellij.jugg.project.info.ExternalBuildInfo
import com.sickworm.intellij.jugg.project.info.ExternalBuildInfoUpdate
import com.sickworm.intellij.jugg.project.info.ExternalBuildType
import com.sickworm.intellij.jugg.project.info.ModuleInfo
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Verifies BaseCompileContext delegates changed-file tracking APIs to DeployFileManager.
 */
class BaseCompileContextChangedFileBridgeTest {

    @Test
    fun addChangedFile_shouldDelegateToDeployFileManager() {
        val deployFileManager = mock<DeployFileManager>()
        val context = createContext(deployFileManager)
        val changedFile = ChangedFile(
            type = CompileFile.Type.Kotlin,
            file = File(TestGlobal.assetsAndroidDir, "app/src/main/java/com/example/myapplication/MainActivity.kt"),
            baseDir = File(TestGlobal.assetsAndroidDir, "app/src/main/java"),
            module = TestGlobal.applicationModule,
        )

        context.addChangedFile(listOf(changedFile))

        verify(deployFileManager).addChangedFile(listOf(changedFile))
    }

    @Test
    fun removeChangedFile_shouldDelegateToDeployFileManager() {
        val deployFileManager = mock<DeployFileManager>()
        val context = createContext(deployFileManager)
        val removedFile = File(TestGlobal.assetsAndroidDir, "app/src/main/java/com/example/myapplication/MainActivity.kt")

        context.removeChangedFile(listOf(removedFile))

        verify(deployFileManager).removeChangedFile(listOf(removedFile))
    }

    @Test
    fun updateExternalBuildInfos_shouldNotFallbackToMemoryWhenPersistenceFails() {
        val module = TestGlobal.applicationModule.copy(externalBuildInfos = listOf(ExternalBuildInfo(
            type = ExternalBuildType.Cpp,
            inputDirs = listOf(TestGlobal.applicationModule.moduleRootDir),
            taskPath = ":app:oldNativeTask",
            assetsOutputDir = null,
            nativeOutput = File(TestGlobal.applicationModule.moduleRootDir, "build/old-native"),
        )))
        val context = createContext(
            deployFileManager = mock(),
            modules = mapOf(module.name to module),
            externalBuildInfoUpdater = object : IExternalBuildInfoUpdater {
                override fun update(updates: List<ExternalBuildInfoUpdate>): Map<String, ModuleInfo>? = null
            },
        )
        val update = ExternalBuildInfoUpdate(
            moduleName = module.name,
            moduleRootDir = module.moduleRootDir,
            buildVariant = module.buildVariant,
            previousTaskPath = ":app:oldNativeTask",
            externalBuildInfo = module.externalBuildInfos.single().copy(taskPath = ":app:newNativeTask"),
        )

        assertFalse(context.updateExternalBuildInfos(listOf(update)))
        assertEquals(":app:oldNativeTask", context.modules.getValue(module.name).externalBuildInfos.single().taskPath)
    }

    private fun createContext(
        deployFileManager: DeployFileManager,
        modules: Map<String, ModuleInfo> = TestGlobal.context.modules,
        externalBuildInfoUpdater: IExternalBuildInfoUpdater? = null,
    ): BaseCompileContext {
        val baseContext = TestGlobal.context
        return BaseCompileContext(
            logger = TestGlobal.logger,
            tempCompileDir = baseContext.tempCompileDir,
            tempModuleDir = baseContext.tempModuleDir,
            androidHome = baseContext.androidHome,
            modules = modules,
            apkInfos = baseContext.apkInfos,
            projectDir = baseContext.projectDir,
            incrementalDataDir = baseContext.incrementalDataDir,
            cmdCompileEnv = emptyList(),
            scene = ICompileContext.Scene.IDE,
            deployFileManager = deployFileManager,
            deployHistoryManager = mock<IDeployHistoryManager>(),
            customCompilerManager = mock<CustomCompilerManager>(),
            externalBuildInfoUpdater = externalBuildInfoUpdater,
        )
    }
}
