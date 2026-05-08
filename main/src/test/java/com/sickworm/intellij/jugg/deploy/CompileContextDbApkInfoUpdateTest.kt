package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.mock.mockModule
import com.sickworm.intellij.jugg.mock.projectInfo
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CompileContextDbApkInfoUpdateTest {

    private lateinit var tempDir: File
    private lateinit var juggRootDir: File
    private lateinit var dbDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("jugg_compile_context_apk_update").toFile()
        juggRootDir = File(tempDir, "build/jugg")
        dbDir = File(juggRootDir, "database/compile_context.db")
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `update apk infos keeps full build info and persists new apk list`() {
        val db = CompileContextDb(juggRootDir, dbDir, logger)
        val fullBuildInfo = FullBuildInfo("./gradlew :app:assembleDebug", BuildTarget.APP, 1234L)
        val originalApks = projectInfo.apkInfos
        val extraApkFile = File(tempDir, "library-test.apk").apply {
            parentFile.mkdirs()
            writeText("apk")
        }
        val updatedApks = originalApks + ApkInfo(extraApkFile, "com.example.library1.test")

        db.saveCompileContext(fullBuildInfo, originalApks, mapOf(mockModule.name to mockModule))
        db.updateApkInfos(updatedApks)

        assertEquals(fullBuildInfo, db.getFullBuildInfoFromDb())
        val compileContextInfo = assertNotNull(db.getCompileBuildPathInfoFromDb())
        assertEquals(updatedApks.size, compileContextInfo.apkInfos.size)
        assertEquals(updatedApks.map { it.applicationId }, compileContextInfo.apkInfos.map { it.applicationId })
    }
}
