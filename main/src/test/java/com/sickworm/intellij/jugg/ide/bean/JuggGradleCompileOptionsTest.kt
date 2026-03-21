package com.sickworm.intellij.jugg.ide.bean

import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.LocalClasspathStoragePathManager
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals

class JuggGradleCompileOptionsTest {

    @Test
    fun remoteInitGradleFilePath_shouldKeepDotGradleRootRelativePath() {
        val parentDir = Files.createTempDirectory("jugg_compile_options_parent").toFile()
        val projectDir = File(parentDir, "demo").apply { mkdirs() }
        try {
            val pathManager = JuggPathManager(projectDir)
            val options = JuggGradleCompileOptions(
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
            )

            assertEquals(
                File(options.remoteProjectPath, ".gradle/jugg/readProjectInfo.gradle.kts").path,
                options.remoteInitGradleFilePath,
            )
        } finally {
            parentDir.deleteRecursively()
        }
    }
}
