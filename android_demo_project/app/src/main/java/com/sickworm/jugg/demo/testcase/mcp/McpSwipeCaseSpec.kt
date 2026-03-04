package com.sickworm.jugg.demo.testcase.mcp

/**
 * Provides stable labels for swipe validation in MCP interaction tests.
 */
object McpSwipeCaseSpec {

    const val swipeStartLabel: String = "Swipe Start Marker"
    const val swipeEndLabel: String = "Swipe End Marker"

    fun buildScrollableLabels(): List<String> {
        val middleLabels = (1..10).map { index ->
            "Swipe filler row $index"
        }

        return listOf(swipeStartLabel) + middleLabels + listOf(swipeEndLabel)
    }
}
