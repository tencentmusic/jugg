package com.sickworm.intellij.jugg.apk.database

import com.sickworm.intellij.jugg.apk.ApkParser
import com.sickworm.intellij.jugg.compiler.ParsedApk
import com.sickworm.intellij.jugg.mock.buildDir
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.mock.projectInfo
import junit.framework.TestCase.assertTrue
import org.junit.Test
import java.io.File
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals

class ParsedApkDatabaseSqLiteHelperTest {

    private val dbFile = File(buildDir, "test.db")

    @Test
    fun testCreateDataBase() {
        deleteDatabase()
        val helper = ParsedApkDatabaseSqLiteHelper(dbFile, logger)

        helper.init()
        assertTrue(dbFile.exists())
    }

    @Test
    fun testInsertApkInfos() {
        deleteDatabase()
        val helper = ParsedApkDatabaseSqLiteHelper(dbFile, logger)

        helper.init()
        assertTrue(dbFile.exists())

        val parsedApk = ApkParser().parse(projectInfo.apkInfo, true)
        val costTime = measureTimeMillis {
            helper.insertApkInfo(listOf(parsedApk))
        }
        val apkInfoKeys = helper.getApkInfoKeys()
        println("Insert ${apkInfoKeys.firstOrNull()} cost $costTime ms")
        assertEquals(1, apkInfoKeys.size)

        testGetTableSize(helper, parsedApk)
    }

    private fun testGetTableSize(helper: ParsedApkDatabaseSqLiteHelper, parsedApk: ParsedApk) {
        val apkInfoKeys = listOf(
            1,
            parsedApk.dexFiles.size + parsedApk.overlayFiles.size,
            parsedApk.classes.size,
            parsedApk.classes.values.sumOf { it.methods.size },
            parsedApk.classes.values.sumOf { it.fields.size }
        )
        listOf("apk_info", "entry_info", "class_info", "method_info", "field_info").forEachIndexed { index, it ->
            val size = helper.getSize(it)
            println("Table $it has $size")
            assertEquals(apkInfoKeys[index], size, "Table $it not match")
        }
        println("DB size is ${dbFile.length() / 1024 / 1024}MB")
    }

    private fun deleteDatabase() {
        if (dbFile.exists()) {
            dbFile.delete()
        }
    }
}