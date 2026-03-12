package com.sickworm.intellij.jugg.mcp.layout.parser

import com.sickworm.intellij.jugg.mcp.layout.model.FigmaNode
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FigmaJsonParserTest {

    @Test
    fun `parse direct node format with layout`() {
        val json = """
            {
              "id": "1",
              "name": "Root",
              "layout": [0, 0, 375, 812],
              "children": [
                {
                  "id": "2",
                  "name": "Child",
                  "layout": [10, 10, 100, 50]
                }
              ]
            }
        """.trimIndent()

        val tempFile = File.createTempFile("figma_test", ".json")
        tempFile.writeText(json)
        tempFile.deleteOnExit()

        val parser = FigmaJsonParser()
        val result = parser.parse(tempFile.absolutePath)

        assertEquals("1", result.id)
        assertEquals("Root", result.name)
        assertArrayEquals(intArrayOf(0, 0, 375, 812), result.bounds)
        assertEquals(1, result.children?.size)
        assertEquals("2", result.children?.get(0)?.id)
    }

    @Test
    fun `parse direct node format with bounds`() {
        val json = """
            {
              "id": "1",
              "name": "Root",
              "bounds": [0, 0, 375, 812],
              "children": []
            }
        """.trimIndent()

        val tempFile = File.createTempFile("figma_test", ".json")
        tempFile.writeText(json)
        tempFile.deleteOnExit()

        val parser = FigmaJsonParser()
        val result = parser.parse(tempFile.absolutePath)

        assertEquals("1", result.id)
        assertArrayEquals(intArrayOf(0, 0, 375, 812), result.bounds)
    }

    @Test
    fun `parse nodes wrapper format`() {
        val json = """
            {
              "nodes": {
                "34:12170": {
                  "id": "34:12170",
                  "name": "Frame",
                  "layout": [0, 0, 375, 812],
                  "children": []
                }
              }
            }
        """.trimIndent()

        val tempFile = File.createTempFile("figma_test", ".json")
        tempFile.writeText(json)
        tempFile.deleteOnExit()

        val parser = FigmaJsonParser()
        val result = parser.parse(tempFile.absolutePath)

        assertEquals("34:12170", result.id)
        assertEquals("Frame", result.name)
        assertArrayEquals(intArrayOf(0, 0, 375, 812), result.bounds)
    }

    @Test
    fun `parse document wrapper format`() {
        val json = """
            {
              "document": {
                "children": [
                  {
                    "id": "1:1",
                    "name": "Page",
                    "bounds": [0, 0, 1920, 1080],
                    "children": []
                  }
                ]
              }
            }
        """.trimIndent()

        val tempFile = File.createTempFile("figma_test", ".json")
        tempFile.writeText(json)
        tempFile.deleteOnExit()

        val parser = FigmaJsonParser()
        val result = parser.parse(tempFile.absolutePath)

        assertEquals("1:1", result.id)
        assertEquals("Page", result.name)
        assertArrayEquals(intArrayOf(0, 0, 1920, 1080), result.bounds)
    }

    @Test
    fun `layout format converts to bounds correctly`() {
        val json = """
            {
              "id": "1",
              "layout": [10, 20, 100, 50]
            }
        """.trimIndent()

        val tempFile = File.createTempFile("figma_test", ".json")
        tempFile.writeText(json)
        tempFile.deleteOnExit()

        val parser = FigmaJsonParser()
        val result = parser.parse(tempFile.absolutePath)

        // layout [x, y, w, h] -> bounds [left, top, right, bottom]
        assertArrayEquals(intArrayOf(10, 20, 110, 70), result.bounds)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid format throws exception`() {
        val json = """
            {
              "invalid": "format"
            }
        """.trimIndent()

        val tempFile = File.createTempFile("figma_test", ".json")
        tempFile.writeText(json)
        tempFile.deleteOnExit()

        val parser = FigmaJsonParser()
        parser.parse(tempFile.absolutePath)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `missing id throws exception`() {
        val json = """
            {
              "layout": [0, 0, 100, 100]
            }
        """.trimIndent()

        val tempFile = File.createTempFile("figma_test", ".json")
        tempFile.writeText(json)
        tempFile.deleteOnExit()

        val parser = FigmaJsonParser()
        parser.parse(tempFile.absolutePath)
    }
}
