package com.sickworm.intellij.jugg.deploy.data

import com.sickworm.intellij.jugg.apk.ApkInfo
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

class DeployDataDatabaseSqLiteHelperTest {

    private val dbFile = File(buildDir, "test.db")

    @Test
    fun testCreateDataBase() {
        val helper = DeployDataDatabaseSqLiteHelper(dbFile, logger)

        helper.init()
        assertTrue(dbFile.exists())
    }

    @Test
    fun testInsertApkInfos() {
        val helper = DeployDataDatabaseSqLiteHelper(dbFile, logger)

        helper.init()
        assertTrue(dbFile.exists())

        val apkOverlays = ApkParser().parseEntries(projectInfo.apkFile)
        val diffResult = helper.diffApk(apkOverlays)
        val parsedApk = ApkParser().parse(projectInfo.apkFile).filterNotExistsClassesRef()

        val costTime = measureTimeMillis {
            helper.saveParsedApk(parsedApk, diffResult)
        }
        val apkInfoKeys = helper.getApkInfoKeys()
        println("Insert ${apkInfoKeys.firstOrNull()} cost $costTime ms")
        assertEquals(1, apkInfoKeys.size)

        testGetTableSize(helper, parsedApk)
        testGetParsedApk(helper, parsedApk)
    }

    private fun testGetTableSize(helper: DeployDataDatabaseSqLiteHelper, parsedApk: ParsedApk) {
        val apkInfoKeys = listOf(
            1,
            parsedApk.dexFiles.size + parsedApk.overlayFiles.size,
            parsedApk.classes.size,
            parsedApk.methodRefs.values.sumOf { it.size },
            parsedApk.fieldRefs.values.sumOf { it.size },
        )
        listOf("apk_info", "entry_info", "class_info", "method_refs", "field_refs").forEachIndexed { index, it ->
            val size = helper.getSize(it)
            println("Table $it has $size")
            assertEquals(apkInfoKeys[index], size, "Table $it not match")
        }
        println("DB size is ${dbFile.length() / 1024 / 1024}MB")
    }

    private fun testGetParsedApk(helper: DeployDataDatabaseSqLiteHelper, parsedApk: ParsedApk) {
        val startTime = System.currentTimeMillis()
        val parsedApkFromDb = helper.getParsedApk(parsedApk.apkFile)
        val endTime = System.currentTimeMillis()
        println("Get parsed apk cost ${endTime - startTime} ms")

        assertNotNull(parsedApkFromDb)
        assertEquals(parsedApk.apkFile, parsedApkFromDb.apkFile)
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
            assertContentEquals(classRefs.sorted(), classRefs2.sorted())
        }

        assertEquals(parsedApk.fieldRefs.size, parsedApkFromDb.fieldRefs.size)
        parsedApk.fieldRefs.forEach { (fieldNode, classRefs) ->
            val classRefs2 = parsedApkFromDb.fieldRefs[fieldNode]!!
            assertNotNull(classRefs2)
            assertContentEquals(classRefs, classRefs2)
        }

        assertEquals(parsedApk.subclassRefs.size, parsedApkFromDb.subclassRefs.size)
        parsedApk.subclassRefs.forEach { (className, classRefs) ->
            val dbClassRefs = parsedApkFromDb.subclassRefs[className]!!
            assertNotNull(dbClassRefs)
            assertContentEquals(classRefs.sorted(), dbClassRefs.sorted(), "class: $className\nclassRefs: ${classRefs.sorted()}\ndbClassRefs: ${dbClassRefs.sorted()}")
        }
    }

    @Test
    fun testUpdateApkInfos() {
        val helper = DeployDataDatabaseSqLiteHelper(dbFile, logger)
        helper.init()
        var apkEntries = ApkParser().parseEntries(projectInfo.apkFile)
        val originApkEntries = apkEntries
        val emptyDiffResult = ParsedApkDiffResult(apkEntries.apkFile)
        var diffResult = helper.diffApk(apkEntries)
        assertEquals(
            emptyDiffResult.copy(updatedApkInfos = 1, addedDexFiles = apkEntries.dexFiles, addedOverlayFiles = apkEntries.overlayFiles, isFullUpdate = true),
            diffResult
        )
        var parsedApk: ParsedApk = ApkParser().parse(projectInfo.apkFile, diffResult.includeEntries).filterNotExistsClassesRef()
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
        parsedApk = ApkParser().parse(projectInfo.apkFile, diffResult.includeEntries)
        updateResult = helper.saveParsedApk(parsedApk, diffResult)
        assertUpdateResultEquals(ParsedApkUpdateResult.success(diffResult), updateResult)

        // removeDexFiles
        logger.info("removeDexFiles")
        val firstDex = originApkEntries.dexFiles.entries.first()
        projectInfo.apkInfo.refreshApkInfoKey()
        val removedDexFiles = mapOf(
            firstDex.key to firstDex.value
        )
        apkEntries = apkEntries.copy(dexFiles = apkEntries.dexFiles - removedDexFiles.keys)
        diffResult = helper.diffApk(apkEntries)
        assertEquals(emptyDiffResult.copy(updatedApkInfos = 1, removedDexFiles = removedDexFiles), diffResult)
        parsedApk = ApkParser().parse(projectInfo.apkFile, diffResult.includeEntries)
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
        parsedApk = ApkParser().parse(projectInfo.apkFile, diffResult.includeEntries)
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
        parsedApk = ApkParser().parse(projectInfo.apkFile, diffResult.includeEntries)
        updateResult = helper.saveParsedApk(parsedApk, diffResult)
        assertUpdateResultEquals(ParsedApkUpdateResult.success(diffResult).copy(updatedClasses = parsedApk.classes.map { it.value.className }), updateResult)
        finalParsedApk = ParsedApk(
            finalParsedApk.apkFile,
            finalParsedApk.classes,
            finalParsedApk.dexFiles + updatedDexFiles,
            finalParsedApk.overlayFiles,
            finalParsedApk.methodRefs,
            finalParsedApk.fieldRefs,
            finalParsedApk.subclassRefs,
        )

        // removeOverlayFiles
        logger.info("removeOverlayFiles")
        val firstOverlayFile = originApkEntries.overlayFiles.entries.first()
        projectInfo.apkInfo.refreshApkInfoKey()
        val deletedOverlayFiles = mapOf(
            firstOverlayFile.key to firstOverlayFile.value
        )
        apkEntries = apkEntries.copy(overlayFiles = apkEntries.overlayFiles - deletedOverlayFiles.keys)
        diffResult = helper.diffApk(apkEntries)
        assertEquals(emptyDiffResult.copy(updatedApkInfos = 1, removedOverlayFiles = deletedOverlayFiles), diffResult)
        parsedApk = ApkParser().parse(projectInfo.apkFile, diffResult.includeEntries)
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
        parsedApk = ApkParser().parse(projectInfo.apkFile, diffResult.includeEntries)
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
        parsedApk = ApkParser().parse(projectInfo.apkFile, diffResult.includeEntries)
        updateResult = helper.saveParsedApk(parsedApk, diffResult)
        assertUpdateResultEquals(ParsedApkUpdateResult.success(diffResult), updateResult)
        finalParsedApk = ParsedApk(
            finalParsedApk.apkFile,
            finalParsedApk.classes,
            finalParsedApk.dexFiles,
            finalParsedApk.overlayFiles + updatedOverlayFiles,
            finalParsedApk.methodRefs,
            finalParsedApk.fieldRefs,
            finalParsedApk.subclassRefs,
        )

//        testGetTableSize(helper, finalParsedApk)
        testGetParsedApk(helper, finalParsedApk)
    }

    @Test
    fun testInsertMultipleApkInfos() {
        // Scenario: insert two APKs; the second APK is copied from the first
        // Expectation: apk_info keys increase to 2 and both APKs are retrievable
        val helper = DeployDataDatabaseSqLiteHelper(dbFile, logger)
        helper.init()

        // Insert first APK (full insert)
        val apkFile1 = projectInfo.apkFile
        val entries1 = ApkParser().parseEntries(apkFile1)
        val diff1 = helper.diffApk(entries1)
        val parsed1 = ApkParser().parse(apkFile1, diff1.includeEntries).filterNotExistsClassesRef()
        helper.saveParsedApk(parsed1, diff1)

        // Prepare second APK by copying and bumping lastModified so key changes
        val apkCopy = File(buildDir, "app-debug-copy.apk")
        apkFile1.copyTo(apkCopy, overwrite = true)
        apkCopy.setLastModified(apkCopy.lastModified() + 2)

        // Insert second APK (full insert)
        val entries2 = ApkParser().parseEntries(apkCopy)
        val diff2 = helper.diffApk(entries2)
        val parsed2 = ApkParser().parse(apkCopy, diff2.includeEntries).filterNotExistsClassesRef()
        helper.saveParsedApkBatch(listOf(parsed1, parsed2), listOf(diff1, diff2))

        // Verify: two keys and both APKs can be read back with matching file
        val keys = helper.getApkInfoKeys()
        assertEquals(2, keys.size)

        val fromDb1 = helper.getParsedApk(apkFile1)
        val fromDb2 = helper.getParsedApk(apkCopy)
        assertNotNull(fromDb1)
        assertNotNull(fromDb2)
        assertEquals(apkFile1, fromDb1.apkFile)
        assertEquals(apkCopy, fromDb2.apkFile)
    }

    @Test
    fun testUpdateApkInfosMultipleApk() {
        // Scenario: mirror single-APK update flows across two APKs
        // Expectation: diff flags and update results follow the same semantics for each APK
        val helper = DeployDataDatabaseSqLiteHelper(dbFile, logger)
        helper.init()

        // APK #1 full insert
        var apkEntries1 = ApkParser().parseEntries(projectInfo.apkFile)
        val originApkEntries1 = apkEntries1
        val emptyDiffResult1 = ParsedApkDiffResult(apkEntries1)
        var diffResult1 = helper.diffApk(apkEntries1)
        var parsedApk1: ParsedApk = ApkParser().parse(projectInfo.apkFile, diffResult1.includeEntries).filterNotExistsClassesRef()
        var finalParsedApk1 = parsedApk1
        var updateResult1 = helper.saveParsedApk(parsedApk1, diffResult1)
        // Expect: addedClasses equals classes of the current parsed APK
        assertUpdateResultEquals(
            ParsedApkUpdateResult.success(diffResult1).copy(addedClasses = parsedApk1.classes.map { it.value.className }.toList()),
            updateResult1
        )

        // Re-diff same entries: expect updatedApkInfos == 0
        diffResult1 = helper.diffApk(apkEntries1)
        assertEquals(0, diffResult1.updatedApkInfos)

        // Refresh key and re-diff: expect updatedApkInfos == 1 and save succeeds
        projectInfo.apkInfo.refreshApkInfoKey()
        diffResult1 = helper.diffApk(apkEntries1)
        assertEquals(1, diffResult1.updatedApkInfos)
        parsedApk1 = ApkParser().parse(projectInfo.apkFile, diffResult1.includeEntries)
        updateResult1 = helper.saveParsedApk(parsedApk1, diffResult1)
        assertUpdateResultEquals(ParsedApkUpdateResult.success(diffResult1), updateResult1)

        // Remove one dex from APK #1: expect removedDexFiles non-empty and removedClasses non-empty after save
        val firstDex1 = originApkEntries1.dexFiles.entries.first()
        projectInfo.apkInfo.refreshApkInfoKey()
        val removedDexFiles1 = mapOf(
            firstDex1.key to firstDex1.value
        )
        apkEntries1 = apkEntries1.copy(dexFiles = apkEntries1.dexFiles - removedDexFiles1.keys)
        diffResult1 = helper.diffApk(apkEntries1)
        assertEquals(1, diffResult1.updatedApkInfos)
        assertEquals(removedDexFiles1.size, diffResult1.removedDexFiles.size)
        parsedApk1 = ApkParser().parse(projectInfo.apkFile, diffResult1.includeEntries)
        updateResult1 = helper.saveParsedApk(parsedApk1, diffResult1)
        assertTrue(updateResult1.removedClasses.isNotEmpty())

        // Add dex back: expect addedClasses equals current parsed classes after save
        val addDexFiles1 = mapOf(
            firstDex1.key to firstDex1.value
        )
        projectInfo.apkInfo.refreshApkInfoKey()
        apkEntries1 = apkEntries1.copy(dexFiles = apkEntries1.dexFiles + addDexFiles1)
        diffResult1 = helper.diffApk(apkEntries1)
        parsedApk1 = ApkParser().parse(projectInfo.apkFile, diffResult1.includeEntries)
        updateResult1 = helper.saveParsedApk(parsedApk1, diffResult1)
        assertUpdateResultEquals(ParsedApkUpdateResult.success(diffResult1).copy(addedClasses = parsedApk1.classes.map { it.value.className}), updateResult1)

        // APK #2: copy APK #1 and insert
        val apkFile2 = File(buildDir, "app-debug-multi.apk")
        projectInfo.apkFile.copyTo(apkFile2, overwrite = true)
        apkFile2.setLastModified(apkFile2.lastModified() + 2)

        // Insert APK #2: expect success
        var apkEntries2 = ApkParser().parseEntries(apkFile2)
        val originApkEntries2 = apkEntries2
        var diffResult2 = helper.diffApk(apkEntries2)
        var parsedApk2: ParsedApk = ApkParser().parse(apkFile2, diffResult2.includeEntries).filterNotExistsClassesRef()
        diffResult1 = helper.diffApk(apkEntries1)
        val updateResultBatch = helper.saveParsedApkBatch(listOf(parsedApk1, parsedApk2), listOf(diffResult1, diffResult2))
        assertUpdateResultEquals(ParsedApkUpdateResult.success(diffResult1), updateResultBatch[0])
        assertUpdateResultEquals(ParsedApkUpdateResult.success(diffResult2).copy(addedClasses = parsedApk2.classes.map { it.value.className}), updateResultBatch[1])

        // Remove one dex from APK #2: expect removedClasses non-empty
        val firstDex2 = originApkEntries2.dexFiles.entries.first()
        apkFile2.setLastModified(apkFile2.lastModified() + 2)
        val removedDexFiles2 = mapOf(
            firstDex2.key to firstDex2.value
        )
        apkEntries2 = apkEntries2.copy(dexFiles = apkEntries2.dexFiles - removedDexFiles2.keys)
        diffResult2 = helper.diffApk(apkEntries2)
        parsedApk2 = ApkParser().parse(apkFile2, diffResult2.includeEntries)
        val updateResult2 = helper.saveParsedApkBatch(listOf(parsedApk1, parsedApk2), listOf(diffResult1, diffResult2))[1]
        assertTrue(updateResult2.removedClasses.isNotEmpty())

        // Verify: multiple keys exist and both APKs can be read back
        val keys = helper.getApkInfoKeys()
        assertEquals(2, keys.size)

        val parsedFromDb1 = helper.getParsedApk(projectInfo.apkFile)
        val parsedFromDb2 = helper.getParsedApk(apkFile2)
        assertNotNull(parsedFromDb1)
        assertNotNull(parsedFromDb2)
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

    private fun ParsedApk.filterNotExistsClassesRef(): ParsedApk {
        return ParsedApk(
            apkFile,
            classes,
            dexFiles,
            overlayFiles,
            methodRefs.filter { classes.containsKey(it.key.owner) },
            fieldRefs.filter { classes.containsKey(it.key.owner) },
            subclassRefs.filter { classes.containsKey(it.key) },
        )
    }

    private fun DeployDataDatabaseSqLiteHelper.saveParsedApk(parsedApk: ParsedApk, diffResult: ParsedApkDiffResult): ParsedApkUpdateResult {
        return saveParsedApkBatch(listOf(parsedApk), listOf(diffResult)).first()
    }
}