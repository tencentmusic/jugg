package com.sickworm.intellij.jugg.project

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.custom.CustomCompilerManager
import com.sickworm.intellij.jugg.deploy.DeployFileManager
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.mock.TestGlobal
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import java.io.File

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

    private fun createContext(deployFileManager: DeployFileManager): BaseCompileContext {
        val baseContext = TestGlobal.context
        return BaseCompileContext(
            logger = TestGlobal.logger,
            tempCompileDir = baseContext.tempCompileDir,
            tempModuleDir = baseContext.tempModuleDir,
            androidHome = baseContext.androidHome,
            modules = baseContext.modules,
            apkInfos = baseContext.apkInfos,
            projectDir = baseContext.projectDir,
            incrementalDataDir = baseContext.incrementalDataDir,
            cmdCompileEnv = emptyList(),
            scene = ICompileContext.Scene.IDE,
            deployFileManager = deployFileManager,
            deployHistoryManager = mock<IDeployHistoryManager>(),
            customCompilerManager = mock<CustomCompilerManager>(),
        )
    }
}
