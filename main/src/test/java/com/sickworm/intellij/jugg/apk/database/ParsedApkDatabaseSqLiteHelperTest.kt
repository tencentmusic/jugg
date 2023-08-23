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
import kotlin.test.assertNotNull

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
            helper.saveParsedApk(parsedApk)
        }
        val apkInfoKeys = helper.getApkInfoKeys()
        println("Insert ${apkInfoKeys.firstOrNull()} cost $costTime ms")
        assertEquals(1, apkInfoKeys.size)

        testGetTableSize(helper, parsedApk)
        testGetParsedApk(helper, parsedApk)
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

    private fun testGetParsedApk(helper: ParsedApkDatabaseSqLiteHelper, parsedApk: ParsedApk) {
        val startTime = System.currentTimeMillis()
        val parsedApk2 = helper.getParsedApk(parsedApk.apkInfo)
        val endTime = System.currentTimeMillis()
        println("Get parsed apk cost ${endTime - startTime} ms")

        assertNotNull(parsedApk2)
        assertEquals(parsedApk.apkInfo, parsedApk2.apkInfo)
        assertEquals(parsedApk.dexFiles.size, parsedApk2.dexFiles.size)
        assertEquals(parsedApk.overlayFiles.size, parsedApk2.overlayFiles.size)
        assertEquals(parsedApk.classes.size, parsedApk2.classes.size)
        parsedApk.classes.forEach { (className, classNode) ->
            val classNode2 = parsedApk2.classes[className]
            assertNotNull(classNode2)
            assertEquals(classNode.methods.size, classNode2.methods.size)
            assertEquals(classNode.fields.size, classNode2.fields.size)

            classNode.methods.forEachIndexed { index, methodNode ->
                val methodNode2 = classNode2.methods[index]
                assertNotNull(methodNode2)
                assertEquals(methodNode.name, methodNode2.name)
                assertEquals(methodNode.desc, methodNode2.desc)
            }
            classNode.fields.forEachIndexed { index, fieldNode ->
                val fieldNode2 = classNode2.fields[index]
                assertNotNull(fieldNode2)
                assertEquals(fieldNode.access, fieldNode2.access)
                assertEquals(fieldNode.name, fieldNode2.name)
                assertEquals(fieldNode.type, fieldNode2.type)
            }
        }
    }

    private fun deleteDatabase() {
        if (dbFile.exists()) {
            dbFile.delete()
        }
    }
}