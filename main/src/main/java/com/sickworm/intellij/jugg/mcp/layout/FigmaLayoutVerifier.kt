package com.sickworm.intellij.jugg.mcp.layout

import com.sickworm.intellij.jugg.mcp.layout.extractor.RelationExtractor
import com.sickworm.intellij.jugg.mcp.layout.matcher.ElementMatcher
import com.sickworm.intellij.jugg.mcp.layout.model.AndroidNode
import com.sickworm.intellij.jugg.mcp.layout.model.Relation
import com.sickworm.intellij.jugg.mcp.layout.model.VerifyResult
import com.sickworm.intellij.jugg.mcp.layout.parser.FigmaJsonParser
import com.sickworm.intellij.jugg.mcp.layout.verifier.RelationVerifier

/**
 * Main entry point for Figma layout verification
 */
class FigmaLayoutVerifier(private val dpr: Float = 1f) {

    private val parser = FigmaJsonParser()
    private val extractor = RelationExtractor(dpr)
    private val matcher = ElementMatcher()
    private val verifier = RelationVerifier()

    fun verify(
        figmaJsonPath: String,
        androidNodes: List<AndroidNode>,
        figmaScreenSize: IntArray,
        androidScreenSize: IntArray
    ): VerificationReport {
        // Parse Figma JSON
        val figmaRoot = parser.parse(figmaJsonPath)
        val figmaNodes = parser.flattenNodes(figmaRoot)

        // Extract relations
        val relations = extractor.extractRelations(figmaRoot)

        // Match elements and verify
        val results = mutableListOf<RelationResult>()

        for (relation in relations) {
            when (relation) {
                is Relation.SpacingRelation -> {
                    val figma1 = figmaNodes.find { it.id == relation.element1 } ?: continue
                    val figma2 = figmaNodes.find { it.id == relation.element2 } ?: continue

                    val match1 = matcher.match(figma1, androidNodes, figmaScreenSize, androidScreenSize)
                    val match2 = matcher.match(figma2, androidNodes, figmaScreenSize, androidScreenSize)

                    if (match1.matched != null && match2.matched != null) {
                        val verifyResult = verifier.verifySpacing(
                            match1.matched,
                            match2.matched,
                            relation.expected,
                            relation.axis
                        )
                        results.add(RelationResult(
                            type = "spacing",
                            description = "${figma1.name} -> ${figma2.name} (${relation.axis})",
                            result = verifyResult
                        ))
                    }
                }
                is Relation.AlignmentRelation -> {
                    val matchedNodes = relation.elements.mapNotNull { id ->
                        val figmaNode = figmaNodes.find { it.id == id } ?: return@mapNotNull null
                        matcher.match(figmaNode, androidNodes, figmaScreenSize, androidScreenSize).matched
                    }

                    if (matchedNodes.size >= 2) {
                        val verifyResult = verifier.verifyAlignment(matchedNodes, relation.axis)
                        results.add(RelationResult(
                            type = "alignment",
                            description = "Align ${relation.elements.size} elements on ${relation.axis}",
                            result = verifyResult
                        ))
                    }
                }
            }
        }

        val passed = results.count { it.result.match }
        val failed = results.count { !it.result.match }

        return VerificationReport(
            total = results.size,
            passed = passed,
            failed = failed,
            results = results
        )
    }

    data class RelationResult(
        val type: String,
        val description: String,
        val result: VerifyResult
    )

    data class VerificationReport(
        val total: Int,
        val passed: Int,
        val failed: Int,
        val results: List<RelationResult>
    )
}
