package com.sickworm.intellij.jugg.gradle.compile

import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.bean.SyncMode
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.LocalClasspathStoragePathManager
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for build target change detection in BaseBuildCommandHelper.
 */
class BaseBuildCommandHelperBuildTargetTest {

    private lateinit var tempDir: File
    private lateinit var pathManager: JuggPathManager
    private lateinit var helper: BaseBuildCommandHelper

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("jugg_bbt").toFile()
        pathManager = JuggPathManager(tempDir)
        helper = BaseBuildCommandHelper(pathManager)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun makeOptions(buildTarget: BuildTarget): JuggGradleCompileOptions =
        JuggGradleCompileOptions(
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
            localToRemoteSyncPath = "",
            remoteSyncPath = "",
            remoteToLocalIftConfigName = "",
            remoteToLocalSyncPath = "",
            httpProxyIp = "",
            httpProxyPort = 0,
            syncMode = SyncMode.IFT,
            environmentVariables = "",
            buildTarget = buildTarget,
        )

    @Test
    fun `isBuildTargetChanged returns false when no record exists`() {
        val options = makeOptions(BuildTarget.APP)
        assertFalse(helper.isBuildTargetChanged(options))
    }

    @Test
    fun `isBuildTargetChanged returns false when target matches recorded target`() {
        val options = makeOptions(BuildTarget.APP)
        helper.recordBaseBuildCmd(options, BuildTarget.APP)
        assertFalse(helper.isBuildTargetChanged(options))
    }

    @Test
    fun `isBuildTargetChanged returns true when target differs from recorded target`() {
        val appOptions = makeOptions(BuildTarget.APP)
        helper.recordBaseBuildCmd(appOptions, BuildTarget.APP)

        val testOptions = makeOptions(BuildTarget.ANDROID_TEST)
        assertTrue(helper.isBuildTargetChanged(testOptions))
    }

    @Test
    fun `isBuildTargetChanged returns true when switching back from ANDROID_TEST to APP`() {
        val testOptions = makeOptions(BuildTarget.ANDROID_TEST)
        helper.recordBaseBuildCmd(testOptions, BuildTarget.ANDROID_TEST)

        val appOptions = makeOptions(BuildTarget.APP)
        assertTrue(helper.isBuildTargetChanged(appOptions))
    }
}
