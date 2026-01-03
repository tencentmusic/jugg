package com.sickworm.intellij.jugg.deploy.data

import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.listFilesRecursively
import com.sickworm.intellij.jugg.mock.*
import com.sickworm.intellij.jugg.project.ChangedFile
import junit.framework.TestCase.assertTrue
import org.junit.Test
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.assertEquals

class SourceFileDatabaseSqLiteHelperTest {

    private val dbFile = File(buildDir, "test.db")

    private val sourceDirs1 = listOf(
        File(assetsAndroidDir, "app/src/main/java"),
    )

    private val sourceDirs2 = listOf(
        File(assetsAndroidModifySourceDir, "app/src/main/java")
    )

    private val sourceDirs = sourceDirs1 + sourceDirs2

    @Test
    fun testCreateDataBase() {
        val helper = SourceFileDatabaseSqLiteHelper(TestGlobal.projectInfo.projectRoot, dbFile, logger)

        helper.init()
        assertTrue(dbFile.exists())
    }

    @Test
    fun testUpdateSourceDirs() {
        val helper = SourceFileDatabaseSqLiteHelper(TestGlobal.projectInfo.projectRoot, dbFile, logger)
        helper.init()
        helper.updateSourceDirs(sourceDirs1)
        val sourceDirs1FileSize = helper.getFiles().size
        assertEquals(sourceDirs1.flatMap { it.listFilesRecursively() }.size, sourceDirs1FileSize)

        helper.updateSourceDirs(sourceDirs)
        val sourceDirsFileSize = helper.getFiles().size
        assertEquals(sourceDirs.flatMap { it.listFilesRecursively() }.size, sourceDirsFileSize)

        helper.updateSourceDirs(sourceDirs)
        val sourceDirsFileSize2 = helper.getFiles().size
        assertEquals(sourceDirsFileSize, sourceDirsFileSize2)

        helper.updateSourceDirs(sourceDirs2)
        val sourceDirs2FileSize = helper.getFiles().size
        assertEquals(sourceDirs2.flatMap { it.listFilesRecursively() }.size, sourceDirs2FileSize)
    }

    @Test
    fun testUpdateFiles() {
        val helper = SourceFileDatabaseSqLiteHelper(TestGlobal.projectInfo.projectRoot, dbFile, logger)
        helper.init()
        helper.updateSourceDirs(sourceDirs)
        var originFileSize = helper.getFiles().size

        val newFiles = listOf(ChangedFile(CompileFile.Type.Java, File("lib/A.java").absoluteFile, File("lib").absoluteFile, mockModule))
        helper.updateFiles(newFiles, emptyList())
        assertEquals(++originFileSize, helper.getFiles().size)

        helper.updateFiles(newFiles, emptyList())
        assertEquals(originFileSize, helper.getFiles().size)

        val deleteFiles = listOf(File("lib/B.java").absoluteFile) // not exists
        helper.updateFiles(emptyList(), deleteFiles)
        assertEquals(originFileSize, helper.getFiles().size)

        val deleteFiles2 = listOf(helper.getFiles().first())
        helper.updateFiles(emptyList(), deleteFiles2)
        assertEquals(--originFileSize, helper.getFiles().size)

        val deleteFiles3 = helper.getFiles()
        helper.updateFiles(emptyList(), deleteFiles3)
        assertEquals(0, helper.getFiles().size)
    }

    @BeforeTest
    fun deleteDatabase() {
        if (dbFile.exists()) {
            dbFile.delete()
        }
    }
}