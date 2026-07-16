package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.bean.SyncMode
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.project.change.ChangedFile
import com.sickworm.intellij.jugg.project.change.IFileChangesHandler
import com.sickworm.intellij.jugg.project.runtime.JuggPathManager
import com.sickworm.intellij.jugg.project.runtime.LocalClasspathStoragePathManager
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeployHistoryManagerFullBuildInfoTest {

    private lateinit var tempDir: File
    private lateinit var pathManager: JuggPathManager
    private lateinit var historyManager: DeployHistoryManager

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("jugg_deploy_full_build_info").toFile()
        pathManager = JuggPathManager(tempDir)
        historyManager = DeployHistoryManager(pathManager, NoopFileChangesHandler, logger)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `get full build info returns null before full build info is saved`() {
        assertNull(historyManager.getFullBuildInfo())
        assertFalse(historyManager.isBuildTargetChanged(makeOptions(BuildTarget.APP)))
    }

    @Test
    fun `is build target changed compares with saved full build info`() {
        val fullBuildInfo = FullBuildInfo(
            compileCommand = "./gradlew :app:assembleDebug",
            buildTarget = BuildTarget.APP,
            createdAt = 1234L,
        )
        val compileContextDb = CompileContextDb(pathManager.juggRootDir, pathManager.compileContextDbDir, logger)
        compileContextDb.saveFullBuildInfo(fullBuildInfo)

        assertEquals(fullBuildInfo, historyManager.getFullBuildInfo())
        assertFalse(historyManager.isBuildTargetChanged(makeOptions(BuildTarget.APP)))
        assertTrue(historyManager.isBuildTargetChanged(makeOptions(BuildTarget.ANDROID_TEST)))
    }

    private fun makeOptions(buildTarget: BuildTarget): JuggGradleCompileOptions {
        return JuggGradleCompileOptions(
            projectRootPath = tempDir.absolutePath,
            localClasspathStoragePath = LocalClasspathStoragePathManager(File(tempDir, "classpath")),
            initGradleFilePath = File(tempDir, "readProjectInfo.gradle.kts").absolutePath,
            compileCommand = "./gradlew :app:assembleDebug",
            outputApkName = "app/build/outputs/apk/debug/*.apk",
            isRemoteCompile = false,
            isSyncAllProjects = false,
            remoteSshUser = "",
            remoteSshPassword = "",
            remoteSshIp = "",
            remoteSshPort = 0,
            localToRemoteIftConfigName = "",
            localToRemoteSyncPath = tempDir.absolutePath,
            remoteSyncPath = "",
            remoteToLocalIftConfigName = "",
            remoteToLocalSyncPath = "",
            httpProxyIp = "",
            httpProxyPort = 0,
            syncMode = SyncMode.IFT,
            environmentVariables = "",
            buildTarget = buildTarget,
        )
    }

    private object NoopFileChangesHandler : IFileChangesHandler {
        override fun init(compileContext: ICompileContext) = Unit
        override fun filter(file: List<File>): List<ChangedFile> = emptyList()
        override fun updateBuildFileRules(rules: List<String>, doNotIgnoreModulePaths: List<String>) = Unit
    }
}
