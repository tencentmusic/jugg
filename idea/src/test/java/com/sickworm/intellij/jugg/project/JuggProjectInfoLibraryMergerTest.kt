package com.sickworm.intellij.jugg.project

import com.sickworm.intellij.jugg.mock.AssembleAndroidProjectOnce
import com.sickworm.intellij.jugg.mock.assetsDir
import com.sickworm.intellij.jugg.mock.buildDir
import com.sickworm.intellij.jugg.mock.logger
import com.sickworm.intellij.jugg.project.data.LibraryDependency
import com.sickworm.intellij.jugg.project.merger.JuggProjectInfoLibraryMerger
import com.sickworm.intellij.jugg.project.merger.JuggProjectInfoMergeResult
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class JuggProjectInfoLibraryMergerTest {

    private lateinit var baseLibraries: MutableList<LibraryDependency>
    private lateinit var newLibraries: MutableList<LibraryDependency>
    private lateinit var mergeResult: JuggProjectInfoMergeResult
    private lateinit var libraryMerger: JuggProjectInfoLibraryMerger

    private val aNewLibrary = LibraryDependency(
        "test_org.eclipse.dirigible:test_dirigible-commons-config:11.2.5",
        File(assetsDir, "libs/dirigible-commons-config-11.2.5.jar"),
    )
    private val rxjava30 = LibraryDependency(
        "test_rxjava:test_rxjava:3.0.12",
        File(assetsDir, "libs/rxjava-3.0.12.jar"),
    )
    private val rxjava31 = LibraryDependency(
        "test_rxjava:test_rxjava:3.1.8",
        File(assetsDir, "libs/rxjava-3.1.8.jar"),
    )

    init {
        AssembleAndroidProjectOnce.ensure()
    }

    @BeforeTest
    fun setUp() {
        baseLibraries = AssembleAndroidProjectOnce.getProjectInfo().modules["app"]!!.libraryDependencies.toMutableList()
        newLibraries = baseLibraries.toMutableList()
        libraryMerger = JuggProjectInfoLibraryMerger(logger)
        mergeResult = JuggProjectInfoMergeResult.createEmpty().copy(isNeedUpdateDependency = true)
    }

    @Test
    fun testEquals() {
        checkResult()

        baseLibraries.add(rxjava30)
        newLibraries.add(rxjava30)
        checkResult()
    }

    @Test
    fun testAddDuplicate() {
        newLibraries.add(newLibraries.first())
        newLibraries.add(baseLibraries.last())
        checkResult()
    }

    @Test
    fun testAdd() {
        newLibraries.add(aNewLibrary)
        checkResult(expectAdded = listOf(aNewLibrary))
    }

    @Test
    fun testRemove() {
        baseLibraries.add(aNewLibrary)
        // new library, won't remove
        checkResult()
    }

    @Test
    fun testUpdate() {
        baseLibraries.add(rxjava30)
        newLibraries.add(rxjava31)
        checkResult(expectAdded = listOf(rxjava31), expectRemoved = listOf(rxjava30))
    }

    @Test
    fun testNotUpdate() {
        baseLibraries.add(rxjava30)
        newLibraries.add(rxjava31)
        mergeResult = mergeResult.copy(isNeedUpdateDependency = false)
        checkResult()
    }

    @Test
    fun testDeleteUpdate() {
        val deletedRxjava30 = LibraryDependency(
            rxjava30.name,
            File(assetsDir, "libs/rxjava-3.0.12_deleted.jar"),
        )
        baseLibraries.add(deletedRxjava30)
        newLibraries.add(rxjava30)
        checkResult(expectAdded = listOf(rxjava30), expectRemoved = listOf(deletedRxjava30))
    }

    @Test
    fun testMultipleUpdate() {
        val rxjava30v2 = LibraryDependency(
            rxjava30.name,
            File(assetsDir, "libs/kotlin-stdlib-1.3.72.jar"),
        )
        val rxjava31v2 = LibraryDependency(
            rxjava30v2.name,
            File(assetsDir, "libs/dirigible-commons-config-11.2.5.jar"),
        )
        baseLibraries.add(rxjava30)
        baseLibraries.add(rxjava30v2)
        newLibraries.add(rxjava31)
        newLibraries.add(rxjava31v2)
        checkResult(expectAdded = listOf(rxjava31, rxjava31v2), expectRemoved = listOf(rxjava30, rxjava30v2))
    }

    @Test
    fun testMultipleDeleteUpdate() {
        // multiple delete update requires same file name

        val copyFile1 = File(buildDir, "base/libs/kotlin-stdlib-1.3.72.jar") // not exists
        copyFile1.deleteRecursively()
        val rxjava30v2 = LibraryDependency(
            rxjava30.name,
            copyFile1,
        )

        val copyFile = File(buildDir, "new/libs/kotlin-stdlib-1.3.72.jar") // exists
        copyFile.parentFile.mkdirs()
        File(assetsDir, "libs/dirigible-commons-config-11.2.5.jar").copyTo(copyFile, overwrite = true)
        val rxjava30v3 = LibraryDependency(
            rxjava30.name,
            copyFile,
        )
        baseLibraries.add(rxjava30)
        baseLibraries.add(rxjava30v2)
        newLibraries.add(rxjava30)
        newLibraries.add(rxjava30v3)
        checkResult(expectAdded = listOf(rxjava30v3), expectRemoved = listOf(rxjava30v2))
    }


    private fun checkResult(
        expectAdded: List<LibraryDependency> = emptyList(),
        expectRemoved: List<LibraryDependency> = emptyList(),
    ) {
        val old = baseLibraries
        val new = merge(mergeResult.isNeedUpdateDependency)
        val added = new.filter { !old.contains(it) }
        val removed = old.filter { !new.contains(it) }
        assertEquals(expectAdded, added, "expectAdded not matched")
        assertEquals(expectRemoved, removed, "expectRemoved not matched")
    }

    private fun merge(isNeedUpdateLibraryDependency: Boolean): List<LibraryDependency> {
        val result = libraryMerger.mergeLibrariesWithBase(
            "test_module", baseLibraries, newLibraries,
            mergeResult, isNeedUpdateLibraryDependency
        )
        logger.debug("merge result: $mergeResult")
        return result
    }
}