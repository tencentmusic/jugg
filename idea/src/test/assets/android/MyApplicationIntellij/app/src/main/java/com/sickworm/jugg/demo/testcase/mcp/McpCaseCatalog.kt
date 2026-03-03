package com.sickworm.jugg.demo.testcase.mcp

/**
 * Catalog of MCP manual test case groups defined in 08_mcp_test_case.md.
 */
object McpCaseCatalog {

    data class Group(
        val groupId: Int,
        val title: String,
        val startCaseId: String,
        val endCaseId: String
    )

    val groups: List<Group> = listOf(
        Group(1, "Remote SSH", "TC-01", "TC-04"),
        Group(2, "Connectivity and Devices", "TC-05", "TC-08"),
        Group(3, "Screenshot and Recording", "TC-09", "TC-15"),
        Group(4, "App Launch and Interaction", "TC-16", "TC-24"),
        Group(5, "Compile and Deploy", "TC-25", "TC-30"),
        Group(6, "Compile Failure", "TC-31", "TC-34"),
        Group(7, "Build Gradle Downgrade", "TC-35", "TC-35"),
        Group(8, "Long Running Compile", "TC-36", "TC-39"),
        Group(9, "No Device Scenario", "TC-40", "TC-54"),
        Group(10, "Device Selection and Errors", "TC-55", "TC-60"),
        Group(11, "End to End Workflow", "TC-61", "TC-64")
    )

    fun allCaseIds(): List<String> {
        return groups.flatMap { group ->
            val start = parseCaseIndex(group.startCaseId)
            val end = parseCaseIndex(group.endCaseId)
            (start..end).map { index -> formatCaseId(index) }
        }
    }

    private fun parseCaseIndex(caseId: String): Int {
        return caseId.removePrefix("TC-").toInt()
    }

    private fun formatCaseId(index: Int): String {
        return String.format("TC-%02d", index)
    }
}
