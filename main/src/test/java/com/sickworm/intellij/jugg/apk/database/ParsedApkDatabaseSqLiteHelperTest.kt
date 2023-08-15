package com.sickworm.intellij.jugg.apk.database

import com.sickworm.intellij.jugg.mock.buildDir
import junit.framework.TestCase.assertTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

class ParsedApkDatabaseSqLiteHelperTest {

    private val dbFile = File(buildDir, "test.db")

    @Test
    fun testCreateDataBase() {
        deleteDatabase()
        val helper = ParsedApkDatabaseSqLiteHelper(dbFile)

        helper.init()
        assertTrue(dbFile.exists())
    }

    @Test
    fun testInsertApkInfos() {
        deleteDatabase()
        val helper = ParsedApkDatabaseSqLiteHelper(dbFile)

        helper.init()
        assertTrue(dbFile.exists())

        helper.insertApkInfo("test")
        val apkInfoKeys = helper.getApkInfoKeys()
        assertEquals(1, apkInfoKeys.size)
        helper.insertApkInfo("test2")
        val apkInfoKeys2 = helper.getApkInfoKeys()
        assertEquals(2, apkInfoKeys2.size)
    }

    private fun deleteDatabase() {
        if (dbFile.exists()) {
            dbFile.delete()
        }
    }
}