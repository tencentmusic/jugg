package com.sickworm.intellij.jugg.deploy

import com.google.gson.JsonParser
import com.sickworm.intellij.jugg.apk.ApkInfo
import com.sickworm.intellij.jugg.compiler.BuildTarget
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.mock.mockModule
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompileContextDbFullBuildInfoTest {

    private lateinit var tempDir: File
    private lateinit var juggRootDir: File
    private lateinit var dbDir: File
    private lateinit var apkInfos: List<ApkInfo>

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("jugg_full_build_info").toFile()
        juggRootDir = File(tempDir, "build/jugg")
        dbDir = File(juggRootDir, "database/compile_context.db")
        apkInfos = listOf(ApkInfo(File(tempDir, "app.apk").apply { writeText("apk") }, "com.example.app"))
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

        db.saveCompileContext(fullBuildInfo, apkInfos, mapOf(mockModule.name to mockModule))

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
        db.saveCompileContext(fullBuildInfo, apkInfos, mapOf(mockModule.name to mockModule))
        File(dbDir, "full_build_info.json").delete()

        val compileContextInfo = db.getCompileBuildPathInfoFromDb()

        assertNull(db.getFullBuildInfoFromDb())
        assertNotNull(compileContextInfo)
        assertEquals(apkInfos.size, compileContextInfo.apkInfos.size)
        assertEquals(1, compileContextInfo.moduleBuildPathInfos.size)
    }

    @Test
    fun `version 1 compile context recovers with conventional build directory`() {
        val db = saveCompileContext()
        rewriteModuleBuildInfo(version = 1, removeBuildDirRelativePath = true)

        val compileContextInfo = assertNotNull(db.getCompileBuildPathInfoFromDb())

        val buildPathInfo = compileContextInfo.moduleBuildPathInfos.getValue(mockModule.name)
        assertEquals("", buildPathInfo.buildDirRelativePath)
        assertEquals(File(buildPathInfo.moduleRootDir, "build"), buildPathInfo.buildDir)
        assertTrue(File(dbDir, "complete_flag").exists())
    }

    @Test
    fun `version 2 compile context keeps custom build directory`() {
        val customBuildDir = "build/custom-module"
        val module = mockModule.copy(
            buildPathInfo = mockModule.buildPathInfo.copy(buildDirRelativePath = customBuildDir),
        )
        val db = saveCompileContext(module)

        val compileContextInfo = assertNotNull(db.getCompileBuildPathInfoFromDb())

        val buildPathInfo = compileContextInfo.moduleBuildPathInfos.getValue(module.name)
        assertEquals(customBuildDir, buildPathInfo.buildDirRelativePath)
        assertEquals(File(buildPathInfo.projectRootDir, customBuildDir), buildPathInfo.buildDir)
    }

    @Test
    fun `unsupported module build info version invalidates compile context`() {
        val db = saveCompileContext()
        rewriteModuleBuildInfo(version = 3)

        assertNull(db.getCompileBuildPathInfoFromDb())
        assertFalse(File(dbDir, "complete_flag").exists())
    }

    @Test
    fun `missing complete flag does not recover version 1 compile context`() {
        val db = saveCompileContext()
        rewriteModuleBuildInfo(version = 1, removeBuildDirRelativePath = true)
        File(dbDir, "complete_flag").delete()

        assertNull(db.getCompileBuildPathInfoFromDb())
        assertFalse(File(dbDir, "complete_flag").exists())
    }

    @Test
    fun `delete compile context clears full build info`() {
        val db = CompileContextDb(juggRootDir, dbDir, logger)
        db.saveCompileContext(
            FullBuildInfo("./gradlew :app:assembleDebug", BuildTarget.APP, 1234L),
            apkInfos,
            mapOf(mockModule.name to mockModule),
        )

        db.deleteCompileContext()

        assertNull(db.getFullBuildInfoFromDb())
    }

    private fun saveCompileContext(module: ModuleInfo = mockModule): CompileContextDb {
        return CompileContextDb(juggRootDir, dbDir, logger).also { db ->
            db.saveCompileContext(
                FullBuildInfo("./gradlew :app:assembleDebug", BuildTarget.APP, 1234L),
                apkInfos,
                mapOf(module.name to module),
            )
        }
    }

    private fun rewriteModuleBuildInfo(version: Int, removeBuildDirRelativePath: Boolean = false) {
        val moduleBuildInfoFile = File(dbDir, "module_builds.json")
        val root = JsonParser.parseString(moduleBuildInfoFile.readText()).asJsonObject
        root.addProperty("version", version)
        if (removeBuildDirRelativePath) {
            root.getAsJsonObject("modulePathInfos").entrySet().forEach { (_, module) ->
                module.asJsonObject.remove("buildDirRelativePath")
            }
        }
        moduleBuildInfoFile.writeText(root.toString())
    }
}
