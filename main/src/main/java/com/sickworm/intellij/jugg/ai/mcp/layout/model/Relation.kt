package com.sickworm.intellij.jugg.ai.mcp.layout.model

/**
 * Relation between UI elements
 */
sealed class Relation {
    /**
     * Spacing relation between two elements
     */
    data class SpacingRelation(
        val element1: String,
        val element2: String,
        val axis: String,  // "x" or "y"
        val expected: Int  // dp
    ) : Relation()

    /**
     * Alignment relation among multiple elements
     */
    data class AlignmentRelation(
        val elements: List<String>,
        val axis: String  // "x" or "y"
    ) : Relation()
}
