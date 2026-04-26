package com.sickworm.intellij.jugg.ai.mcp.layout.verifier

import com.sickworm.intellij.jugg.ai.mcp.layout.model.AndroidNode
import com.sickworm.intellij.jugg.ai.mcp.layout.model.VerifyResult
import kotlin.math.abs

/**
 * Relation verifier with fixed tolerance
 */
class RelationVerifier {

    companion object {
        const val TOLERANCE_DP = 2
        const val TOLERANCE_PERCENT = 0.05f
    }

    fun verifySpacing(element1: AndroidNode, element2: AndroidNode, expected: Int, axis: String): VerifyResult {
        val actual = when (axis) {
            "x" -> element2.bounds[0] - element1.bounds[2]
            "y" -> element2.bounds[1] - element1.bounds[3]
            else -> 0
        }

        val match = isWithinTolerance(actual - expected, expected)
        return VerifyResult(
            match = match,
            expected = "${expected}dp",
            actual = "${actual}dp",
            diff = "${actual - expected}dp"
        )
    }

    fun verifyAlignment(elements: List<AndroidNode>, axis: String): VerifyResult {
        val centers = elements.map { node ->
            when (axis) {
                "x" -> (node.bounds[0] + node.bounds[2]) / 2
                "y" -> (node.bounds[1] + node.bounds[3]) / 2
                else -> 0
            }
        }

        val maxDiff = (centers.maxOrNull() ?: 0) - (centers.minOrNull() ?: 0)
        val match = maxDiff <= TOLERANCE_DP

        return VerifyResult(
            match = match,
            expected = "aligned",
            actual = "center${axis.uppercase()}: $centers, maxDiff: ${maxDiff}dp"
        )
    }

    private fun isWithinTolerance(diff: Int, expected: Int): Boolean {
        val absDiff = abs(diff)
        val percentDiff = if (expected != 0) absDiff.toFloat() / expected else 0f
        return absDiff <= TOLERANCE_DP || percentDiff <= TOLERANCE_PERCENT
    }
}
