package com.sickworm.intellij.jugg.project

import com.sickworm.intellij.jugg.gradle.compile.GradleCompileResult
import com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.project.data.JuggProjectInfo
import com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResultSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import java.io.File

class ClasspathBackupHelperTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun fetch_preservesCustomSyncFilePathOnlyWhenWrappingClasspathRoot() {
        val projectDir = temporaryFolder.newFolder("JOOX_Android")
        val moduleDir = File(projectDir, "module_libs/common/protocol").also { it.mkdirs() }
        val classpathRootDir = temporaryFolder.newFolder("classpathRoot")
        val customSyncPath = "build/outputs/jar/protocol-debug.jar"
        val customClasspath = "libs/compile-only.jar"
        val module = ModuleInfo.virtualModule.copy(
            name = "common.protocol",
            projectRootDir = projectDir,
            moduleRootDir = moduleDir,
            buildPathInfo = ModuleBuildPathInfo(
                projectDir,
                moduleDir,
                "debug",
                customClasspath = listOf(customClasspath),
                customSyncFilePath = listOf(customSyncPath),
            ),
        )
        val compileClient = FakeGradleCompileClient(classpathRootDir)
        val helper = ClasspathBackupHelper(
            compileClient = compileClient,
            progressIndicator = null,
            coroutineScope = CoroutineScope(Dispatchers.Unconfined),
            logger = mock(),
        )

        val result = helper.fetch(JuggProjectInfo(mapOf(module.name to module)))!!

        val buildPathInfo = result.modules.getValue(module.name).buildPathInfo
        val backupModuleDir = File(classpathRootDir, "module_libs/common/protocol")
        assertEquals(classpathRootDir, buildPathInfo.projectRootDir)
        assertEquals(backupModuleDir, buildPathInfo.moduleRootDir)
        assertEquals(listOf(customSyncPath), buildPathInfo.customSyncFilePath)
        assertNull(buildPathInfo.customClasspath)
        assertTrue(
            buildPathInfo.syncToLocalPathList.contains(File(backupModuleDir, customSyncPath))
        )
        assertFalse(
            buildPathInfo.syncToLocalPathList.contains(File(backupModuleDir, customClasspath))
        )
    }

    private class FakeGradleCompileClient(
        private val classpathRootDir: File?,
    ) : IGradleCompileClient {

        override var terminalOutputListener: IGradleCompileClient.TerminalOutputListener =
            IGradleCompileClient.TerminalOutputListener.DEFAULT

        override fun login(juggGradleCompileOptions: JuggGradleCompileOptions) = Unit

        override fun compileAndFetchResult(isOnlyFetchResult: Boolean): GradleCompileResult {
            return GradleCompileResult.success(emptyList())
        }

        override fun fetchClasspathResult(buildDirs: List<ModuleBuildPathInfo>): File? {
            return classpathRootDir
        }

        override fun fetchLibraryChanges(incDeployTimes: Int): DependencyDiffResultSet? = null

        override fun cancelAction(isByUser: Boolean) = Unit

        override fun dispose() = Unit
    }
}
