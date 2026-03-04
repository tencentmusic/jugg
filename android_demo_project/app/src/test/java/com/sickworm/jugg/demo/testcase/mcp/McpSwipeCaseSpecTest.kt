package com.sickworm.jugg.demo.testcase.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpSwipeCaseSpecTest {

    @Test
    fun scrollableLabelsShouldContainStartAndEndMarkers() {
        val labels = McpSwipeCaseSpec.buildScrollableLabels()

        assertTrue(labels.size >= 12)
        assertEquals(McpSwipeCaseSpec.swipeStartLabel, labels.first())
        assertEquals(McpSwipeCaseSpec.swipeEndLabel, labels.last())
    }

    @Test
    fun scrollableLabelsShouldContainUniqueEndMarkerOnce() {
        val labels = McpSwipeCaseSpec.buildScrollableLabels()

        assertEquals(1, labels.count { it == McpSwipeCaseSpec.swipeEndLabel })
    }
}
