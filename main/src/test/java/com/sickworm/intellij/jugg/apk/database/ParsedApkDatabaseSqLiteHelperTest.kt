package com.sickworm.intellij.jugg.apk.database

import com.android.tools.idea.run.ApkInfo
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

        val apkOverlays = ApkParser().parseEntries(projectInfo.apkInfo)
        val diffResult = helper.diffApk(apkOverlays)
        val parsedApk = ApkParser().parse(projectInfo.apkInfo)

        val costTime = measureTimeMillis {
            helper.saveParsedApk(parsedApk, diffResult)
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
        var apkEntries = ApkParser().parseEntries(projectInfo.apkInfo)
        val originApkEntries = apkEntries
        val emptyDiffResult = ParsedApkDiffResult(apkEntries.apkInfo)
        var diffResult = helper.diffApk(apkEntries)
        assertEquals(
            emptyDiffResult.copy(updatedApkInfos = 1, addedDexFiles = apkEntries.dexFiles, addedOverlayFiles = apkEntries.overlayFiles),
            diffResult
        )
        var parsedApk: ParsedApk = ApkParser().parse(projectInfo.apkInfo, diffResult.includeEntries)
        var finalParsedApk = parsedApk
        var updateResult = helper.saveParsedApk(parsedApk, diffResult)
        assertUpdateResultEquals(
            ParsedApkUpdateResult.success(diffResult).copy(addedClasses = parsedApk.classes.map { it.value.className }.toList()),
            updateResult
        )

        diffResult = helper.diffApk(apkEntries)
        assertEquals(emptyDiffResult, diffResult)

        projectInfo.apkInfo.refreshApkInfoKey()
        diffResult = helper.diffApk(apkEntries)
        assertEquals(emptyDiffResult.copy(updatedApkInfos = 1), diffResult)
        parsedApk = ApkParser().parse(apkEntries.apkInfo, diffResult.includeEntries)
        updateResult = helper.saveParsedApk(parsedApk, diffResult)
        assertUpdateResultEquals(ParsedApkUpdateResult.success(diffResult), updateResult)

        // removeDexFiles
        logger.info("removeDexFiles")
        val firstDex = originApkEntries.dexFiles.first()
        projectInfo.apkInfo.refreshApkInfoKey()
        val removedDexFiles = mapOf(
            firstDex.key to firstDex.value
        )
        apkEntries = apkEntries.copy(dexFiles = apkEntries.dexFiles - removedDexFiles.keys)
        diffResult = helper.diffApk(apkEntries)
        assertEquals(emptyDiffResult.copy(updatedApkInfos = 1, removedDexFiles = removedDexFiles), diffResult)
        parsedApk = ApkParser().parse(apkEntries.apkInfo, diffResult.includeEntries)
        updateResult = helper.saveParsedApk(parsedApk, diffResult)
        assertTrue(updateResult.removedClasses.isNotEmpty())

        // addDexFiles
        logger.info("addDexFiles")
        projectInfo.apkInfo.refreshApkInfoKey()
        val addDexFiles = mapOf(
            firstDex.key to firstDex.value
        )
        apkEntries = apkEntries.copy(dexFiles = apkEntries.dexFiles + addDexFiles)
        diffResult = helper.diffApk(apkEntries)
        assertEquals(emptyDiffResult.copy(updatedApkInfos = 1, addedDexFiles = addDexFiles), diffResult)
        parsedApk = ApkParser().parse(apkEntries.apkInfo, diffResult.includeEntries)
        updateResult = helper.saveParsedApk(parsedApk, diffResult)
        assertUpdateResultEquals(ParsedApkUpdateResult.success(diffResult).copy(addedClasses = parsedApk.classes.map { it.value.className}), updateResult)

        // updateDexFiles
        logger.info("updateDexFiles")
        projectInfo.apkInfo.refreshApkInfoKey()
        val updatedDexFiles = mapOf(
            firstDex.key to firstDex.value.copy(checksum = 1)
        )
        apkEntries = apkEntries.copy(dexFiles = apkEntries.dexFiles + updatedDexFiles)
        diffResult = helper.diffApk(apkEntries)
        assertEquals(emptyDiffResult.copy(updatedApkInfos = 1, updatedDexFiles = updatedDexFiles), diffResult)
        parsedApk = ApkParser().parse(apkEntries.apkInfo, diffResult.includeEntries)
        updateResult = helper.saveParsedApk(parsedApk, diffResult)
        assertUpdateResultEquals(ParsedApkUpdateResult.success(diffResult).copy(updatedClasses = parsedApk.classes.map { it.value.className }), updateResult)
        finalParsedApk = ParsedApk(
            finalParsedApk.apkInfo,
            finalParsedApk.classes,
            finalParsedApk.dexFiles + updatedDexFiles,
            finalParsedApk.overlayFiles,
            finalParsedApk.methodRefs,
            finalParsedApk.fieldRefs
        )

        // removeOverlayFiles
        logger.info("removeOverlayFiles")
        val firstOverlayFile = originApkEntries.overlayFiles.first()
        projectInfo.apkInfo.refreshApkInfoKey()
        val deletedOverlayFiles = mapOf(
            firstOverlayFile.key to firstOverlayFile.value
        )
        apkEntries = apkEntries.copy(overlayFiles = apkEntries.overlayFiles - deletedOverlayFiles.keys)
        diffResult = helper.diffApk(apkEntries)
        assertEquals(emptyDiffResult.copy(updatedApkInfos = 1, removedOverlayFiles = deletedOverlayFiles), diffResult)
        parsedApk = ApkParser().parse(apkEntries.apkInfo, diffResult.includeEntries)
        updateResult = helper.saveParsedApk(parsedApk, diffResult)
        assertUpdateResultEquals(ParsedApkUpdateResult.success(diffResult), updateResult)

        // addOverlayFiles
        logger.info("addOverlayFiles")
        projectInfo.apkInfo.refreshApkInfoKey()
        val addOverlayFiles = mapOf(
            firstOverlayFile.key to firstOverlayFile.value
        )
        apkEntries = apkEntries.copy(overlayFiles = apkEntries.overlayFiles + addOverlayFiles)
        diffResult = helper.diffApk(apkEntries)
        assertEquals(emptyDiffResult.copy(updatedApkInfos = 1, addedOverlayFiles = addOverlayFiles), diffResult)
        parsedApk = ApkParser().parse(apkEntries.apkInfo, diffResult.includeEntries)
        updateResult = helper.saveParsedApk(parsedApk, diffResult)
        assertUpdateResultEquals(ParsedApkUpdateResult.success(diffResult), updateResult)

        // updateOverlayFiles
        logger.info("updateOverlayFiles")
        projectInfo.apkInfo.refreshApkInfoKey()
        val updatedOverlayFiles = mapOf(
            firstOverlayFile.key to firstOverlayFile.value.copy(checksum = 1)
        )
        apkEntries = apkEntries.copy(overlayFiles = apkEntries.overlayFiles + updatedOverlayFiles)
        diffResult = helper.diffApk(apkEntries)
        assertEquals(emptyDiffResult.copy(updatedApkInfos = 1, updatedOverlayFiles = updatedOverlayFiles), diffResult)
        parsedApk = ApkParser().parse(apkEntries.apkInfo, diffResult.includeEntries)
        updateResult = helper.saveParsedApk(parsedApk, diffResult)
        assertUpdateResultEquals(ParsedApkUpdateResult.success(diffResult), updateResult)
        finalParsedApk = ParsedApk(
            finalParsedApk.apkInfo,
            finalParsedApk.classes,
            finalParsedApk.dexFiles,
            finalParsedApk.overlayFiles + updatedOverlayFiles,
            finalParsedApk.methodRefs,
            finalParsedApk.fieldRefs
        )

        testGetTableSize(helper, finalParsedApk)
        testGetParsedApk(helper, finalParsedApk)
    }

    private fun ApkInfo.refreshApkInfoKey() {
        files.first().apkFile.also {
            it.setLastModified(it.lastModified() + 1)
        }
    }

    private fun assertUpdateResultEquals(expected: ParsedApkUpdateResult, actual: ParsedApkUpdateResult) {
        assertEquals(expected.isSuccess, actual.isSuccess, "Error: ${actual.errorMessage}")
        assertEquals(expected.diffResult, actual.diffResult)
        assertEquals(expected.addedClasses.size, actual.addedClasses.size)
        assertEquals(expected.addedClasses.sorted(), actual.addedClasses.sorted())
        assertEquals(expected.updatedClasses.sorted(), actual.updatedClasses.sorted())
        assertEquals(expected.updatedClasses.size, actual.updatedClasses.size)
        assertEquals(expected.removedClasses.sorted(), actual.removedClasses.sorted())
        assertEquals(expected.removedClasses.size, actual.removedClasses.size)
    }


    @BeforeTest
    fun deleteDatabase() {
        if (dbFile.exists()) {
            dbFile.delete()
        }
    }
}