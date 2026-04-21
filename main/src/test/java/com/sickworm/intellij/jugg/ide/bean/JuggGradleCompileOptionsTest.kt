package com.sickworm.intellij.jugg.ide.bean

import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.LocalClasspathStoragePathManager
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals

class JuggGradleCompileOptionsTest {

    private fun makeOptions(
        projectDir: File,
        parentDir: File,
        buildTarget: BuildTarget = BuildTarget.APP,
    ): JuggGradleCompileOptions {
        val pathManager = JuggPathManager(projectDir)
        return JuggGradleCompileOptions(
            projectRootPath = projectDir.absolutePath,
            localClasspathStoragePath = LocalClasspathStoragePathManager(File(projectDir, "build/jugg/classpath")),
            initGradleFilePath = pathManager.initGradleFilePath.absolutePath,
            compileCommand = "./gradlew clean assembleDebug",
            outputApkName = "app-debug.apk",
            isRemoteCompile = true,
            isSyncAllProjects = false,
            remoteSshUser = "tester",
            remoteSshPassword = "",
            remoteSshIp = "127.0.0.1",
            remoteSshPort = 22,
            localToRemoteIftConfigName = "local_config",
            localToRemoteSyncPath = parentDir.absolutePath,
            remoteSyncPath = "/remote",
            remoteToLocalIftConfigName = "remote_config",
            remoteToLocalSyncPath = File(parentDir, "fetch").absolutePath,
            httpProxyIp = "",
            httpProxyPort = 0,
            syncMode = SyncMode.RSYNC_SIMPLE,
            environmentVariables = "",
            buildTarget = buildTarget,
        )
    }

    @Test
    fun remoteInitGradleFilePath_shouldKeepDotGradleRootRelativePath() {
        val parentDir = Files.createTempDirectory("jugg_compile_options_parent").toFile()
        val projectDir = File(parentDir, "demo").apply { mkdirs() }
        try {
            val options = makeOptions(projectDir, parentDir)

            assertEquals(
                File(options.remoteProjectPath, ".gradle/jugg/readProjectInfo.gradle.kts").path,
                options.remoteInitGradleFilePath,
            )
        } finally {
            parentDir.deleteRecursively()
        }
    }

    @Test
    fun buildTarget_defaultsToApp() {
        val parentDir = Files.createTempDirectory("jugg_compile_options_bt").toFile()
        val projectDir = File(parentDir, "demo").apply { mkdirs() }
        try {
            val options = makeOptions(projectDir, parentDir)
            assertEquals(BuildTarget.APP, options.buildTarget)
        } finally {
            parentDir.deleteRecursively()
        }
    }

    @Test
    fun buildTarget_canBeSetToAndroidTest() {
        val parentDir = Files.createTempDirectory("jugg_compile_options_bt2").toFile()
        val projectDir = File(parentDir, "demo").apply { mkdirs() }
        try {
            val options = makeOptions(projectDir, parentDir, BuildTarget.ANDROID_TEST)
            assertEquals(BuildTarget.ANDROID_TEST, options.buildTarget)
        } finally {
            parentDir.deleteRecursively()
        }
    }
}
