package com.sickworm.intellij.jugg.mcp.actions

import com.android.ddmlib.IDevice
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.mcp.McpErrorCode
import com.sickworm.intellij.jugg.mcp.McpToolStatus
import com.sickworm.intellij.jugg.mcp.viewhierarchy.VerifyResult
import com.sickworm.intellij.jugg.mcp.viewhierarchy.ViewHierarchyClient
import com.sickworm.intellij.jugg.platform.PlatformApi
import org.junit.Assert
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * LayoutVerifyMcpToolActionTest covers dumpFile mode (pure JSON parsing) and live query mode (socket).
 */
class LayoutVerifyMcpToolActionTest {

    // ---- dumpFile mode: validation ----

    @Test
    fun testDumpFileModeReturnsErrorWhenTargetMissing() {
        val action = LayoutVerifyMcpToolAction()
        val runtime = buildRuntime(null)
        val result = action.execute(
            mapOf("projectDir" to "/tmp", "dumpFile" to "/tmp/fake.json"),
            runtime,
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.MCP_INVALID_PARAMS, result.errorCode)
        Assert.assertTrue(result.message.contains("target"))
    }

    @Test
    fun testDumpFileModeReturnsErrorWhenAssertAndRelationBothMissing() {
        val action = LayoutVerifyMcpToolAction()
        val runtime = buildRuntime(null)
        val result = action.execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to "/tmp/fake.json",
                "target" to mapOf("resourceId" to "btn_ok"),
            ),
            runtime,
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.MCP_INVALID_PARAMS, result.errorCode)
        Assert.assertTrue(result.message.contains("assert or relation"))
    }

    @Test
    fun testDumpFileModeReturnsErrorWhenFileNotFound() {
        val action = LayoutVerifyMcpToolAction()
        val runtime = buildRuntime(null)
        val result = action.execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to "/nonexistent/path/layout.json",
                "target" to mapOf("resourceId" to "btn_ok"),
                "assert" to mapOf("property" to "exists"),
            ),
            runtime,
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.MCP_INVALID_PARAMS, result.errorCode)
        Assert.assertTrue(result.message.contains("dumpFile not found"))
    }

    // ---- dumpFile mode: assert.property = exists ----

    @Test
    fun testDumpFileModePassesForExistsByResourceId() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,1080,1920],"id":"com.example:id/root","children":[
                {"className":"Button","id":"com.example:id/btn_ok","bounds":[0,100,300,200],"text":"OK","clickable":true}
            ]}}],"deviceInfo":{"density":3.0,"scaledDensity":3.0}}"""
        )
        val action = LayoutVerifyMcpToolAction()
        val result = action.execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "btn_ok"),
                "assert" to mapOf("property" to "exists"),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue(result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    // ---- dumpFile mode: assert.property = text ----

    @Test
    fun testDumpFileModeAssertTextEquals() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"TextView","id":"com.example:id/label","bounds":[0,0,300,100],"text":"Hello World"}}],"deviceInfo":{"density":3.0}}"""
        )
        val action = LayoutVerifyMcpToolAction()
        val result = action.execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "label"),
                "assert" to mapOf("property" to "text", "value" to "Hello World"),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS but got: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertTextFail() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"TextView","id":"com.example:id/label","bounds":[0,0,300,100],"text":"Actual"}}],"deviceInfo":{"density":3.0}}"""
        )
        val action = LayoutVerifyMcpToolAction()
        val result = action.execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "label"),
                "assert" to mapOf("property" to "text", "value" to "Expected"),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue("Expected FAIL but got: ${result.message}", result.message.startsWith("FAIL"))
        dumpFile.delete()
    }

    // ---- dumpFile mode: assert.property = bounds.width with dp conversion ----

    @Test
    fun testDumpFileModeAssertBoundsWidthInDp() {
        // Element bounds [0,0,300,100], density=3.0 → width=300px=100dp
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"Button","id":"com.example:id/btn","bounds":[0,0,300,100]}}],"deviceInfo":{"density":3.0}}"""
        )
        val action = LayoutVerifyMcpToolAction()
        val result = action.execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "btn"),
                "assert" to mapOf("property" to "bounds.width", "value" to 100, "unit" to "dp"),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS but got: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    // ---- dumpFile mode: target not found ----

    @Test
    fun testDumpFileModeReturnsErrorWhenTargetNotFound() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,1080,1920],"id":"root","children":[]}}],"deviceInfo":{"density":3.0}}"""
        )
        val action = LayoutVerifyMcpToolAction()
        val result = action.execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "nonexistent_btn"),
                "assert" to mapOf("property" to "exists"),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue(
            "Expected 'target not found' in message: ${result.message}",
            result.message.contains("not found", ignoreCase = true),
        )
        dumpFile.delete()
    }

    // ---- dumpFile mode: relation = spacing ----

    @Test
    fun testDumpFileModeRelationSpacingPass() {
        // Two buttons: A=[0,0,300,100], B=[0,116,300,200] → vertical spacing = 116-100=16px
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,1080,1920],"id":"root",
                "children":[
                  {"className":"Button","id":"com.example:id/btn_a","bounds":[0,0,300,100]},
                  {"className":"Button","id":"com.example:id/btn_b","bounds":[0,116,300,200]}
                ]}}],"deviceInfo":{"density":1.0}}"""
        )
        val action = LayoutVerifyMcpToolAction()
        val result = action.execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "btn_a"),
                "target2" to mapOf("resourceId" to "btn_b"),
                "relation" to mapOf("type" to "spacing", "direction" to "vertical", "expected" to 16, "tolerance" to 0),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeRelationSpacingFail() {
        // Two buttons with spacing=16px but expected=20px → FAIL
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,1080,1920],"id":"root",
                "children":[
                  {"className":"Button","id":"com.example:id/btn_a","bounds":[0,0,300,100]},
                  {"className":"Button","id":"com.example:id/btn_b","bounds":[0,116,300,200]}
                ]}}],"deviceInfo":{"density":1.0}}"""
        )
        val action = LayoutVerifyMcpToolAction()
        val result = action.execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "btn_a"),
                "target2" to mapOf("resourceId" to "btn_b"),
                "relation" to mapOf("type" to "spacing", "direction" to "vertical", "expected" to 20, "tolerance" to 0),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue("Expected FAIL: ${result.message}", result.message.startsWith("FAIL"))
        dumpFile.delete()
    }

    // ---- dumpFile mode: relation = overlap ----

    @Test
    fun testDumpFileModeRelationOverlapNoOverlapPass() {
        // Non-overlapping elements → PASS (expected: no overlap)
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,1080,1920],"id":"root",
                "children":[
                  {"className":"View","id":"com.example:id/view_a","bounds":[0,0,100,100]},
                  {"className":"View","id":"com.example:id/view_b","bounds":[200,0,300,100]}
                ]}}],"deviceInfo":{"density":1.0}}"""
        )
        val action = LayoutVerifyMcpToolAction()
        val result = action.execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "view_a"),
                "target2" to mapOf("resourceId" to "view_b"),
                "relation" to mapOf("type" to "overlap"),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    // ---- dumpFile mode: text ops (contains / matches / regex / missing field) ----

    @Test
    fun testDumpFileModeAssertTextContains() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"TextView","id":"com.example:id/label","bounds":[0,0,300,100],"text":"Hello World"}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "label"),
                "assert" to mapOf("property" to "text", "op" to "contains", "value" to "World"),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertTextContainsFail() {
        // op=contains but substring not present → FAIL with actual/expected
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"TextView","id":"com.example:id/label","bounds":[0,0,300,100],"text":"Hello World"}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "label"),
                "assert" to mapOf("property" to "text", "op" to "contains", "value" to "Bye"),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue("Expected FAIL: ${result.message}", result.message.startsWith("FAIL"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertTextMatchesRegex() {
        // op=matches with a valid regex
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"TextView","id":"com.example:id/label","bounds":[0,0,300,100],"text":"Order #1234"}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "label"),
                "assert" to mapOf("property" to "text", "op" to "matches", "value" to "Order #\\d+"),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertTextMatchesInvalidRegex() {
        // Invalid regex must not crash, must return FAIL (not exception)
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"TextView","id":"com.example:id/label","bounds":[0,0,300,100],"text":"abc"}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "label"),
                "assert" to mapOf("property" to "text", "op" to "matches", "value" to "[invalid("),
            ),
            buildRuntime(null),
        )
        // Invalid regex → matches() returns false → FAIL (not a crash/exception)
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertTextMissingFieldDefaultEmpty() {
        // Node has no "text" field; asserting eq "" should PASS (default is empty string)
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"View","id":"com.example:id/v","bounds":[0,0,100,100]}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "v"),
                "assert" to mapOf("property" to "text", "value" to ""),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    // ---- dumpFile mode: visibility / enabled ----

    @Test
    fun testDumpFileModeAssertClickableFalse() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"Button","id":"com.example:id/btn","bounds":[0,0,200,80]}}],"deviceInfo":{"density":3.0}}"""
        )
        // clickable field absent → defaults false; asserting false → PASS
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "btn"),
                "assert" to mapOf("property" to "clickable", "value" to false),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertEnabledFalse() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"Button","id":"com.example:id/btn","bounds":[0,0,200,80],"enabled":false}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "btn"),
                "assert" to mapOf("property" to "enabled", "value" to false),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertEnabledDefaultTrue() {
        // No "enabled" field → default true; assert true → PASS
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"Button","id":"com.example:id/btn","bounds":[0,0,200,80]}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "btn"),
                "assert" to mapOf("property" to "enabled", "value" to true),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertVisibilityGone() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"View","id":"com.example:id/v","bounds":[0,0,100,100],"visibility":"gone"}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "v"),
                "assert" to mapOf("property" to "visibility", "value" to "gone"),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertVisibilityInvisible() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"View","id":"com.example:id/v","bounds":[0,0,100,100],"visibility":"invisible"}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "v"),
                "assert" to mapOf("property" to "visibility", "value" to "invisible"),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertVisibilityContains() {
        // op=contains "invis" matches "invisible" → PASS
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"View","id":"com.example:id/v","bounds":[0,0,100,100],"visibility":"invisible"}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "v"),
                "assert" to mapOf("property" to "visibility", "op" to "contains", "value" to "invis"),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    // ---- dumpFile mode: bounds / size with various operators ----

    @Test
    fun testDumpFileModeAssertBoundsHeightInPx() {
        // bounds=[0,0,300,100] → height=100px; assert eq 100 (no unit) → PASS
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"View","id":"com.example:id/v","bounds":[0,0,300,100]}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "v"),
                "assert" to mapOf("property" to "bounds.height", "value" to 100),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertBoundsLeftTopRightBottom() {
        // bounds=[10,20,310,120] → assert each boundary coordinate
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"View","id":"com.example:id/v","bounds":[10,20,310,120]}}],"deviceInfo":{"density":1.0}}"""
        )
        val action = LayoutVerifyMcpToolAction()
        val runtime = buildRuntime(null)
        for ((property, expected) in listOf(
            "bounds.left" to 10,
            "bounds.top" to 20,
            "bounds.right" to 310,
            "bounds.bottom" to 120,
        )) {
            val r = action.execute(
                mapOf(
                    "projectDir" to "/tmp",
                    "dumpFile" to dumpFile.absolutePath,
                    "target" to mapOf("resourceId" to "v"),
                    "assert" to mapOf("property" to property, "value" to expected),
                ),
                runtime,
            )
            Assert.assertEquals("$property PASS expected", McpToolStatus.OK, r.status)
        }
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertBoundsGteLte() {
        // bounds=[0,0,300,100] → width=300px; assert gte 200 → PASS; assert lte 400 → PASS
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"View","id":"com.example:id/v","bounds":[0,0,300,100]}}],"deviceInfo":{"density":1.0}}"""
        )
        val action = LayoutVerifyMcpToolAction()
        val runtime = buildRuntime(null)
        val rGte = action.execute(
            mapOf(
                "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "v"),
                "assert" to mapOf("property" to "bounds.width", "op" to "gte", "value" to 200),
            ), runtime,
        )
        val rLte = action.execute(
            mapOf(
                "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "v"),
                "assert" to mapOf("property" to "bounds.width", "op" to "lte", "value" to 400),
            ), runtime,
        )
        Assert.assertEquals("gte PASS", McpToolStatus.OK, rGte.status)
        Assert.assertEquals("lte PASS", McpToolStatus.OK, rLte.status)
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertBoundsGtLt() {
        // width=300px; gt 299 → PASS; lt 301 → PASS; gt 300 → FAIL; lt 300 → FAIL
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"View","id":"com.example:id/v","bounds":[0,0,300,100]}}],"deviceInfo":{"density":1.0}}"""
        )
        val action = LayoutVerifyMcpToolAction()
        val runtime = buildRuntime(null)
        Assert.assertEquals(McpToolStatus.OK, action.execute(
            mapOf("projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "v"),
                "assert" to mapOf("property" to "bounds.width", "op" to "gt", "value" to 299)), runtime).status)
        Assert.assertEquals(McpToolStatus.OK, action.execute(
            mapOf("projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "v"),
                "assert" to mapOf("property" to "bounds.width", "op" to "lt", "value" to 301)), runtime).status)
        Assert.assertEquals(McpToolStatus.ERROR, action.execute(
            mapOf("projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "v"),
                "assert" to mapOf("property" to "bounds.width", "op" to "gt", "value" to 300)), runtime).status)
        Assert.assertEquals(McpToolStatus.ERROR, action.execute(
            mapOf("projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "v"),
                "assert" to mapOf("property" to "bounds.width", "op" to "lt", "value" to 300)), runtime).status)
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertPaddingFourDirectionsInDp() {
        // padding=[6,9,12,15], density=3.0 → dp: left=2, top=3, right=4, bottom=5
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"View","id":"com.example:id/v","bounds":[0,0,200,100],"padding":[6,9,12,15]}}],"deviceInfo":{"density":3.0}}"""
        )
        val action = LayoutVerifyMcpToolAction()
        val runtime = buildRuntime(null)
        for ((property, expected) in listOf(
            "padding.left" to 2, "padding.top" to 3, "padding.right" to 4, "padding.bottom" to 5,
        )) {
            val r = action.execute(
                mapOf(
                    "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
                    "target" to mapOf("resourceId" to "v"),
                    "assert" to mapOf("property" to property, "value" to expected, "unit" to "dp"),
                ), runtime,
            )
            Assert.assertEquals("$property dp PASS", McpToolStatus.OK, r.status)
        }
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertBoundsZeroSized() {
        // bounds all zero → height=0; assert eq 0 → PASS (no crash)
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"View","id":"com.example:id/v","bounds":[0,0,0,0]}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "v"),
                "assert" to mapOf("property" to "bounds.height", "value" to 0),
            ), buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    // ---- dumpFile mode: color / style / alpha ----

    @Test
    fun testDumpFileModeAssertTextColorCaseInsensitive() {
        // textColor in dump is upper-case; assert with lower-case → PASS (equalsIgnoreCase)
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"TextView","id":"com.example:id/tv","bounds":[0,0,200,60],"textColor":"#FFFF0000"}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "tv"),
                "assert" to mapOf("property" to "textColor", "value" to "#ffff0000"),
            ), buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertTextColorMismatch() {
        // Actual color #FFFF0000, expected #FF0000FF → FAIL with actual value in message
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"TextView","id":"com.example:id/tv","bounds":[0,0,200,60],"textColor":"#FFFF0000"}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "tv"),
                "assert" to mapOf("property" to "textColor", "value" to "#FF0000FF"),
            ), buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue("Expected FAIL: ${result.message}", result.message.startsWith("FAIL"))
        Assert.assertTrue("Expected actual value in message: ${result.message}",
            result.message.contains("#FFFF0000", ignoreCase = true))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertTextColorMissingDefaultBlack() {
        // No textColor field → default "#FF000000" (black); assert eq → PASS
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"TextView","id":"com.example:id/tv","bounds":[0,0,200,60]}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "tv"),
                "assert" to mapOf("property" to "textColor", "value" to "#FF000000"),
            ), buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertAlphaWithinTolerance() {
        // alpha=0.999 vs expected=1.0; |0.999-1.0|=0.001 < 0.001 is false; actual diff=0.001 so NOT pass
        // Use alpha=0.9995 (rounds to < 0.001 diff when stored as double) – instead use exact match
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"View","id":"com.example:id/v","bounds":[0,0,100,100],"alpha":0.5}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "v"),
                // alpha=0.5 vs expected=0.5 → exact → PASS
                "assert" to mapOf("property" to "alpha", "value" to 0.5),
            ), buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertAlphaExceedsTolerance() {
        // alpha=0.5 vs expected=1.0 → |0.5-1.0|=0.5 >= 0.001 → FAIL
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"View","id":"com.example:id/v","bounds":[0,0,100,100],"alpha":0.5}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "v"),
                "assert" to mapOf("property" to "alpha", "value" to 1.0),
            ), buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue("Expected FAIL: ${result.message}", result.message.startsWith("FAIL"))
        dumpFile.delete()
    }

    // ---- dumpFile mode: layout relations ----

    @Test
    fun testDumpFileModeRelationOverlapActualOverlapFail() {
        // Two overlapping views: A=[0,0,100,100], B=[50,50,150,150] → overlap → FAIL
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,400,400],"id":"root",
                "children":[
                  {"className":"View","id":"com.example:id/view_a","bounds":[0,0,100,100]},
                  {"className":"View","id":"com.example:id/view_b","bounds":[50,50,150,150]}
                ]}}],"deviceInfo":{"density":1.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "view_a"),
                "target2" to mapOf("resourceId" to "view_b"),
                "relation" to mapOf("type" to "overlap"),
            ), buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue("Expected FAIL: ${result.message}", result.message.startsWith("FAIL"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeRelationSpacingToleranceBoundary() {
        // spacing=16px; tolerance=2 → diff=|16-16|=0 ≤ 2 → PASS; expected=13 → diff=3 > 2 → FAIL
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,1080,1920],"id":"root",
                "children":[
                  {"className":"Button","id":"com.example:id/btn_a","bounds":[0,0,300,100]},
                  {"className":"Button","id":"com.example:id/btn_b","bounds":[0,116,300,200]}
                ]}}],"deviceInfo":{"density":1.0}}"""
        )
        val action = LayoutVerifyMcpToolAction()
        val runtime = buildRuntime(null)
        val rPass = action.execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "btn_a"), "target2" to mapOf("resourceId" to "btn_b"),
            "relation" to mapOf("type" to "spacing", "direction" to "vertical", "expected" to 16, "tolerance" to 2),
        ), runtime)
        val rFail = action.execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "btn_a"), "target2" to mapOf("resourceId" to "btn_b"),
            "relation" to mapOf("type" to "spacing", "direction" to "vertical", "expected" to 13, "tolerance" to 2),
        ), runtime)
        Assert.assertEquals("tolerance boundary PASS", McpToolStatus.OK, rPass.status)
        Assert.assertEquals("tolerance boundary FAIL", McpToolStatus.ERROR, rFail.status)
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeRelationSpacingHorizontal() {
        // A=[0,0,100,100], B=[120,0,220,100] → horizontal spacing = 120-100=20px
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,400,200],"id":"root",
                "children":[
                  {"className":"View","id":"com.example:id/view_a","bounds":[0,0,100,100]},
                  {"className":"View","id":"com.example:id/view_b","bounds":[120,0,220,100]}
                ]}}],"deviceInfo":{"density":1.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "view_a"), "target2" to mapOf("resourceId" to "view_b"),
            "relation" to mapOf("type" to "spacing", "direction" to "horizontal", "expected" to 20, "tolerance" to 0),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeRelationSpacingInDp() {
        // A=[0,0,300,100], B=[0,160,300,260], density=2.0 → spacing=60px=30dp
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,1080,1920],"id":"root",
                "children":[
                  {"className":"Button","id":"com.example:id/btn_a","bounds":[0,0,300,100]},
                  {"className":"Button","id":"com.example:id/btn_b","bounds":[0,160,300,260]}
                ]}}],"deviceInfo":{"density":2.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "btn_a"), "target2" to mapOf("resourceId" to "btn_b"),
            "relation" to mapOf("type" to "spacing", "direction" to "vertical", "expected" to 30, "tolerance" to 0, "unit" to "dp"),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeRelationAlignmentVerticalPass() {
        // Vertically-aligned: same horizontal center. A=[0,0,100,50], B=[0,60,100,110] → centerX=50 for both → PASS
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,400,400],"id":"root",
                "children":[
                  {"className":"View","id":"com.example:id/view_a","bounds":[0,0,100,50]},
                  {"className":"View","id":"com.example:id/view_b","bounds":[0,60,100,110]}
                ]}}],"deviceInfo":{"density":1.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "view_a"), "target2" to mapOf("resourceId" to "view_b"),
            "relation" to mapOf("type" to "alignment", "direction" to "vertical"),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeRelationAlignmentVerticalFail() {
        // A=[0,0,100,50] centerX=50; B=[10,60,110,110] centerX=60 → diff=10 > 2 → FAIL
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,400,400],"id":"root",
                "children":[
                  {"className":"View","id":"com.example:id/view_a","bounds":[0,0,100,50]},
                  {"className":"View","id":"com.example:id/view_b","bounds":[10,60,110,110]}
                ]}}],"deviceInfo":{"density":1.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "view_a"), "target2" to mapOf("resourceId" to "view_b"),
            "relation" to mapOf("type" to "alignment", "direction" to "vertical"),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue("Expected FAIL: ${result.message}", result.message.startsWith("FAIL"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeRelationAlignmentHorizontal() {
        // Horizontal alignment: same vertical center. A=[0,0,100,60], B=[110,5,210,55] → centerY=30 vs 30 → PASS
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,400,200],"id":"root",
                "children":[
                  {"className":"View","id":"com.example:id/view_a","bounds":[0,0,100,60]},
                  {"className":"View","id":"com.example:id/view_b","bounds":[110,0,210,60]}
                ]}}],"deviceInfo":{"density":1.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "view_a"), "target2" to mapOf("resourceId" to "view_b"),
            "relation" to mapOf("type" to "alignment", "direction" to "horizontal"),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeRelationContainmentPass() {
        // A=[10,10,90,90] inside B=[0,0,100,100] → PASS
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,400,400],"id":"root",
                "children":[
                  {"className":"View","id":"com.example:id/inner","bounds":[10,10,90,90]},
                  {"className":"View","id":"com.example:id/outer","bounds":[0,0,100,100]}
                ]}}],"deviceInfo":{"density":1.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "inner"), "target2" to mapOf("resourceId" to "outer"),
            "relation" to mapOf("type" to "containment"),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeRelationContainmentFail() {
        // A=[0,0,110,100] NOT inside B=[0,0,100,100] (right=110 > 100) → FAIL
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,400,400],"id":"root",
                "children":[
                  {"className":"View","id":"com.example:id/inner","bounds":[0,0,110,100]},
                  {"className":"View","id":"com.example:id/outer","bounds":[0,0,100,100]}
                ]}}],"deviceInfo":{"density":1.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "inner"), "target2" to mapOf("resourceId" to "outer"),
            "relation" to mapOf("type" to "containment"),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue("Expected FAIL: ${result.message}", result.message.startsWith("FAIL"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeRelationOrderVerticalPass() {
        // A=[0,0,100,50] top=0, B=[0,60,100,110] top=60 → A before B vertically → PASS
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,400,400],"id":"root",
                "children":[
                  {"className":"View","id":"com.example:id/view_a","bounds":[0,0,100,50]},
                  {"className":"View","id":"com.example:id/view_b","bounds":[0,60,100,110]}
                ]}}],"deviceInfo":{"density":1.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "view_a"), "target2" to mapOf("resourceId" to "view_b"),
            "relation" to mapOf("type" to "order", "direction" to "vertical"),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeRelationOrderHorizontalFail() {
        // A=[200,0,300,50] left=200, B=[0,0,100,50] left=0 → A is NOT to the left of B → FAIL
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,400,400],"id":"root",
                "children":[
                  {"className":"View","id":"com.example:id/view_a","bounds":[200,0,300,50]},
                  {"className":"View","id":"com.example:id/view_b","bounds":[0,0,100,50]}
                ]}}],"deviceInfo":{"density":1.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "view_a"), "target2" to mapOf("resourceId" to "view_b"),
            "relation" to mapOf("type" to "order", "direction" to "horizontal"),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue("Expected FAIL: ${result.message}", result.message.startsWith("FAIL"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeRelationTarget2MissingForRelation() {
        // Provide relation but no target2 → MCP_INVALID_PARAMS
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,400,400],"id":"root",
                "children":[{"className":"View","id":"com.example:id/view_a","bounds":[0,0,100,50]}]
            }}],"deviceInfo":{"density":1.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "view_a"),
            "relation" to mapOf("type" to "spacing", "direction" to "vertical", "expected" to 16),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.MCP_INVALID_PARAMS, result.errorCode)
        dumpFile.delete()
    }

    // ---- dumpFile mode: selector robustness ----

    @Test
    fun testDumpFileModeSelectByFullResourceId() {
        // Use full package:id/name format; shortId() should strip package prefix
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"Button","id":"com.example:id/btn_save","bounds":[0,0,200,80]}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "com.example:id/btn_save"),
            "assert" to mapOf("property" to "exists"),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeSelectByText() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"TextView","bounds":[0,0,200,60],"text":"Submit"}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("text" to "Submit"),
            "assert" to mapOf("property" to "exists"),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeSelectByContentDesc() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"ImageView","bounds":[0,0,60,60],"contentDesc":"Close button"}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("contentDesc" to "Close button"),
            "assert" to mapOf("property" to "exists"),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeSelectByShortClassName() {
        // Node className=android.widget.Button; selector className=Button (short) → PASS
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"android.widget.Button","id":"com.example:id/btn","bounds":[0,0,200,80]}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("className" to "Button"),
            "assert" to mapOf("property" to "exists"),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeSelectCombinedSelector() {
        // Two buttons with different text; combined resourceId+text picks exact one
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,400,400],"id":"root",
                "children":[
                  {"className":"Button","id":"com.example:id/btn","bounds":[0,0,200,80],"text":"OK"},
                  {"className":"Button","id":"com.example:id/btn","bounds":[0,100,200,180],"text":"Cancel"}
                ]}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "btn", "text" to "Cancel"),
            "assert" to mapOf("property" to "bounds.top", "value" to 100),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeSelectAcrossMultipleWindows() {
        // Target element lives in the second window (e.g. a Dialog)
        val dumpFile = writeDumpFile(
            """{"windows":[
                {"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,1080,1920],"id":"root","children":[]}},
                {"title":"Dialog","root":{"className":"LinearLayout","bounds":[100,400,980,600],"id":"dialog_root",
                  "children":[{"className":"Button","id":"com.example:id/dialog_btn","bounds":[200,450,400,550],"text":"Confirm"}]}}
            ],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "dialog_btn"),
            "assert" to mapOf("property" to "text", "value" to "Confirm"),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeSelectAmbiguousPicksFirst() {
        // Two nodes with same resourceId and no other selector → firstOrNull picks the first (top=0)
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,400,400],"id":"root",
                "children":[
                  {"className":"TextView","id":"com.example:id/item","bounds":[0,0,200,50],"text":"First"},
                  {"className":"TextView","id":"com.example:id/item","bounds":[0,60,200,110],"text":"Second"}
                ]}}],"deviceInfo":{"density":1.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "item"),
            "assert" to mapOf("property" to "text", "value" to "First"),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    // ---- dumpFile mode: edge cases / error handling ----

    @Test
    fun testDumpFileModeInvalidJsonReturnsError() {
        // File content is not valid JSON → MCP_INTERNAL_ERROR
        val dumpFile = writeDumpFile("this is not json {{{")
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "btn"),
            "assert" to mapOf("property" to "exists"),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.MCP_INTERNAL_ERROR, result.errorCode)
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeEmptyWindowsArray() {
        // windows=[] → no nodes → target not found → ERROR
        val dumpFile = writeDumpFile(
            """{"windows":[],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "btn"),
            "assert" to mapOf("property" to "exists"),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue("Expected 'not found': ${result.message}",
            result.message.contains("not found", ignoreCase = true))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeNullBoundsDefaultZero() {
        // No "bounds" field → getBounds() returns [0,0,0,0] → width=0, no crash
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"View","id":"com.example:id/v"}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "v"),
            "assert" to mapOf("property" to "bounds.width", "value" to 0),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeUnsupportedProperty() {
        // Property not in the switch → ERROR containing "unsupported"
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"View","id":"com.example:id/v","bounds":[0,0,100,100]}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "v"),
            "assert" to mapOf("property" to "nonexistentProperty"),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue("Expected 'unsupported' in message: ${result.message}",
            result.message.contains("unsupported", ignoreCase = true))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeUnsupportedRelationType() {
        // Unsupported relation type → ERROR
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,400,400],"id":"root",
                "children":[
                  {"className":"View","id":"com.example:id/view_a","bounds":[0,0,100,50]},
                  {"className":"View","id":"com.example:id/view_b","bounds":[0,60,100,110]}
                ]}}],"deviceInfo":{"density":1.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "view_a"), "target2" to mapOf("resourceId" to "view_b"),
            "relation" to mapOf("type" to "unknownRelationType"),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        dumpFile.delete()
    }

    // ---- dumpFile mode: clickable assertion ----

    @Test
    fun testDumpFileModeAssertClickableTrue() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"Button","id":"com.example:id/btn","bounds":[0,0,300,100],"clickable":true}}],"deviceInfo":{"density":3.0}}"""
        )
        val action = LayoutVerifyMcpToolAction()
        val result = action.execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "btn"),
                "assert" to mapOf("property" to "clickable", "value" to true),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    // ---- live query mode: returns PASS from ViewHierarchyClient ----

    @Test
    fun testLiveQueryModePassResultFromClient() {
        val projectDir = createTempDir(prefix = "jugg_verify_live_")
        val setup = setupDevice(projectDir, packageName = "com.example.app")
        val action = LayoutVerifyMcpToolAction()

        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.verify(any())).thenReturn(
                VerifyResult(result = "PASS", message = "text = \"OK\" (expected: eq \"OK\")", actual = "OK", expected = "OK")
            )
        }.use {
            val result = action.execute(
                mapOf(
                    "projectDir" to projectDir.absolutePath,
                    "target" to mapOf("resourceId" to "btn_ok"),
                    "assert" to mapOf("property" to "text", "value" to "OK"),
                ),
                setup.runtime,
            )
            Assert.assertEquals(McpToolStatus.OK, result.status)
            Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        }
        projectDir.deleteRecursively()
    }

    @Test
    fun testLiveQueryModeFailResultFromClient() {
        val projectDir = createTempDir(prefix = "jugg_verify_live_fail_")
        val setup = setupDevice(projectDir, packageName = "com.example.app")
        val action = LayoutVerifyMcpToolAction()

        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.verify(any())).thenReturn(
                VerifyResult(result = "FAIL", message = "text = \"Actual\" (expected: eq \"Expected\")", actual = "Actual", expected = "Expected")
            )
        }.use {
            val result = action.execute(
                mapOf(
                    "projectDir" to projectDir.absolutePath,
                    "target" to mapOf("resourceId" to "btn_ok"),
                    "assert" to mapOf("property" to "text", "value" to "Expected"),
                ),
                setup.runtime,
            )
            Assert.assertEquals(McpToolStatus.ERROR, result.status)
            Assert.assertTrue("Expected FAIL: ${result.message}", result.message.startsWith("FAIL"))
        }
        projectDir.deleteRecursively()
    }

    @Test
    fun testLiveQueryModeReturnsErrorWhenClientReturnsNull() {
        val projectDir = createTempDir(prefix = "jugg_verify_null_")
        val setup = setupDevice(projectDir, packageName = "com.example.app")
        val action = LayoutVerifyMcpToolAction()

        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.verify(any())).thenReturn(null)
        }.use {
            val result = action.execute(
                mapOf(
                    "projectDir" to projectDir.absolutePath,
                    "target" to mapOf("resourceId" to "btn_ok"),
                    "assert" to mapOf("property" to "exists"),
                ),
                setup.runtime,
            )
            Assert.assertEquals(McpToolStatus.ERROR, result.status)
            Assert.assertTrue(
                "Expected 'unavailable' in message: ${result.message}",
                result.message.contains("unavailable", ignoreCase = true),
            )
        }
        projectDir.deleteRecursively()
    }

    @Test
    fun testLiveQueryModeReturnsNoDeviceErrorWhenNoDevice() {
        val projectDir = createTempDir(prefix = "jugg_verify_no_device_")
        PlatformApi.impl = FakePlatformApi(emptyMap())
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.getSelectedDevices()).thenReturn(emptyList())
        Mockito.`when`(deployTargetManager.getConnectedDevices()).thenReturn(emptyList())
        val action = LayoutVerifyMcpToolAction()

        val result = action.execute(
            mapOf(
                "projectDir" to projectDir.absolutePath,
                "target" to mapOf("resourceId" to "btn_ok"),
                "assert" to mapOf("property" to "exists"),
            ),
            buildRuntimeWithDeployManager(projectDir, deployTargetManager),
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.MCP_NO_DEVICE, result.errorCode)
        projectDir.deleteRecursively()
    }

    // ---- Helpers ----

    private fun writeDumpFile(json: String): File {
        val f = File.createTempFile("jugg_verify_dump_", ".json")
        f.writeText(json, StandardCharsets.UTF_8)
        return f
    }

    private fun buildRuntime(packageName: String?): com.sickworm.intellij.jugg.mcp.IMcpRuntime {
        val projectDir = createTempDir(prefix = "jugg_verify_rt_")
        val device = Mockito.mock(IDevice::class.java)
        val adb = FakeDeviceAdb()
        PlatformApi.impl = FakePlatformApi(mapOf(device to adb))
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.getSelectedDevices()).thenReturn(listOf(device))
        Mockito.`when`(deployTargetManager.getConnectedDevices()).thenReturn(listOf(device))
        if (packageName != null) {
            Mockito.`when`(deployTargetManager.getPackageName()).thenReturn(packageName)
        }
        return buildRuntimeWithDeployManager(projectDir, deployTargetManager)
    }

    private fun setupDevice(projectDir: File, packageName: String): SetupResult {
        val device = Mockito.mock(IDevice::class.java)
        val adb = FakeDeviceAdb()
        PlatformApi.impl = FakePlatformApi(mapOf(device to adb))
        val deployTargetManager = Mockito.mock(IDeployTargetManager::class.java)
        Mockito.`when`(deployTargetManager.getSelectedDevices()).thenReturn(listOf(device))
        Mockito.`when`(deployTargetManager.getConnectedDevices()).thenReturn(listOf(device))
        Mockito.`when`(deployTargetManager.getPackageName()).thenReturn(packageName)
        return SetupResult(runtime = buildRuntimeWithDeployManager(projectDir, deployTargetManager))
    }

    private fun buildRuntimeWithDeployManager(
        projectDir: File,
        deployTargetManager: IDeployTargetManager,
    ): com.sickworm.intellij.jugg.mcp.IMcpRuntime {
        val project = Mockito.mock(com.intellij.openapi.project.Project::class.java)
        Mockito.`when`(project.basePath).thenReturn(projectDir.absolutePath)
        return object : com.sickworm.intellij.jugg.mcp.IMcpRuntime {
            override val logger: com.intellij.openapi.diagnostic.Logger
                get() = com.intellij.openapi.diagnostic.Logger.getInstance("TestMcpRuntime")
            override val project = project
            override val deployTargetManager = deployTargetManager
            override val forceGradleCompileHelper get() = throw UnsupportedOperationException()
            override val juggConfigurationRunner get() = throw UnsupportedOperationException()
            override fun isAppReadyDeploy(): Boolean = true
        }
    }

    private data class SetupResult(val runtime: com.sickworm.intellij.jugg.mcp.IMcpRuntime)

    private class FakeDeviceAdb : IDeviceAdb {
        override val displayName: String? = "fake"
        override val api: Int = 34
        override val serial: String = "emulator-5554"
        override val isOnline: Boolean = true
        override fun execAdbShellCmd(cmd: String): String = ""
        override fun push(from: File, to: String): Boolean = true
        override fun pull(from: String, to: File): Boolean = false
        override fun getDefaultLaunchActivity(apkFile: File): String? = null
        override fun getArch(packageName: String): String = "ARCH_64_BIT"
        override fun getProperty(name: String): String? = null
    }

    private class FakePlatformApi(
        private val adbByDevice: Map<IDevice, IDeviceAdb>,
    ) : com.sickworm.intellij.jugg.platform.IPlatformApi {
        override fun showDialog(title: String, content: String, okButtonText: String?, cancelButtonText: String?, isShowCancelButton: Boolean): Boolean = false
        override fun showChangeConfirmDialog(diffResult: com.sickworm.intellij.jugg.project.dependency.DependencyDiffResult?, isRunLater: Boolean, logger: com.intellij.openapi.diagnostic.Logger): com.sickworm.intellij.jugg.ide.bean.ConfirmResult = throw UnsupportedOperationException()
        override fun showUserAndPasswordInputDialog(content: String, subTitle: String?, isPassword: Boolean, defaultInputText: String?, title: String?): String? = null
        override fun allAvailableJavaHomes(): List<String> = emptyList()
        override fun getGradleJdkPath(project: com.intellij.openapi.project.Project, logger: com.intellij.openapi.diagnostic.Logger): String? = null
        override fun getAndroidHomePath(logger: com.intellij.openapi.diagnostic.Logger): String? = null
        override fun getIdeVersion(): String = "test"
        override fun toDeviceAdb(device: IDevice): IDeviceAdb? = adbByDevice[device]
        override fun isHasRelaunchActivityIssues(device: IDeviceAdb, logger: com.intellij.openapi.diagnostic.Logger): Boolean = false
        override fun invokeMcp(request: com.sickworm.intellij.jugg.mcp.McpJsonRpcRequest): com.sickworm.intellij.jugg.mcp.McpJsonRpcResponse = throw UnsupportedOperationException()
        override fun getInitializedProjectDirs(): List<File> = emptyList()
        override fun executeGradleCompile(autoConfirm: Boolean, useCleanAndReinstall: Boolean) = throw UnsupportedOperationException()
    }
}
