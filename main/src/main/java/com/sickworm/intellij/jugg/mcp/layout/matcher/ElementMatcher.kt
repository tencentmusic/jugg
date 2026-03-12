package com.sickworm.intellij.jugg.mcp.layout.matcher

import com.sickworm.intellij.jugg.mcp.layout.model.AndroidNode
import com.sickworm.intellij.jugg.mcp.layout.model.FigmaNode
import kotlin.math.max
import kotlin.math.min

/**
 * Element matcher using IoU
 */
class ElementMatcher {

    fun match(figmaNode: FigmaNode, androidNodes: List<AndroidNode>, figmaScreenSize: IntArray, androidScreenSize: IntArray): MatchResult {
        val normalizedFigma = normalizeBounds(figmaNode.bounds, figmaScreenSize)

        val candidates = androidNodes.map { androidNode ->
            val normalizedAndroid = normalizeBounds(androidNode.bounds, androidScreenSize)
            val score = calculateIoU(normalizedFigma, normalizedAndroid)
            Pair(androidNode, score)
        }.filter { it.second > 0.7f }
          .sortedByDescending { it.second }

        return if (candidates.isNotEmpty()) {
            MatchResult(
                matched = candidates.first().first,
                confidence = candidates.first().second,
                alternatives = candidates.drop(1).take(3).map { it.first }
            )
        } else {
            MatchResult(matched = null, confidence = 0f, alternatives = emptyList())
        }
    }

    private fun calculateIoU(bounds1: IntArray, bounds2: IntArray): Float {
        val (l1, t1, r1, b1) = bounds1
        val (l2, t2, r2, b2) = bounds2

        val intersectLeft = max(l1, l2)
        val intersectTop = max(t1, t2)
        val intersectRight = min(r1, r2)
        val intersectBottom = min(b1, b2)

        if (intersectRight <= intersectLeft || intersectBottom <= intersectTop) {
            return 0f
        }

        val intersectArea = (intersectRight - intersectLeft) * (intersectBottom - intersectTop)
        val area1 = (r1 - l1) * (b1 - t1)
        val area2 = (r2 - l2) * (b2 - t2)
        val unionArea = area1 + area2 - intersectArea

        return intersectArea.toFloat() / unionArea
    }

    private fun normalizeBounds(bounds: IntArray, screenSize: IntArray): IntArray {
        val (screenWidth, screenHeight) = screenSize
        return intArrayOf(
            (bounds[0].toFloat() / screenWidth * 1000).toInt(),
            (bounds[1].toFloat() / screenHeight * 1000).toInt(),
            (bounds[2].toFloat() / screenWidth * 1000).toInt(),
            (bounds[3].toFloat() / screenHeight * 1000).toInt()
        )
    }

    data class MatchResult(
        val matched: AndroidNode?,
        val confidence: Float,
        val alternatives: List<AndroidNode>
    )
}
