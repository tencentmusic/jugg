package com.sickworm.jugg.demo.testcase.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class McpCaseCatalogTest {

    @Test
    fun shouldCoverAllCasesFromTc01ToTc64() {
        val allCaseIds = McpCaseCatalog.allCaseIds()

        assertEquals(64, allCaseIds.size)
        assertEquals("TC-01", allCaseIds.first())
        assertEquals("TC-64", allCaseIds.last())
    }

    @Test
    fun shouldContainExpectedGroupRanges() {
        val groups = McpCaseCatalog.groups

        assertEquals(11, groups.size)
        assertEquals(1, groups[0].groupId)
        assertEquals("TC-01", groups[0].startCaseId)
        assertEquals("TC-04", groups[0].endCaseId)
        assertEquals(7, groups[6].groupId)
        assertEquals("TC-35", groups[6].startCaseId)
        assertEquals("TC-35", groups[6].endCaseId)
        assertEquals(11, groups[10].groupId)
        assertEquals("TC-61", groups[10].startCaseId)
        assertEquals("TC-64", groups[10].endCaseId)
    }

    @Test
    fun eachGroupShouldHaveAscendingCaseIds() {
        val groups = McpCaseCatalog.groups

        assertTrue(groups.all { it.startCaseId <= it.endCaseId })
    }
}
