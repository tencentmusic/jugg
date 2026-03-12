package com.sickworm.intellij.jugg.mcp.layout.extractor

import com.sickworm.intellij.jugg.mcp.layout.model.FigmaNode
import com.sickworm.intellij.jugg.mcp.layout.model.Relation
import kotlin.math.abs

/**
 * Extract relations from Figma JSON
 */
class RelationExtractor(private val dpr: Float) {

    fun extractRelations(figmaJson: FigmaNode): List<Relation> {
        val relations = mutableListOf<Relation>()
        val nodes = flattenNodes(figmaJson)

        relations.addAll(extractSpacingRelations(nodes))
        relations.addAll(extractAlignmentRelations(nodes))

        return relations
    }

    private fun extractSpacingRelations(nodes: List<FigmaNode>): List<Relation.SpacingRelation> {
        val relations = mutableListOf<Relation.SpacingRelation>()

        for (i in 0 until nodes.size - 1) {
            val node1 = nodes[i]
            val node2 = nodes[i + 1]

            // Horizontal spacing
            if (isHorizontallyAdjacent(node1, node2)) {
                val spacing = ((node2.bounds[0] - node1.bounds[2]) / dpr).toInt()
                relations.add(Relation.SpacingRelation(
                    element1 = node1.id,
                    element2 = node2.id,
                    axis = "x",
                    expected = spacing
                ))
            }

            // Vertical spacing
            if (isVerticallyAdjacent(node1, node2)) {
                val spacing = ((node2.bounds[1] - node1.bounds[3]) / dpr).toInt()
                relations.add(Relation.SpacingRelation(
                    element1 = node1.id,
                    element2 = node2.id,
                    axis = "y",
                    expected = spacing
                ))
            }
        }

        return relations
    }

    private fun extractAlignmentRelations(nodes: List<FigmaNode>): List<Relation.AlignmentRelation> {
        val relations = mutableListOf<Relation.AlignmentRelation>()
        val tolerance = (5 * dpr).toInt()

        // Group by Y coordinate (horizontal alignment)
        val yGroups = nodes.groupBy { (it.bounds[1] / tolerance) * tolerance }
        for (group in yGroups.values) {
            if (group.size > 1) {
                relations.add(Relation.AlignmentRelation(
                    elements = group.map { it.id },
                    axis = "y"
                ))
            }
        }

        // Group by X coordinate (vertical alignment)
        val xGroups = nodes.groupBy { (it.bounds[0] / tolerance) * tolerance }
        for (group in xGroups.values) {
            if (group.size > 1) {
                relations.add(Relation.AlignmentRelation(
                    elements = group.map { it.id },
                    axis = "x"
                ))
            }
        }

        return relations
    }

    private fun isHorizontallyAdjacent(node1: FigmaNode, node2: FigmaNode): Boolean {
        val tolerance = (20 * dpr).toInt()
        val y1 = node1.bounds[1]
        val y2 = node2.bounds[1]
        val x1Right = node1.bounds[2]
        val x2Left = node2.bounds[0]

        return abs(y1 - y2) < tolerance && x2Left >= x1Right
    }

    private fun isVerticallyAdjacent(node1: FigmaNode, node2: FigmaNode): Boolean {
        val tolerance = (20 * dpr).toInt()
        val x1 = node1.bounds[0]
        val x2 = node2.bounds[0]
        val y1Bottom = node1.bounds[3]
        val y2Top = node2.bounds[1]

        return abs(x1 - x2) < tolerance && y2Top >= y1Bottom
    }

    private fun flattenNodes(root: FigmaNode): List<FigmaNode> {
        val result = mutableListOf<FigmaNode>()
        fun traverse(node: FigmaNode) {
            result.add(node)
            node.children?.forEach { traverse(it) }
        }
        traverse(root)
        return result
    }
}
