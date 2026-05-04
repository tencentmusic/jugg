package com.sickworm.intellij.jugg.deploy

import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.compiler.clearDir
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
import kotlin.test.assertNull

class CompileContextDbFullBuildInfoTest {

    private lateinit var tempDir: File
    private lateinit var juggRootDir: File
    private lateinit var dbDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("jugg_full_build_info").toFile()
        juggRootDir = File(tempDir, "build/jugg")
        dbDir = File(juggRootDir, "database/compile_context.db")
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `save compile context stores full build info`() {
        val db = CompileContextDb(juggRootDir, dbDir, logger)
        val fullBuildInfo = FullBuildInfo(
            compileCommand = "./gradlew :app:assembleDebug",
            buildTarget = BuildTarget.APP,
            createdAt = 1234L,
        )

        db.saveCompileContext(fullBuildInfo, projectInfo.apkInfos, mapOf(mockModule.name to mockModule))

        assertEquals(fullBuildInfo, db.getFullBuildInfoFromDb())
    }

    @Test
    fun `missing full build info does not break compile context recovery`() {
        val db = CompileContextDb(juggRootDir, dbDir, logger)
        val fullBuildInfo = FullBuildInfo(
            compileCommand = "./gradlew :app:assembleDebug",
            buildTarget = BuildTarget.APP,
            createdAt = 1234L,
        )
        db.saveCompileContext(fullBuildInfo, projectInfo.apkInfos, mapOf(mockModule.name to mockModule))
        File(dbDir, "full_build_info.json").delete()

        val compileContextInfo = db.getCompileBuildPathInfoFromDb()

        assertNull(db.getFullBuildInfoFromDb())
        assertNotNull(compileContextInfo)
        assertEquals(projectInfo.apkInfos.size, compileContextInfo.apkInfos.size)
        assertEquals(1, compileContextInfo.moduleBuildPathInfos.size)
    }

    @Test
    fun `delete compile context clears full build info`() {
        val db = CompileContextDb(juggRootDir, dbDir, logger)
        db.saveCompileContext(
            FullBuildInfo("./gradlew :app:assembleDebug", BuildTarget.APP, 1234L),
            projectInfo.apkInfos,
            mapOf(mockModule.name to mockModule),
        )

        db.deleteCompileContext()

        assertNull(db.getFullBuildInfoFromDb())
    }
}
