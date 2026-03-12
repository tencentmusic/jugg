package com.sickworm.intellij.jugg.mcp.layout.verifier

import com.sickworm.intellij.jugg.mcp.layout.model.AndroidNode
import org.junit.Assert.*
import org.junit.Test

class RelationVerifierTest {

    @Test
    fun `verify spacing within tolerance passes`() {
        val node1 = AndroidNode("View", "v1", null, intArrayOf(0, 0, 100, 100))
        val node2 = AndroidNode("View", "v2", null, intArrayOf(118, 0, 200, 100))

        val verifier = RelationVerifier()
        val result = verifier.verifySpacing(node1, node2, 20, "x")

        assertTrue(result.match)
        assertEquals("20dp", result.expected)
        assertEquals("18dp", result.actual)
    }

    @Test
    fun `verify spacing outside tolerance fails`() {
        val node1 = AndroidNode("View", "v1", null, intArrayOf(0, 0, 100, 100))
        val node2 = AndroidNode("View", "v2", null, intArrayOf(130, 0, 200, 100))

        val verifier = RelationVerifier()
        val result = verifier.verifySpacing(node1, node2, 20, "x")

        assertFalse(result.match)
        assertEquals("20dp", result.expected)
        assertEquals("30dp", result.actual)
        assertEquals("10dp", result.diff)
    }

    @Test
    fun `verify vertical spacing`() {
        val node1 = AndroidNode("View", "v1", null, intArrayOf(0, 0, 100, 50))
        val node2 = AndroidNode("View", "v2", null, intArrayOf(0, 62, 100, 150))

        val verifier = RelationVerifier()
        val result = verifier.verifySpacing(node1, node2, 10, "y")

        assertTrue(result.match)
        assertEquals("10dp", result.expected)
        assertEquals("12dp", result.actual)
    }

    @Test
    fun `verify alignment passes when aligned`() {
        val node1 = AndroidNode("View", "v1", null, intArrayOf(0, 100, 50, 150))
        val node2 = AndroidNode("View", "v2", null, intArrayOf(100, 101, 150, 151))
        val node3 = AndroidNode("View", "v3", null, intArrayOf(200, 99, 250, 149))

        val verifier = RelationVerifier()
        val result = verifier.verifyAlignment(listOf(node1, node2, node3), "y")

        assertTrue(result.match)
    }

    @Test
    fun `verify alignment fails when misaligned`() {
        val node1 = AndroidNode("View", "v1", null, intArrayOf(0, 100, 50, 150))
        val node2 = AndroidNode("View", "v2", null, intArrayOf(100, 110, 150, 160))
        val node3 = AndroidNode("View", "v3", null, intArrayOf(200, 90, 250, 140))

        val verifier = RelationVerifier()
        val result = verifier.verifyAlignment(listOf(node1, node2, node3), "y")

        assertFalse(result.match)
    }

    @Test
    fun `verify percent tolerance for large spacing`() {
        val node1 = AndroidNode("View", "v1", null, intArrayOf(0, 0, 100, 100))
        val node2 = AndroidNode("View", "v2", null, intArrayOf(205, 0, 300, 100))

        val verifier = RelationVerifier()
        val result = verifier.verifySpacing(node1, node2, 100, "x")

        assertTrue(result.match)
        assertEquals("100dp", result.expected)
        assertEquals("105dp", result.actual)
    }
}
