package com.sickworm.intellij.jugg.apk.database

import com.jetbrains.rd.util.first
import com.sickworm.intellij.jugg.deploy.data.*
import com.sickworm.intellij.jugg.mock.buildDir
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.mock.projectInfo
import junit.framework.TestCase.assertTrue
import org.junit.Test
import java.io.File
import kotlin.system.measureTimeMillis
import kotlin.test.BeforeTest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ParsedApkDatabaseSqLiteHelperTest {

    private val dbFile = File(buildDir, "test.db")

    @Test
    fun testCreateDataBase() {
        val helper = ParsedApkDatabaseSqLiteHelper(dbFile, logger)

        helper.init()
        assertTrue(dbFile.exists())
    }

    @Test
    fun testInsertApkInfos() {
        val helper = ParsedApkDatabaseSqLiteHelper(dbFile, logger)

        helper.init()
        assertTrue(dbFile.exists())

        val parsedApk = ApkParser().parse(projectInfo.apkInfo)
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
            parsedApk.classes.values.sumOf { it.fields.size },
            parsedApk.methodRefs.values.sumOf { it.size },
            parsedApk.fieldRefs.values.sumOf { it.size },
        )
        listOf("apk_info", "entry_info", "class_info", "method_info", "field_info", "method_refs", "field_refs").forEachIndexed { index, it ->
            val size = helper.getSize(it)
            println("Table $it has $size")
            assertEquals(apkInfoKeys[index], size, "Table $it not match")
        }
        println("DB size is ${dbFile.length() / 1024 / 1024}MB")
    }

    private fun testGetParsedApk(helper: ParsedApkDatabaseSqLiteHelper, parsedApk: ParsedApk) {
        val startTime = System.currentTimeMillis()
        val parsedApkFromDb = helper.getParsedApk(parsedApk.apkInfo)
        val endTime = System.currentTimeMillis()
        println("Get parsed apk cost ${endTime - startTime} ms")

        assertNotNull(parsedApkFromDb)
        assertEquals(parsedApk.apkInfo, parsedApkFromDb.apkInfo)
        assertEquals(parsedApk.dexFiles.size, parsedApkFromDb.dexFiles.size)
        assertEquals(parsedApk.overlayFiles.size, parsedApkFromDb.overlayFiles.size)
        assertEquals(parsedApk.classes.size, parsedApkFromDb.classes.size)
        parsedApk.classes.forEach { (className, classNode) ->
            val classNode2 = parsedApkFromDb.classes[className]
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

        assertEquals(parsedApk.methodRefs.size, parsedApkFromDb.methodRefs.size)
        parsedApk.methodRefs.forEach { (methodNode, classRefs) ->
            val classRefs2 = parsedApkFromDb.methodRefs[methodNode]!!
            assertNotNull(classRefs2)
            assertContentEquals(classRefs, classRefs2)
        }

        assertEquals(parsedApk.fieldRefs.size, parsedApkFromDb.fieldRefs.size)
        parsedApk.fieldRefs.forEach { (fieldNode, classRefs) ->
            val classRefs2 = parsedApkFromDb.fieldRefs[fieldNode]!!
            assertNotNull(classRefs2)
            assertContentEquals(classRefs, classRefs2)
        }
    }

    @Test
    fun testUpdateApkInfos() {
        val helper = ParsedApkDatabaseSqLiteHelper(dbFile, logger)
        helper.init()
        val parsedApk: ParsedApk = ApkParser().parse(projectInfo.apkInfo)
        helper.saveParsedApk(parsedApk)

        val parsedApkDiffResult = ParsedApkDiffResult()

        var apkOverlays = ApkParser().parseOverlays(projectInfo.apkInfo)
        var result = helper.diffApk(apkOverlays)
        assertEquals(parsedApkDiffResult, result)

        projectInfo.apkInfo.files.first().apkFile.setLastModified(System.currentTimeMillis())
        result = helper.diffApk(apkOverlays)
        assertEquals(parsedApkDiffResult.copy(updatedApkInfos = 1), result)

        val originApkOverlays = apkOverlays
        val addDexFiles = mapOf(
            "test.dex" to JuggFileInfo("test.dex", 1)
        )
        apkOverlays = originApkOverlays.copy(dexFiles = originApkOverlays.dexFiles + addDexFiles)
        result = helper.diffApk(apkOverlays)
        assertEquals(parsedApkDiffResult.copy(updatedApkInfos = 1, addedDexFiles = addDexFiles.keys.toList()), result)

        val removedDexFiles = mapOf(
            originApkOverlays.dexFiles.first().key to originApkOverlays.dexFiles.first().value
        )
        apkOverlays = originApkOverlays.copy(dexFiles = originApkOverlays.dexFiles - removedDexFiles.keys)
        result = helper.diffApk(apkOverlays)
        assertEquals(parsedApkDiffResult.copy(updatedApkInfos = 1, removedDexFiles = removedDexFiles.keys.toList()), result)

        val updatedDexFiles = mapOf(
            originApkOverlays.dexFiles.first().key to JuggFileInfo(originApkOverlays.dexFiles.first().key, 2)
        )
        apkOverlays = originApkOverlays.copy(dexFiles = originApkOverlays.dexFiles + updatedDexFiles)
        result = helper.diffApk(apkOverlays)
        assertEquals(parsedApkDiffResult.copy(updatedApkInfos = 1, updatedDexFiles = updatedDexFiles.keys.toList()), result)

        val addOverlayFiles = mapOf(
            "test.dat" to JuggFileInfo("test.dat", 1)
        )
        apkOverlays = originApkOverlays.copy(overlayFiles = originApkOverlays.overlayFiles + addOverlayFiles)
        result = helper.diffApk(apkOverlays)
        assertEquals(parsedApkDiffResult.copy(updatedApkInfos = 1, addedOverlayFiles = addOverlayFiles.keys.toList()), result)

        val deletedOverlayFiles = mapOf(
            originApkOverlays.overlayFiles.first().key to originApkOverlays.overlayFiles.first().value
        )
        apkOverlays = originApkOverlays.copy(overlayFiles = originApkOverlays.overlayFiles - deletedOverlayFiles.keys)
        result = helper.diffApk(apkOverlays)
        assertEquals(parsedApkDiffResult.copy(updatedApkInfos = 1, removedOverlayFiles = deletedOverlayFiles.keys.toList()), result)

        val updatedOverlayFiles = mapOf(
            originApkOverlays.overlayFiles.first().key to JuggFileInfo(originApkOverlays.overlayFiles.first().key, 2)
        )
        apkOverlays = originApkOverlays.copy(overlayFiles = originApkOverlays.overlayFiles + updatedOverlayFiles)
        result = helper.diffApk(apkOverlays)
        assertEquals(parsedApkDiffResult.copy(updatedApkInfos = 1, updatedOverlayFiles = updatedOverlayFiles.keys.toList()), result)

    }

    @BeforeTest
    fun deleteDatabase() {
        if (dbFile.exists()) {
            dbFile.delete()
        }
    }
}