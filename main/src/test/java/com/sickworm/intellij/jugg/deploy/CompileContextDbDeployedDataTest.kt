package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.compiler.CompileOutput
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.mock.mockModule
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals

class CompileContextDbDeployedDataTest {

    private lateinit var tempDir: File
    private lateinit var juggRootDir: File
    private lateinit var dbDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("jugg_compile_context_deployed_data").toFile()
        juggRootDir = File(tempDir, "build/jugg")
        dbDir = File(juggRootDir, "database/compile_context.db")
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `updateDeployedData isolates same-path resources between app and test APKs`() {
        val db = CompileContextDb(juggRootDir, dbDir, logger)
        val appApk = File(juggRootDir, "classpath/apk/0_app-debug.apk").apply {
            parentFile.mkdirs()
            writeText("app")
        }
        val testApk = File(juggRootDir, "classpath/apk/2_features-debug-androidTest.apk").apply {
            parentFile.mkdirs()
            writeText("test")
        }
        val apkInfos = listOf(
            ApkInfo(
                files = listOf(ApkFileUnit("com.example.app", "", true, appApk)),
                applicationId = "com.example.app",
            ),
            ApkInfo(
                files = listOf(ApkFileUnit("com.example.features.test", "", true, testApk)),
                applicationId = "com.example.features.test",
                instrumentationTargetPackage = "com.example.features.test",
                instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner",
            ),
        )
        db.saveCompileContext(
            FullBuildInfo("./gradlew :app:assembleDebug", BuildTarget.ANDROID_TEST, 1234L),
            apkInfos,
            mapOf(mockModule.name to mockModule),
        )

        val appStagingDir = File(tempDir, "staging/app")
        val appArsc = File(appStagingDir, "resources.arsc").apply {
            parentFile.mkdirs()
            writeText("app-arsc-content")
        }
        val testStagingDir = File(tempDir, "staging/test")
        val testArsc = File(testStagingDir, "resources.arsc").apply {
            parentFile.mkdirs()
            writeText("test-arsc-content")
        }

        db.updateDeployedData(
            listOf(
                CompileOutput(CompileOutput.Type.Res, appArsc, appStagingDir, appApk.path),
                CompileOutput(CompileOutput.Type.Res, testArsc, testStagingDir, testApk.path),
            )
        )

        val deployed = db.getDeployedData().orEmpty()
            .filter { it.type == CompileOutput.Type.Res }
            .associate { it.apkPath to it.file.readText() }

        assertEquals("app-arsc-content", deployed[appApk.path])
        assertEquals("test-arsc-content", deployed[testApk.path])
    }
}
