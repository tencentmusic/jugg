package com.sickworm.intellij.jugg.mcp.layout.matcher

import com.sickworm.intellij.jugg.mcp.layout.model.AndroidNode
import com.sickworm.intellij.jugg.mcp.layout.model.FigmaNode
import org.junit.Assert.*
import org.junit.Test

class ElementMatcherTest {

    @Test
    fun `match identical bounds returns high confidence`() {
        val figmaNode = FigmaNode("1", "Button", intArrayOf(100, 200, 300, 250), null)
        val androidNode = AndroidNode("Button", "btn", "Click", intArrayOf(100, 200, 300, 250))

        val matcher = ElementMatcher()
        val result = matcher.match(figmaNode, listOf(androidNode), intArrayOf(400, 600), intArrayOf(400, 600))

        assertNotNull(result.matched)
        assertTrue(result.confidence > 0.9f)
    }

    @Test
    fun `match with slight offset returns good confidence`() {
        val figmaNode = FigmaNode("1", "Button", intArrayOf(100, 200, 300, 250), null)
        val androidNode = AndroidNode("Button", "btn", "Click", intArrayOf(102, 198, 298, 252))

        val matcher = ElementMatcher()
        val result = matcher.match(figmaNode, listOf(androidNode), intArrayOf(400, 600), intArrayOf(400, 600))

        assertNotNull(result.matched)
        assertTrue(result.confidence > 0.8f)
    }

    @Test
    fun `match across different screen sizes`() {
        val figmaNode = FigmaNode("1", "Button", intArrayOf(100, 200, 300, 250), null)
        val androidNode = AndroidNode("Button", "btn", "Click", intArrayOf(50, 100, 150, 125))

        val matcher = ElementMatcher()
        val result = matcher.match(figmaNode, listOf(androidNode), intArrayOf(400, 600), intArrayOf(200, 300))

        assertNotNull(result.matched)
        assertTrue(result.confidence > 0.7f)
    }

    @Test
    fun `no match returns null with low confidence`() {
        val figmaNode = FigmaNode("1", "Button", intArrayOf(100, 200, 300, 250), null)
        val androidNode = AndroidNode("Button", "btn", "Click", intArrayOf(500, 500, 600, 550))

        val matcher = ElementMatcher()
        val result = matcher.match(figmaNode, listOf(androidNode), intArrayOf(800, 800), intArrayOf(800, 800))

        assertNull(result.matched)
        assertTrue(result.confidence < 0.7f)
    }

    @Test
    fun `select best match from multiple candidates`() {
        val figmaNode = FigmaNode("1", "Button", intArrayOf(100, 200, 300, 250), null)
        val node1 = AndroidNode("Button", "btn1", "Click", intArrayOf(100, 200, 300, 250))
        val node2 = AndroidNode("Button", "btn2", "Click", intArrayOf(105, 205, 305, 255))
        val node3 = AndroidNode("Button", "btn3", "Click", intArrayOf(500, 500, 600, 550))

        val matcher = ElementMatcher()
        val result = matcher.match(figmaNode, listOf(node1, node2, node3), intArrayOf(800, 800), intArrayOf(800, 800))

        assertNotNull(result.matched)
        assertEquals("btn1", result.matched?.id)
    }
}
