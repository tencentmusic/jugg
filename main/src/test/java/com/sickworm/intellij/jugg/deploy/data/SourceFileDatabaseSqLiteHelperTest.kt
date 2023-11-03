package com.sickworm.intellij.jugg.deploy.data

import com.sickworm.intellij.jugg.mock.GradleSettingsDummyReader
import com.sickworm.intellij.jugg.mock.buildDir
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.mock.projectInfo
import junit.framework.TestCase.assertTrue
import org.junit.Test
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.assertEquals

class SourceFileDatabaseSqLiteHelperTest {

    private val dbFile = File(buildDir, "test.db")

    private val sourceDirs = GradleSettingsDummyReader(projectInfo.projectRoot).readProjectDirs()
        .map { File(it, "src/main/java") }

    @Test
    fun testCreateDataBase() {
        val helper = SourceFileDatabaseSqLiteHelper(dbFile, logger)

        helper.init()
        assertTrue(dbFile.exists())
    }

    @Test
    fun testUpdateSourceDirs() {
        val helper = SourceFileDatabaseSqLiteHelper(dbFile, logger)
        helper.init()
        helper.updateSourceDirs(sourceDirs)
        val originFileSize = helper.getFiles().size
        helper.updateSourceDirs(sourceDirs)
        assertEquals(originFileSize, helper.getFiles().size)
    }

    @Test
    fun testUpdateFiles() {
        val helper = SourceFileDatabaseSqLiteHelper(dbFile, logger)
        helper.init()
        helper.updateSourceDirs(sourceDirs)
        var originFileSize = helper.getFiles().size

        val newFiles = listOf(File("A.java"))
        helper.updateFiles(newFiles, emptyList())
        assertEquals(++originFileSize, helper.getFiles().size)

        helper.updateFiles(newFiles, emptyList())
        assertEquals(originFileSize, helper.getFiles().size)

        val deleteFiles = listOf(File("B.java")) // not exists
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