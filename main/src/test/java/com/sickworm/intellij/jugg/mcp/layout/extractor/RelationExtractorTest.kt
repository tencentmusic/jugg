package com.sickworm.intellij.jugg.mcp.layout.extractor

import com.sickworm.intellij.jugg.mcp.layout.model.FigmaNode
import com.sickworm.intellij.jugg.mcp.layout.model.Relation
import org.junit.Assert.*
import org.junit.Test

class RelationExtractorTest {

    @Test
    fun `extract horizontal spacing between adjacent nodes`() {
        val node1 = FigmaNode("1", "Avatar", intArrayOf(0, 0, 100, 100), null)
        val node2 = FigmaNode("2", "Title", intArrayOf(120, 0, 300, 100), null)
        val root = FigmaNode("root", "Root", intArrayOf(0, 0, 300, 100), listOf(node1, node2))

        val extractor = RelationExtractor(1f)
        val relations = extractor.extractRelations(root)

        val spacingRelations = relations.filterIsInstance<Relation.SpacingRelation>()
        assertEquals(1, spacingRelations.size)
        assertEquals("1", spacingRelations[0].element1)
        assertEquals("2", spacingRelations[0].element2)
        assertEquals("x", spacingRelations[0].axis)
        assertEquals(20, spacingRelations[0].expected)
    }

    @Test
    fun `extract vertical spacing between stacked nodes`() {
        val node1 = FigmaNode("1", "Header", intArrayOf(0, 0, 300, 50), null)
        val node2 = FigmaNode("2", "Content", intArrayOf(0, 70, 300, 200), null)
        val root = FigmaNode("root", "Root", intArrayOf(0, 0, 300, 200), listOf(node1, node2))

        val extractor = RelationExtractor(1f)
        val relations = extractor.extractRelations(root)

        val spacingRelations = relations.filterIsInstance<Relation.SpacingRelation>()
        assertTrue(spacingRelations.any { it.axis == "y" && it.expected == 20 })
    }

    @Test
    fun `extract horizontal alignment`() {
        val node1 = FigmaNode("1", "Icon1", intArrayOf(0, 100, 50, 150), null)
        val node2 = FigmaNode("2", "Icon2", intArrayOf(100, 100, 150, 150), null)
        val node3 = FigmaNode("3", "Icon3", intArrayOf(200, 100, 250, 150), null)
        val root = FigmaNode("root", "Root", intArrayOf(0, 0, 300, 200), listOf(node1, node2, node3))

        val extractor = RelationExtractor(1f)
        val relations = extractor.extractRelations(root)

        val alignmentRelations = relations.filterIsInstance<Relation.AlignmentRelation>()
        assertTrue(alignmentRelations.any { it.axis == "y" && it.elements.size == 3 })
    }

    @Test
    fun `handle dpr scaling`() {
        val node1 = FigmaNode("1", "Avatar", intArrayOf(0, 0, 200, 200), null)
        val node2 = FigmaNode("2", "Title", intArrayOf(240, 0, 600, 200), null)
        val root = FigmaNode("root", "Root", intArrayOf(0, 0, 600, 200), listOf(node1, node2))

        val extractor = RelationExtractor(2f)
        val relations = extractor.extractRelations(root)

        val spacingRelations = relations.filterIsInstance<Relation.SpacingRelation>()
        assertEquals(20, spacingRelations[0].expected)
    }

    @Test
    fun `ignore non-adjacent nodes`() {
        val node1 = FigmaNode("1", "Left", intArrayOf(0, 0, 100, 100), null)
        val node2 = FigmaNode("2", "Right", intArrayOf(500, 200, 600, 300), null)
        val root = FigmaNode("root", "Root", intArrayOf(0, 0, 600, 300), listOf(node1, node2))

        val extractor = RelationExtractor(1f)
        val relations = extractor.extractRelations(root)

        val spacingRelations = relations.filterIsInstance<Relation.SpacingRelation>()
        assertTrue(spacingRelations.isEmpty())
    }
}
