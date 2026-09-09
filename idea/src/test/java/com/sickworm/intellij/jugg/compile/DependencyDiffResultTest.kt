package com.sickworm.intellij.jugg.compile

import com.jetbrains.rd.util.first
import com.sickworm.intellij.jugg.compiler.manifest.XmlAndroidManifestInfo
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResult
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResultSet
import com.sickworm.intellij.jugg.mock.mockModule
import com.sickworm.intellij.jugg.project.info.JuggProjectInfo
import com.sickworm.intellij.jugg.project.info.LibraryDependency
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class DependencyDiffResultTest {

    private val manifestFile = File(
        "src/test/assets/android/MyApplicationIntellij/app/build/intermediates/merged_manifest/debug/AndroidManifest.xml",
    ).absoluteFile
    private val libraryDependencies = listOf(
        LibraryDependency("com.sickworm.intellij.jugg:base:1.0", File("fake_base.jar"), 0L, 1),
        LibraryDependency("com.sickworm.intellij.jugg:android-lib:1.0", manifestFile, 0L, 2),
        LibraryDependency(
            "com.sickworm.intellij.jugg:android-lib:1.0",
            File("src/test/assets/android/MyApplicationIntellij/library1/src/main/res").absoluteFile,
            0L,
            3,
        ),
        LibraryDependency("com.sickworm.intellij.jugg:android-lib:1.0", File("classes.jar"), 0L, 4),
        LibraryDependency("com.sickworm.intellij.jugg:tail:1.0", File("fake_tail.jar"), 0L, 5),
    )
    private val module = mockModule.copy(
        libraryDependencies = libraryDependencies,
        runtimeLibraryDependencies = emptyList(),
    )
    private val fullBuildDependencies = JuggProjectInfo(
        modules = mapOf(module.name to module),
        agpR8Classpath = null,
    )

    @Test
    fun testAddDependency() {
        val newLibrary = LibraryDependency("com.sickworm.intellij.jugg:lib:1.0", File("fake_lib.jar"), 0L, 1)
        val currentBuildDependencies = createBuildDependencies(newLibraries = listOf(newLibrary))

        val diffResult = DependencyDiffResult.create(currentBuildDependencies, fullBuildDependencies)
        assertEquals(1, diffResult.addedLibraries.size)
        assertEquals(0, diffResult.removedLibraries.size)
        assertEquals(0, diffResult.updatedLibraries.size)
    }

    @Test
    fun testAddDependencyMultiple() {
        val newLibrary = LibraryDependency("com.sickworm.intellij.jugg:lib:1.0", File("fake_lib.jar"), 0L, 1)
        val newLibrary2 = LibraryDependency("com.sickworm.intellij.jugg:lib2:1.0", File("fake_lib2.jar"), 0L, 2)
        val currentBuildDependencies = createBuildDependencies(newLibraries = listOf(newLibrary, newLibrary2))

        val diffResult = DependencyDiffResult.create(currentBuildDependencies, fullBuildDependencies)
        assertEquals(2, diffResult.addedLibraries.size)
        assertEquals(0, diffResult.removedLibraries.size)
        assertEquals(0, diffResult.updatedLibraries.size)
    }

    @Test
    fun testAddDependencyDuplicate() {
        val newLibrary = LibraryDependency("com.sickworm.intellij.jugg:lib:1.0", File("fake_lib.jar"), 0L, 1)
        val currentBuildDependencies = createBuildDependencies(newLibraries = listOf(newLibrary, newLibrary))

        val diffResult = DependencyDiffResult.create(currentBuildDependencies, fullBuildDependencies)
        assertEquals(1, diffResult.addedLibraries.size)
        assertEquals(0, diffResult.removedLibraries.size)
        assertEquals(0, diffResult.updatedLibraries.size)
    }

    @Test
    fun legacyFullBuildBaselineIgnoresNewRuntimeDependencies() {
        val runtimeLibrary = LibraryDependency(
            "com.sickworm.intellij.jugg:runtime-lib:1.0",
            File("runtime_lib.jar"),
            0L,
            1,
        )
        val currentModule = module.copy(
            runtimeLibraryDependencies = listOf(runtimeLibrary),
        )
        val currentBuildDependencies = JuggProjectInfo(
            modules = mapOf(currentModule.name to currentModule),
            agpR8Classpath = null,
        )

        val diffResultSet = DependencyDiffResultSet.create(
            currentBuildDependencies,
            fullBuildDependencies,
            fullBuildDependencies,
        )

        assertEquals(0, diffResultSet.diffResult.changedLibraries.size)
        assertEquals(0, diffResultSet.diffResultWithFull.changedLibraries.size)
    }

    @Test
    fun legacyFullBuildBaselineKeepsBothDiffsOnCompileDependencies() {
        val lastRuntimeLibrary = LibraryDependency(
            "com.sickworm.intellij.jugg:runtime-lib:1.0",
            File("runtime_lib_1.jar"),
            0L,
            1,
        )
        val currentRuntimeLibrary = lastRuntimeLibrary.copy(
            name = "com.sickworm.intellij.jugg:runtime-lib:2.0",
            file = File("runtime_lib_2.jar"),
            crc32 = 2,
        )
        val lastBuildDependencies = createBuildDependencies(runtimeLibraries = listOf(lastRuntimeLibrary))
        val currentBuildDependencies = createBuildDependencies(runtimeLibraries = listOf(currentRuntimeLibrary))

        val diffResultSet = DependencyDiffResultSet.create(
            currentBuildDependencies,
            lastBuildDependencies,
            fullBuildDependencies,
        )

        assertEquals(0, diffResultSet.diffResult.changedLibraries.size)
        assertEquals(0, diffResultSet.diffResultWithFull.changedLibraries.size)
    }

    @Test
    fun runtimeAwareFullBuildBaselineDetectsRuntimeDependencyChanges() {
        val oldRuntimeLibrary = LibraryDependency(
            "com.sickworm.intellij.jugg:runtime-lib:1.0",
            File("runtime_lib_1.jar"),
            0L,
            1,
        )
        val newRuntimeLibrary = oldRuntimeLibrary.copy(
            name = "com.sickworm.intellij.jugg:runtime-lib:2.0",
            file = File("runtime_lib_2.jar"),
            crc32 = 2,
        )
        val runtimeFullBuildDependencies = createBuildDependencies(runtimeLibraries = listOf(oldRuntimeLibrary))
        val currentBuildDependencies = createBuildDependencies(runtimeLibraries = listOf(newRuntimeLibrary))

        val diffResultSet = DependencyDiffResultSet.create(
            currentBuildDependencies,
            runtimeFullBuildDependencies,
            runtimeFullBuildDependencies,
        )

        assertEquals(1, diffResultSet.diffResult.updatedLibraries.size)
        assertEquals(1, diffResultSet.diffResultWithFull.updatedLibraries.size)
        assertEquals(newRuntimeLibrary.name, diffResultSet.diffResult.updatedLibraries.single().dependency!!.declaration)
        assertEquals(oldRuntimeLibrary.name, diffResultSet.diffResult.updatedLibraries.single().oldDependency!!.declaration)
    }

    @Test
    fun runtimeAwareFullBuildBaselineDetectsRuntimeDependencyAdditionsAndRemovals() {
        val retainedRuntimeLibrary = LibraryDependency(
            "com.sickworm.intellij.jugg:runtime-retained:1.0",
            File("runtime_retained.jar"),
            0L,
            1,
        )
        val removedRuntimeLibrary = LibraryDependency(
            "com.sickworm.intellij.jugg:runtime-removed:1.0",
            File("runtime_removed.jar"),
            0L,
            1,
        )
        val addedRuntimeLibrary = LibraryDependency(
            "com.sickworm.intellij.jugg:runtime-added:1.0",
            File("runtime_added.jar"),
            0L,
            1,
        )
        val runtimeFullBuildDependencies = createBuildDependencies(
            runtimeLibraries = listOf(retainedRuntimeLibrary, removedRuntimeLibrary),
        )
        val currentBuildDependencies = createBuildDependencies(
            runtimeLibraries = listOf(retainedRuntimeLibrary, addedRuntimeLibrary),
        )

        val diffResultSet = DependencyDiffResultSet.create(
            currentBuildDependencies,
            runtimeFullBuildDependencies,
            runtimeFullBuildDependencies,
        )

        assertEquals(1, diffResultSet.diffResult.addedLibraries.size)
        assertEquals(1, diffResultSet.diffResult.removedLibraries.size)
        assertEquals(1, diffResultSet.diffResultWithFull.addedLibraries.size)
        assertEquals(1, diffResultSet.diffResultWithFull.removedLibraries.size)
    }

    @Test
    fun testCompileAndRuntimeDependencyUsesSingleArtifact() {
        val duplicatedLibrary = libraryDependencies.first()
        val currentModule = module.copy(
            runtimeLibraryDependencies = listOf(duplicatedLibrary),
        )
        val currentBuildDependencies = JuggProjectInfo(
            modules = mapOf(currentModule.name to currentModule),
            agpR8Classpath = null,
        )

        val diffResult = DependencyDiffResult.create(
            currentBuildDependencies,
            fullBuildDependencies,
            includeRuntimeDependencies = true,
        )

        assertEquals(0, diffResult.changedLibraries.size)
    }

    @Test
    fun testRemoveDependency() {
        val removeLibraryName = fullBuildDependencies.modules.first().value.libraryDependencies.first().name
        val removeLibraries = fullBuildDependencies.modules.first().value.libraryDependencies.filter {
            it.name == removeLibraryName
        }
        val currentBuildDependencies = createBuildDependencies(removedLibraries = removeLibraries)

        val diffResult = DependencyDiffResult.create(currentBuildDependencies, fullBuildDependencies)
        assertEquals(0, diffResult.addedLibraries.size)
        assertEquals(1, diffResult.removedLibraries.size)
        assertEquals(0, diffResult.updatedLibraries.size)
    }

    @Test
    fun testRemoveDependencyMultiple() {
        val removeLibraryName = fullBuildDependencies.modules.first().value.libraryDependencies.first().name
        val removeLibraryName2 = fullBuildDependencies.modules.first().value.libraryDependencies.last().name
        val removeLibraries = fullBuildDependencies.modules.first().value.libraryDependencies.filter {
            it.name == removeLibraryName || it.name == removeLibraryName2
        }
        val currentBuildDependencies = createBuildDependencies(removedLibraries = removeLibraries)

        val diffResult = DependencyDiffResult.create(currentBuildDependencies, fullBuildDependencies)
        assertEquals(0, diffResult.addedLibraries.size)
        assertEquals(2, diffResult.removedLibraries.size)
        assertEquals(0, diffResult.updatedLibraries.size)
    }

    @Test
    fun testUpdateDependency() {
        val originLibrary = fullBuildDependencies.modules.first().value.libraryDependencies.first()
        val updateLibrary = originLibrary.copy(name = originLibrary.name.updateVersion("9.9.9"))
        val currentBuildDependencies = createBuildDependencies(updateLibraries = listOf(updateLibrary))

        val diffResult = DependencyDiffResult.create(currentBuildDependencies, fullBuildDependencies)
        assertEquals(0, diffResult.addedLibraries.size)
        assertEquals(0, diffResult.removedLibraries.size)
        assertEquals(1, diffResult.updatedLibraries.size)

        assertEquals(updateLibrary.name, diffResult.updatedLibraries.first().dependency!!.declaration)
        assertEquals(originLibrary.name, diffResult.updatedLibraries.first().oldDependency!!.declaration)
    }

    @Test
    fun testUpdateDependencyMultiple() {
        val originLibrary = fullBuildDependencies.modules.first().value.libraryDependencies.first()
        val updateLibrary = originLibrary.copy(name = originLibrary.name.updateVersion("9.9.9"))
        val originLibrary2 = fullBuildDependencies.modules.first().value.libraryDependencies.last()
        val updateLibrary2 = originLibrary2.copy(name = originLibrary2.name.updateVersion("8.8.8"))
        val currentBuildDependencies = createBuildDependencies(updateLibraries = listOf(updateLibrary, updateLibrary2))

        val diffResult = DependencyDiffResult.create(currentBuildDependencies, fullBuildDependencies)
        assertEquals(0, diffResult.addedLibraries.size)
        assertEquals(0, diffResult.removedLibraries.size)
        assertEquals(2, diffResult.updatedLibraries.size)

        assertEquals(updateLibrary.name, diffResult.updatedLibraries.first().dependency!!.declaration)
        assertEquals(originLibrary.name, diffResult.updatedLibraries.first().oldDependency!!.declaration)
        assertEquals(updateLibrary2.name, diffResult.updatedLibraries.last().dependency!!.declaration)
        assertEquals(originLibrary2.name, diffResult.updatedLibraries.last().oldDependency!!.declaration)
    }

    @Test
    fun testUpdateDependencyContent() {
        val originLibrary = fullBuildDependencies.modules.first().value.libraryDependencies.first()
        val updateLibrary = originLibrary.copy(crc32 = originLibrary.crc32 + 1)
        val currentBuildDependencies = createBuildDependencies(updateLibraries = listOf(updateLibrary))

        val diffResult = DependencyDiffResult.create(currentBuildDependencies, fullBuildDependencies)
        assertEquals(0, diffResult.addedLibraries.size)
        assertEquals(0, diffResult.removedLibraries.size)
        assertEquals(1, diffResult.updatedLibraries.size)

        assertEquals(updateLibrary.crc32, diffResult.updatedLibraries.first().dependency!!.libraries.first().crc32)
        assertEquals(originLibrary.crc32, diffResult.updatedLibraries.first().oldDependency!!.libraries.first().crc32)
    }

    @Test
    fun testUpdateDependencyContentMultiple() {
        val originLibrary = fullBuildDependencies.modules.first().value.libraryDependencies.first()
        val updateLibrary = originLibrary.copy(crc32 = originLibrary.crc32 + 1)
        val originLibrary2 = fullBuildDependencies.modules.first().value.libraryDependencies.last()
        val updateLibrary2 = originLibrary2.copy(crc32 = originLibrary.crc32 + 2)
        val currentBuildDependencies = createBuildDependencies(updateLibraries = listOf(updateLibrary, updateLibrary2))

        val diffResult = DependencyDiffResult.create(currentBuildDependencies, fullBuildDependencies)
        assertEquals(0, diffResult.addedLibraries.size)
        assertEquals(0, diffResult.removedLibraries.size)
        assertEquals(2, diffResult.updatedLibraries.size)

        assertEquals(updateLibrary.crc32, diffResult.updatedLibraries.first().dependency!!.libraries.first().crc32)
        assertEquals(originLibrary.crc32, diffResult.updatedLibraries.first().oldDependency!!.libraries.first().crc32)
        assertEquals(updateLibrary2.crc32, diffResult.updatedLibraries.last().dependency!!.libraries.last().crc32)
        assertEquals(originLibrary2.crc32, diffResult.updatedLibraries.last().oldDependency!!.libraries.last().crc32)
    }

    @Test
    fun testUpdateDependencyWithPackageName() {
        val removeName = fullBuildDependencies.modules.first().value.libraryDependencies
            .find { it.isAndroidManifest }!!.name
        val removeLibraries = fullBuildDependencies.modules.first().value.libraryDependencies
            .filter { it.name == removeName }
        val removeManifestLibrary = removeLibraries.find { it.isAndroidManifest }!!
        val removeJarLibrary = removeLibraries.find { !it.isAndroidManifest && !it.isRes }!!
        val addLibraries = removeLibraries.map {
            it.copy(name = "com.sickworm.intellij.jugg:lib:1.0", crc32 = it.crc32 + 1)
        }
        val addManifestLibrary = addLibraries.find { it.isAndroidManifest }!!
        val addJarLibrary = addLibraries.find { !it.isAndroidManifest && !it.isRes }!!
        val currentBuildDependencies = createBuildDependencies(
            newLibraries = addLibraries,
            removedLibraries = removeLibraries,
        )

        val diffResult = DependencyDiffResult.create(currentBuildDependencies, fullBuildDependencies)
        assertEquals(0, diffResult.addedLibraries.size)
        assertEquals(0, diffResult.removedLibraries.size)
        assertEquals(1, diffResult.updatedLibraries.size)

        assertEquals(addManifestLibrary.crc32, diffResult.updatedLibraries.first().dependency!!.libraries.find { it.isAndroidManifest }!!.crc32)
        assertEquals(removeManifestLibrary.crc32, diffResult.updatedLibraries.first().oldDependency!!.libraries.find { it.isAndroidManifest }!!.crc32)
        assertEquals(addJarLibrary.crc32, diffResult.updatedLibraries.first().dependency!!.libraries.find { !it.isAndroidManifest && !it.isRes }!!.crc32)
        assertEquals(removeJarLibrary.crc32, diffResult.updatedLibraries.first().oldDependency!!.libraries.find { !it.isAndroidManifest && !it.isRes }!!.crc32)
    }

    @Test
    fun testAddDependencyMultipleVersion() {
        val newLibrary = LibraryDependency("com.sickworm.intellij.jugg:lib:1.0", File("fake_lib.jar"), 0L, 1)
        val newLibrary2 = LibraryDependency("com.sickworm.intellij.jugg:lib:1.1", File("fake_lib2.jar"), 0L, 2)
        val newLibrary3 = LibraryDependency("com.sickworm.intellij.jugg:lib:1.2", File("fake_lib3.jar"), 0L, 3)
        val currentBuildDependencies = createBuildDependencies(newLibraries = listOf(newLibrary, newLibrary2, newLibrary3))
        val newFullBuildDependencies = createBuildDependencies(newLibraries = listOf(newLibrary, newLibrary2))

        val diffResult = DependencyDiffResult.create(currentBuildDependencies, newFullBuildDependencies)
        assertEquals(0, diffResult.addedLibraries.size)
        assertEquals(0, diffResult.removedLibraries.size)
        assertEquals(1, diffResult.updatedLibraries.size)
    }

    @Test
    fun testAddDependencyMultipleVersion2() {
        val newLibrary = LibraryDependency("com.sickworm.intellij.jugg:lib:1.0", File("fake_lib.jar"), 0L, 1)
        val newLibrary2 = LibraryDependency("com.sickworm.intellij.jugg:lib:1.1", File("fake_lib2.jar"), 0L, 2)
        val newLibrary3 = LibraryDependency("com.sickworm.intellij.jugg:lib:1.2", File("fake_lib3.jar"), 0L, 3)
        val currentBuildDependencies = createBuildDependencies(newLibraries = listOf(newLibrary, newLibrary3))
        val newFullBuildDependencies = createBuildDependencies(newLibraries = listOf(newLibrary, newLibrary2))

        val diffResult = DependencyDiffResult.create(currentBuildDependencies, newFullBuildDependencies)
        assertEquals(0, diffResult.addedLibraries.size)
        assertEquals(0, diffResult.removedLibraries.size)
        assertEquals(1, diffResult.updatedLibraries.size)
    }

    private fun String.updateVersion(newVersion: String): String {
        val name = this.substringBeforeLast(":")
        return "$name:$newVersion"
    }

    private fun createBuildDependencies(
        newLibraries: List<LibraryDependency> = emptyList(),
        removedLibraries: List<LibraryDependency> = emptyList(),
        updateLibraries: List<LibraryDependency> = emptyList(),
        runtimeLibraries: List<LibraryDependency> = emptyList(),
    ): JuggProjectInfo {
        var newLibraryDependencies = libraryDependencies.toMutableList()
        newLibraryDependencies.addAll(newLibraries)
        newLibraryDependencies.removeAll(removedLibraries)
        newLibraryDependencies = newLibraryDependencies.filter { depend ->
            updateLibraries.all { it.name.substringBeforeLast(":") != depend.name.substringBeforeLast(":") }
        }.toMutableList()
        newLibraryDependencies.addAll(updateLibraries)

        val newModules = listOf(module.copy(
            libraryDependencies = newLibraryDependencies,
            runtimeLibraryDependencies = runtimeLibraries,
        ))
        return JuggProjectInfo(
            modules = newModules.associateBy { it.name },
            agpR8Classpath = null,
        )
    }
}
