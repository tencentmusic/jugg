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
    private lateinit var baseMultiJarDependency: List<LibraryDependency>
    private lateinit var newMultiJarDependency: List<LibraryDependency>
    private lateinit var baseSingleJarDependency: List<LibraryDependency>
    private lateinit var newSingleJarDependency: List<LibraryDependency>


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
        baseMultiJarDependency = createFakeLibrarySet("test_group:test_artifact:1.2.3", buildDir.resolve("base"))
        newMultiJarDependency = createFakeLibrarySet("test_group:test_artifact:1.2.3", buildDir.resolve("new"))
        baseSingleJarDependency = baseMultiJarDependency.filter { !it.file.path.contains("jars/libs") }
        newSingleJarDependency = newMultiJarDependency.filter { !it.file.path.contains("jars/libs") }
    }

    @Suppress("SameParameterValue")
    private fun createFakeLibrarySet(name: String, baseDir: File): List<LibraryDependency> {
        val jarFile1 = File(baseDir, "jars/classes.jar")
        jarFile1.parentFile.mkdirs()
        jarFile1.writeText(jarFile1.absolutePath)
        val jarDep1 = LibraryDependency(name, jarFile1)

        val jarFile2 = File(baseDir, "jars/libs/classes.jar")
        jarFile2.parentFile.mkdirs()
        jarFile2.writeText(jarFile2.absolutePath)
        val jarDep2 = LibraryDependency(name, jarFile2)

        val manifestFile = File(baseDir, "AndroidManifest.xml")
        manifestFile.writeText(manifestFile.absolutePath)
        val manifestDep = LibraryDependency(name, manifestFile)

        val resFile = File(baseDir, "res")
        resFile.mkdirs()
        val resDep = LibraryDependency(name, resFile)

        return listOf(jarDep1, jarDep2, manifestDep, resDep)
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
        val removedDep = baseMultiJarDependency.find { it.file.path.endsWith("jars/classes.jar") }!!
        removedDep.file.delete()
        baseLibraries.addAll(baseMultiJarDependency)

        val addedDep = newMultiJarDependency.find { it.file.path.endsWith("jars/classes.jar") }!!
        newLibraries.addAll(newMultiJarDependency)

        checkResult(expectAdded = listOf(addedDep), expectRemoved = listOf(removedDep))
    }

    @Test
    fun testSingleJarMissingUpdate() {
        val missingDep = baseSingleJarDependency.find { it.file.path.endsWith("jars/classes.jar") }!!
        baseSingleJarDependency = baseSingleJarDependency.filter { it != missingDep }
        baseLibraries.addAll(baseSingleJarDependency)

        val addedDep = newSingleJarDependency.find { it.file.path.endsWith("jars/classes.jar") }!!
        newLibraries.addAll(newSingleJarDependency)

        checkResult(expectAdded = listOf(addedDep))
    }

    @Test
    fun testMultipleJarPartMissingUpdate() {
        val missingDep = baseMultiJarDependency.find { it.file.path.endsWith("jars/classes.jar") }!!
        baseMultiJarDependency = baseMultiJarDependency.filter { it != missingDep }
        baseLibraries.addAll(baseMultiJarDependency)

        val addedDep = newMultiJarDependency.find { it.file.path.endsWith("jars/classes.jar") }!!
        newLibraries.addAll(newMultiJarDependency)

        checkResult(expectAdded = listOf(addedDep))
    }

    @Test
    fun testMultipleJarPartMissingUpdate2() {
        val missingDep = baseMultiJarDependency.find { it.file.path.endsWith("jars/libs/classes.jar") }!!
        baseMultiJarDependency = baseMultiJarDependency.filter { it != missingDep }
        baseLibraries.addAll(baseMultiJarDependency)

        val addedDep = newMultiJarDependency.find { it.file.path.endsWith("jars/libs/classes.jar") }!!
        newLibraries.addAll(newMultiJarDependency)

        checkResult(expectAdded = listOf(addedDep))
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