package com.sickworm.intellij.jugg.gradle.compile

import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.bean.SyncMode
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.LocalClasspathStoragePathManager
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals

class ApkLookupPlannerTest {

    @Test
    fun `androidTest keeps library test apk outputs optional`() {
        withTempOptions(BuildTarget.ANDROID_TEST) { options ->
            val plan = ApkLookupPlanner.build(
                options.copy(
                    outputApkName = "app/build/outputs/apk/debug/*.apk;app/build/outputs/apk/androidTest/debug/*.apk",
                    libraryTestApkOutputPatterns = listOf(
                        "library1/build/outputs/apk/androidTest/debug/*.apk",
                        " ",
                        "library2/build/outputs/apk/androidTest/debug/*.apk",
                    ),
                )
            )

            assertEquals(
                listOf(
                    "app/build/outputs/apk/debug/*.apk",
                    "app/build/outputs/apk/androidTest/debug/*.apk",
                ),
                plan.requiredPatterns,
            )
            assertEquals(
                listOf(
                    "library1/build/outputs/apk/androidTest/debug/*.apk",
                    "library2/build/outputs/apk/androidTest/debug/*.apk",
                ),
                plan.optionalLibraryTestPatterns,
            )
        }
    }

    @Test
    fun `app ignores library test apk outputs`() {
        withTempOptions(BuildTarget.APP) { options ->
            val plan = ApkLookupPlanner.build(
                options.copy(
                    outputApkName = "app/build/outputs/apk/debug/*.apk",
                    libraryTestApkOutputPatterns = listOf("library1/build/outputs/apk/androidTest/debug/*.apk"),
                )
            )

            assertEquals(listOf("app/build/outputs/apk/debug/*.apk"), plan.requiredPatterns)
            assertEquals(emptyList(), plan.optionalLibraryTestPatterns)
        }
    }

    @Test
    fun `blank remote apk path is not found`() {
        assertEquals(false, ApkLookupPlanner.isFoundRemoteApkPath(null))
        assertEquals(false, ApkLookupPlanner.isFoundRemoteApkPath(""))
        assertEquals(false, ApkLookupPlanner.isFoundRemoteApkPath("   "))
        assertEquals(true, ApkLookupPlanner.isFoundRemoteApkPath("library1/build/outputs/apk/androidTest/debug/library1.apk"))
    }

    private fun withTempOptions(buildTarget: BuildTarget, block: (JuggGradleCompileOptions) -> Unit) {
        val parentDir = Files.createTempDirectory("jugg_apk_lookup_parent").toFile()
        val projectDir = File(parentDir, "demo").apply { mkdirs() }
        try {
            block(makeOptions(projectDir, parentDir, buildTarget))
        } finally {
            parentDir.deleteRecursively()
        }
    }

    private fun makeOptions(
        projectDir: File,
        parentDir: File,
        buildTarget: BuildTarget,
    ): JuggGradleCompileOptions {
        val pathManager = JuggPathManager(projectDir)
        return JuggGradleCompileOptions(
            projectRootPath = projectDir.absolutePath,
            localClasspathStoragePath = LocalClasspathStoragePathManager(File(projectDir, "build/jugg/classpath")),
            initGradleFilePath = pathManager.initGradleFilePath.absolutePath,
            compileCommand = "./gradlew :app:assembleDebug",
            outputApkName = "app-debug.apk",
            isRemoteCompile = false,
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
}
