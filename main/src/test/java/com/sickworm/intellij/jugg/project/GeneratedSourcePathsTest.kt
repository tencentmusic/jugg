package com.sickworm.intellij.jugg.project

import com.intellij.openapi.diagnostic.Logger
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeneratedSourcePathsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private val logger = mock(Logger::class.java)

    @Test
    fun `filtered roots preserve originals and refresh changed and removed sources`() {
        val root = temporaryFolder.newFolder("generated")
        val cleanRoot = temporaryFolder.newFolder("clean")
        val copies = temporaryFolder.newFolder("copies")
        val empty = File(root, "example/Parent.java").apply { parentFile.mkdirs(); writeText("") }
        val valid = File(root, "example/Binding.java").apply { writeText("class Binding {}") }
        val resource = File(root, "empty.txt").apply { writeText("") }
        val roots = listOf(root, cleanRoot)
        val filtered = filterEmptyGeneratedJavaSources(roots, copies, logger)
        assertEquals(cleanRoot, filtered[1])
        assertTrue(empty.exists())
        assertFalse(File(filtered[0], "example/Parent.java").exists())
        assertEquals(valid.readText(), File(filtered[0], "example/Binding.java").readText())
        assertTrue(File(filtered[0], resource.name).exists())

        valid.delete()
        File(root, "example/NewBinding.java").writeText("class NewBinding {}")
        val refreshed = filterEmptyGeneratedJavaSources(roots, copies, logger)[0]
        assertFalse(File(refreshed, "example/Binding.java").exists())
        assertTrue(File(refreshed, "example/NewBinding.java").exists())

        empty.writeText("class Parent {}")
        assertEquals(roots, filterEmptyGeneratedJavaSources(roots, copies, logger))
    }

    @Test
    fun `changed root order rebuilds copies without mixing generated files`() {
        val first = temporaryFolder.newFolder("first")
        val second = temporaryFolder.newFolder("second")
        val copies = temporaryFolder.newFolder("copies")
        File(first, "Empty.java").writeText("")
        File(first, "First.java").writeText("class First {}")
        File(second, "Empty.java").writeText("")
        File(second, "Second.java").writeText("class Second {}")
        filterEmptyGeneratedJavaSources(listOf(first, second), copies, logger)

        val reordered = filterEmptyGeneratedJavaSources(listOf(second, first), copies, logger)
        assertEquals(setOf("Second.java"), reordered[0].list()!!.toSet())
        assertEquals(setOf("First.java"), reordered[1].list()!!.toSet())
        assertTrue(File(first, "Empty.java").exists())
        assertTrue(File(second, "Empty.java").exists())
    }

    @Test
    fun `unavailable copy storage falls back to original roots`() {
        val root = temporaryFolder.newFolder("generated")
        File(root, "Empty.java").writeText("")
        File(root, "Binding.java").writeText("class Binding {}")
        val clean = temporaryFolder.newFolder("clean")
        val blockedStorage = temporaryFolder.newFile("blocked")

        assertEquals(listOf(root, clean), filterEmptyGeneratedJavaSources(listOf(root, clean), blockedStorage, logger))
        assertEquals("class Binding {}", File(root, "Binding.java").readText())
        assertTrue(File(root, "Empty.java").exists())
    }

    @Test
    fun `failed source scan preserves that root and filters the remaining roots`() {
        val unreadable = mock(File::class.java)
        org.mockito.Mockito.`when`(unreadable.isDirectory).thenThrow(SecurityException("Access denied"))
        val root = temporaryFolder.newFolder("generated")
        File(root, "Empty.java").writeText("")
        File(root, "Binding.java").writeText("class Binding {}")

        val result = filterEmptyGeneratedJavaSources(listOf(unreadable, root), temporaryFolder.newFolder("copies"), logger)
        assertEquals(unreadable, result[0])
        assertEquals(setOf("Binding.java"), result[1].list()!!.toSet())
    }

}
