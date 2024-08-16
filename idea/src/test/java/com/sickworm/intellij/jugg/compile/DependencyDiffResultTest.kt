package com.sickworm.intellij.jugg.compile

import com.jetbrains.rd.util.first
import com.sickworm.intellij.jugg.compiler.manifest.XmlAndroidManifestInfo
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResult
import com.sickworm.intellij.jugg.project.data.LibraryDependency
import com.sickworm.intellij.jugg.mock.context
import com.sickworm.intellij.jugg.project.data.JuggProjectInfo
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class DependencyDiffResultTest {

    private val fullBuildDependencies = JuggProjectInfo(context.modules)
    private val libraryDependencies = context.modules.first().value.libraryDependencies

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
    fun testRemoveDependency() {
        val removeLibrary = fullBuildDependencies.modules.first().value.libraryDependencies.first()
        val currentBuildDependencies = createBuildDependencies(removedLibraries = listOf(removeLibrary))

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
        val removeManifestLibrary = fullBuildDependencies.modules.first().value.libraryDependencies.find { it.isAndroidManifest }!!
        val removeJarLibrary = fullBuildDependencies.modules.first().value.libraryDependencies.find { it.name == removeManifestLibrary.name }!!
        val addManifestLibrary = removeManifestLibrary.copy(name = "com.sickworm.intellij.jugg:lib:1.0", crc32 = removeManifestLibrary.crc32 + 1)
        val addJarLibrary = removeJarLibrary.copy(name = "com.sickworm.intellij.jugg:lib:1.0", crc32 = removeJarLibrary.crc32 + 1)
        val currentBuildDependencies = createBuildDependencies(
            newLibraries = listOf(addJarLibrary, addManifestLibrary),
            removedLibraries = listOf(removeJarLibrary, removeManifestLibrary),
        )

        val diffResult = DependencyDiffResult.create(currentBuildDependencies, fullBuildDependencies)
        assertEquals(0, diffResult.addedLibraries.size)
        assertEquals(0, diffResult.removedLibraries.size)
        assertEquals(1, diffResult.updatedLibraries.size)

        assertEquals(addManifestLibrary.crc32, diffResult.updatedLibraries.first().dependency!!.libraries.find { it.isAndroidManifest }!!.crc32)
        assertEquals(removeManifestLibrary.crc32, diffResult.updatedLibraries.first().oldDependency!!.libraries.find { it.isAndroidManifest }!!.crc32)
        assertEquals(addJarLibrary.crc32, diffResult.updatedLibraries.first().dependency!!.libraries.find { !it.isAndroidManifest }!!.crc32)
        assertEquals(removeJarLibrary.crc32, diffResult.updatedLibraries.first().oldDependency!!.libraries.find { !it.isAndroidManifest }!!.crc32)
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
    ): JuggProjectInfo {
        var newLibraryDependencies = libraryDependencies.toMutableList()
        newLibraryDependencies.addAll(newLibraries)
        newLibraryDependencies.removeAll(removedLibraries)
        newLibraryDependencies = newLibraryDependencies.filter { depend ->
            updateLibraries.all { it.name.substringBeforeLast(":") != depend.name.substringBeforeLast(":") }
        }.toMutableList()
        newLibraryDependencies.addAll(updateLibraries)

        val newModules = listOf(context.modules.first().value.copy(libraryDependencies = newLibraryDependencies))
        return JuggProjectInfo(newModules.associateBy { it.name })
    }
}