package com.sickworm.intellij.jugg.ai.mcp.actions

import com.sickworm.intellij.jugg.deploy.api.IDevice
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.deploy.IDeployTargetManager
import com.sickworm.intellij.jugg.ai.mcp.McpErrorCode
import com.sickworm.intellij.jugg.ai.mcp.McpToolStatus
import com.sickworm.intellij.jugg.ai.mcp.viewhierarchy.LayoutDumpResult
import com.sickworm.intellij.jugg.ai.mcp.viewhierarchy.VerifyResult
import com.sickworm.intellij.jugg.ai.mcp.viewhierarchy.ViewHierarchyClient
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
 * Note: dumpFile is no longer a public parameter but still used internally for auto dump and tests.
 * All numeric values are always in dp (unit parameter removed).
 */
class LayoutVerifyMcpToolActionTest {

    // ---- dumpFile mode: validation ----

    @Test
    fun testInputSchemaDoesNotContainRootTarget() {
        val properties = LayoutVerifyMcpToolAction().definition.inputSchema.properties
        Assert.assertFalse("inputSchema should not expose root-level target", properties.containsKey("target"))
    }

    @Test
    fun testInputSchemaPropertyEnumIncludesCanonicalNames() {
        val checksProperties = LayoutVerifyMcpToolAction().definition.inputSchema.properties
        val checksItems = checksProperties["checks"]?.items
        val propertyEnum = checksItems?.properties?.get("property")?.`enum`?.mapNotNull { it as? String } ?: emptyList()
        Assert.assertTrue("property enum should include bounds.width", propertyEnum.contains("bounds.width"))
        Assert.assertTrue("property enum should include alpha", propertyEnum.contains("alpha"))
        Assert.assertFalse("property enum should not include width alias", propertyEnum.contains("width"))
    }

    @Test
    fun testInputSchemaRelationAxisAndSpacingOpExist() {
        val checksProperties = LayoutVerifyMcpToolAction().definition.inputSchema.properties
        val checksItems = checksProperties["checks"]?.items
        val axisEnum = checksItems?.properties?.get("axis")?.`enum`?.mapNotNull { it as? String } ?: emptyList()
        val opEnum = checksItems?.properties?.get("op")?.`enum`?.mapNotNull { it as? String } ?: emptyList()
        Assert.assertTrue("axis enum should include x", axisEnum.contains("x"))
        Assert.assertTrue("axis enum should include y", axisEnum.contains("y"))
        Assert.assertTrue("op enum should include gt", opEnum.contains("gt"))
    }

    @Test
    fun testDumpFileModeReturnsErrorWhenNoTargetProvidedForCheck() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"Button","id":"com.example:id/btn_ok","bounds":[0,0,200,80]}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "checks" to listOf(mapOf("type" to "property", "property" to "exists")),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.INVALID_PARAMS, result.errorCode)
        Assert.assertTrue(result.message.contains("target"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeReturnsErrorWhenChecksMissing() {
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
        Assert.assertEquals(McpErrorCode.INVALID_PARAMS, result.errorCode)
        Assert.assertTrue(result.message.contains("checks"))
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
                "checks" to listOf(mapOf("type" to "property", "property" to "exists")),
            ),
            runtime,
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.INVALID_PARAMS, result.errorCode)
        Assert.assertTrue(result.message.contains("dumpFile not found"))
    }

    @Test
    fun testDumpFileModeReturnsErrorWhenChecksFileNotFound() {
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to "/tmp/fake.json",
                "checksFile" to "/nonexistent/path/checks.json",
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.INVALID_PARAMS, result.errorCode)
        Assert.assertTrue(result.message.contains("checksFile not found"))
    }

    @Test
    fun testDumpFileModeSupportsMultipleTargetsInsideChecks() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"root":{"id":"root","className":"FrameLayout","bounds":[0,0,400,400],"children":[
                {"id":"com.example:id/tv_title","className":"TextView","bounds":[0,0,200,50],"text":"Title"},
                {"id":"com.example:id/btn_ok","className":"Button","bounds":[0,70,200,120],"text":"OK"}
            ]}}],"deviceInfo":{"density":1.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "checks" to listOf(
                    mapOf(
                        "target" to mapOf("resourceId" to "tv_title"),
                        "type" to "property",
                        "property" to "text",
                        "value" to "Title",
                    ),
                    mapOf(
                        "target" to mapOf("resourceId" to "btn_ok"),
                        "type" to "property",
                        "property" to "text",
                        "value" to "OK",
                    )
                ),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        val data = result.data as Map<*, *>
        Assert.assertEquals("PASS", data["result"])
        val items = data["checkResults"] as? List<*> ?: emptyList<Any>()
        Assert.assertEquals(2, items.size)
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeUsesChecksFileWhenChecksMissing() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"root":{"id":"com.example:id/btn_ok","className":"Button","bounds":[0,0,200,80],"text":"OK"}}],"deviceInfo":{"density":3.0}}"""
        )
        val checksFile = writeChecksFile(
            """{"checks":[{"target":{"resourceId":"btn_ok"},"type":"property","property":"text","value":"OK"}]}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "checksFile" to checksFile.absolutePath,
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue(result.message.startsWith("PASS"))
        dumpFile.delete()
        checksFile.delete()
    }

    @Test
    fun testDumpFileModeChecksArgumentOverridesChecksFile() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"root":{"id":"com.example:id/btn_ok","className":"Button","bounds":[0,0,200,80],"text":"OK"}}],"deviceInfo":{"density":3.0}}"""
        )
        val checksFile = writeChecksFile(
            """{"checks":[{"target":{"resourceId":"btn_ok"},"type":"property","property":"text","value":"MISMATCH"}]}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "checksFile" to checksFile.absolutePath,
                "checks" to listOf(
                    mapOf(
                        "target" to mapOf("resourceId" to "btn_ok"),
                        "type" to "property",
                        "property" to "text",
                        "value" to "OK",
                    )
                ),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS but got: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
        checksFile.delete()
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
                "checks" to listOf(mapOf("type" to "property", "property" to "exists")),
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
                "checks" to listOf(mapOf("type" to "property", "property" to "text", "value" to "Hello World")),
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
                "checks" to listOf(mapOf("type" to "property", "property" to "text", "value" to "Expected")),
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
                "checks" to listOf(mapOf("type" to "property", "property" to "bounds.width", "value" to 100)),
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
                "checks" to listOf(mapOf("type" to "property", "property" to "exists")),
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
                "checks" to listOf(mapOf("target2" to mapOf("resourceId" to "btn_b"), "type" to "spacing", "axis" to "y", "expected" to 16, "op" to "eq")),
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
                "checks" to listOf(mapOf("target2" to mapOf("resourceId" to "btn_b"), "type" to "spacing", "axis" to "y", "expected" to 20, "op" to "eq")),
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
                "checks" to listOf(mapOf("target2" to mapOf("resourceId" to "view_b"), "type" to "overlap")),
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
                "checks" to listOf(mapOf("type" to "property", "property" to "text", "op" to "contains", "value" to "World")),
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
                "checks" to listOf(mapOf("type" to "property", "property" to "text", "op" to "contains", "value" to "Bye")),
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
                "checks" to listOf(mapOf("type" to "property", "property" to "text", "op" to "matches", "value" to "Order #\\d+")),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertTextMatchesInvalidRegex() {
        // Invalid regex pattern must return ERROR with descriptive message, not a silent FAIL
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"TextView","id":"com.example:id/label","bounds":[0,0,300,100],"text":"abc"}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "label"),
                "checks" to listOf(mapOf("type" to "property", "property" to "text", "op" to "matches", "value" to "[invalid(")),
            ),
            buildRuntime(null),
        )
        // Invalid regex → ERROR (distinct from FAIL which means valid regex but text did not match)
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue(
            "Expected 'invalid regex' in message: ${result.message}",
            result.message.contains("invalid regex", ignoreCase = true),
        )
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertTextMatchesValidRegexNotMatching() {
        // Valid regex that does not match → FAIL (not ERROR), so agent can distinguish the two cases
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"TextView","id":"com.example:id/tv","bounds":[0,0,300,100],"text":"Ready for action"}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "tv"),
                "checks" to listOf(mapOf("type" to "property", "property" to "text", "op" to "matches", "value" to "Waiting.*interaction")),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue(
            "Expected FAIL (not ERROR) in message: ${result.message}",
            result.message.startsWith("FAIL"),
        )
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertWithToleranceReturnsError() {
        // tolerance is no longer supported; should return ERROR
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"View","id":"com.example:id/sv","bounds":[0,0,1080,660]}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "sv"),
                "checks" to listOf(mapOf("type" to "property", "property" to "bounds.height", "op" to "eq", "value" to "220", "tolerance" to 5)),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue(
            "Expected message about tolerance not supported: ${result.message}",
            result.message.contains("tolerance", ignoreCase = true) && result.message.contains("no longer supported", ignoreCase = true),
        )
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeRelationSpacingFailContainsBoundsInfo() {
        // spacing FAIL message must include bounds of both elements to help agent diagnose
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,1080,1920],"id":"root",
                "children":[
                  {"className":"TextView","id":"com.example:id/tv_title","bounds":[0,50,1080,100]},
                  {"className":"Button","id":"com.example:id/btn_main","bounds":[0,748,1080,860]}
                ]}}],"deviceInfo":{"density":3.0}}"""
        )
        // Actual spacing = 748-100=648px = 216dp; expected=20dp → FAIL
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "tv_title"),
                "checks" to listOf(mapOf("target2" to mapOf("resourceId" to "btn_main"), "type" to "spacing", "axis" to "y", "expected" to 20, "op" to "eq")),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue("Expected FAIL in message: ${result.message}", result.message.startsWith("FAIL"))
        Assert.assertTrue(
            "Expected bounds info in FAIL message: ${result.message}",
            result.message.contains("target") && result.message.contains("target2"),
        )
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeRelationSpacingWithToleranceInDpPass() {
        // A=[0,0,1080,100], B=[0,760,1080,860], density=3.0 → spacing=660px=220dp; expected=220dp ±5dp → PASS
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,1080,1920],"id":"root",
                "children":[
                  {"className":"View","id":"com.example:id/view_a","bounds":[0,0,1080,100]},
                  {"className":"View","id":"com.example:id/view_b","bounds":[0,760,1080,860]}
                ]}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "view_a"),
                "checks" to listOf(mapOf("target2" to mapOf("resourceId" to "view_b"), "type" to "spacing", "axis" to "y", "expected" to 220, "op" to "eq")),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
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
                "checks" to listOf(mapOf("type" to "property", "property" to "text", "value" to "")),
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
                "checks" to listOf(mapOf("type" to "property", "property" to "clickable", "value" to false)),
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
                "checks" to listOf(mapOf("type" to "property", "property" to "enabled", "value" to false)),
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
                "checks" to listOf(mapOf("type" to "property", "property" to "enabled", "value" to true)),
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
                "checks" to listOf(mapOf("type" to "property", "property" to "visibility", "value" to "gone")),
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
                "checks" to listOf(mapOf("type" to "property", "property" to "visibility", "value" to "invisible")),
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
                "checks" to listOf(mapOf("type" to "property", "property" to "visibility", "op" to "contains", "value" to "invis")),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    // ---- dumpFile mode: bounds / size with various operators ----

    @Test
    fun testDumpFileModeAssertBoundsHeightAlwaysDp() {
        // bounds=[0,0,300,100] → height=100px; density=3.0 → 33dp; assert eq 33 → PASS
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"View","id":"com.example:id/v","bounds":[0,0,300,100]}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "v"),
                "checks" to listOf(mapOf("type" to "property", "property" to "bounds.height", "value" to 33)),
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
                    "checks" to listOf(mapOf("type" to "property", "property" to property, "value" to expected)),
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
                "checks" to listOf(mapOf("type" to "property", "property" to "bounds.width", "op" to "gte", "value" to 200)),
            ), runtime,
        )
        val rLte = action.execute(
            mapOf(
                "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "v"),
                "checks" to listOf(mapOf("type" to "property", "property" to "bounds.width", "op" to "lte", "value" to 400)),
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
                "checks" to listOf(mapOf("type" to "property", "property" to "bounds.width", "op" to "gt", "value" to 299))), runtime).status)
        Assert.assertEquals(McpToolStatus.OK, action.execute(
            mapOf("projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "v"),
                "checks" to listOf(mapOf("type" to "property", "property" to "bounds.width", "op" to "lt", "value" to 301))), runtime).status)
        Assert.assertEquals(McpToolStatus.ERROR, action.execute(
            mapOf("projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "v"),
                "checks" to listOf(mapOf("type" to "property", "property" to "bounds.width", "op" to "gt", "value" to 300))), runtime).status)
        Assert.assertEquals(McpToolStatus.ERROR, action.execute(
            mapOf("projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "v"),
                "checks" to listOf(mapOf("type" to "property", "property" to "bounds.width", "op" to "lt", "value" to 300))), runtime).status)
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
                    "checks" to listOf(mapOf("type" to "property", "property" to property, "value" to expected)),
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
                "checks" to listOf(mapOf("type" to "property", "property" to "bounds.height", "value" to 0)),
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
                "checks" to listOf(mapOf("type" to "property", "property" to "textColor", "value" to "#ffff0000")),
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
                "checks" to listOf(mapOf("type" to "property", "property" to "textColor", "value" to "#FF0000FF")),
            ), buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue("Expected FAIL: ${result.message}", result.message.startsWith("FAIL"))
        Assert.assertTrue("Expected actual value in message: ${result.message}",
            result.message.contains("#FFFF0000", ignoreCase = true))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertTextColorNeqPass() {
        // textColor=#FFFF0000, assert neq #FFFFFFFF → PASS
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"TextView","id":"com.example:id/tv","bounds":[0,0,200,60],"textColor":"#FFFF0000"}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "tv"),
                "checks" to listOf(mapOf("type" to "property", "property" to "textColor", "op" to "neq", "value" to "#FFFFFFFF")),
            ), buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertTextColorNeqFail() {
        // textColor=#FFFF0000, assert neq #FFFF0000 → FAIL (same color)
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"TextView","id":"com.example:id/tv","bounds":[0,0,200,60],"textColor":"#FFFF0000"}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "tv"),
                "checks" to listOf(mapOf("type" to "property", "property" to "textColor", "op" to "neq", "value" to "#ffff0000")),
            ), buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue("Expected FAIL: ${result.message}", result.message.startsWith("FAIL"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertTextColorContainsPass() {
        // textColor=#FFFF0000, contains "FF00" → PASS
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"TextView","id":"com.example:id/tv","bounds":[0,0,200,60],"textColor":"#FFFF0000"}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "tv"),
                "checks" to listOf(mapOf("type" to "property", "property" to "textColor", "op" to "contains", "value" to "FF00")),
            ), buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertTextColorMatchesPass() {
        // textColor=#FFFF0000, matches regex "#FF[0-9A-F]+" → PASS
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"TextView","id":"com.example:id/tv","bounds":[0,0,200,60],"textColor":"#FFFF0000"}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "tv"),
                "checks" to listOf(mapOf("type" to "property", "property" to "textColor", "op" to "matches", "value" to "#FF[0-9A-F]+")),
            ), buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
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
                "checks" to listOf(mapOf("type" to "property", "property" to "textColor", "value" to "#FF000000")),
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
                "checks" to listOf(mapOf("type" to "property", "property" to "alpha", "value" to 0.5)),
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
                "checks" to listOf(mapOf("type" to "property", "property" to "alpha", "value" to 1.0)),
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
                "checks" to listOf(mapOf("target2" to mapOf("resourceId" to "view_b"), "type" to "overlap")),
            ), buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue("Expected FAIL: ${result.message}", result.message.startsWith("FAIL"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeRelationSpacingToleranceRejected() {
        // tolerance parameter is no longer supported, should return ERROR
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,1080,1920],"id":"root",
                "children":[
                  {"className":"Button","id":"com.example:id/btn_a","bounds":[0,0,300,100]},
                  {"className":"Button","id":"com.example:id/btn_b","bounds":[0,116,300,200]}
                ]}}],"deviceInfo":{"density":1.0}}"""
        )
        val action = LayoutVerifyMcpToolAction()
        val runtime = buildRuntime(null)
        val result = action.execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "btn_a"), "checks" to listOf(mapOf("target2" to mapOf("resourceId" to "btn_b"), "type" to "spacing", "axis" to "y", "expected" to 16, "tolerance" to 2)),
        ), runtime)
        Assert.assertEquals("tolerance should be rejected", McpToolStatus.ERROR, result.status)
        Assert.assertTrue(
            "Expected message about tolerance not supported: ${result.message}",
            result.message.contains("tolerance", ignoreCase = true) && result.message.contains("no longer supported", ignoreCase = true)
        )
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeRelationSpacingOpGtPass() {
        // spacing=16px, expected gt 0dp -> PASS
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,1080,1920],"id":"root",
                "children":[
                  {"className":"Button","id":"com.example:id/btn_a","bounds":[0,0,300,100]},
                  {"className":"Button","id":"com.example:id/btn_b","bounds":[0,116,300,200]}
                ]}}],"deviceInfo":{"density":1.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "btn_a"),
                "checks" to listOf(
                    mapOf(
                        "target2" to mapOf("resourceId" to "btn_b"),
                        "type" to "spacing",
                        "axis" to "y",
                        "op" to "gt",
                        "expected" to 0,
                    )
                ),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        Assert.assertTrue("Expected op in message: ${result.message}", result.message.contains("expected: gt 0dp"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeRelationSpacingOpGtFail() {
        // spacing=16px, expected gt 16dp -> FAIL
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,1080,1920],"id":"root",
                "children":[
                  {"className":"Button","id":"com.example:id/btn_a","bounds":[0,0,300,100]},
                  {"className":"Button","id":"com.example:id/btn_b","bounds":[0,116,300,200]}
                ]}}],"deviceInfo":{"density":1.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "btn_a"),
                "checks" to listOf(
                    mapOf(
                        "target2" to mapOf("resourceId" to "btn_b"),
                        "type" to "spacing",
                        "axis" to "y",
                        "op" to "gt",
                        "expected" to 16,
                    )
                ),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue("Expected FAIL: ${result.message}", result.message.startsWith("FAIL"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeRelationSpacingRejectsOpAndToleranceTogether() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,1080,1920],"id":"root",
                "children":[
                  {"className":"Button","id":"com.example:id/btn_a","bounds":[0,0,300,100]},
                  {"className":"Button","id":"com.example:id/btn_b","bounds":[0,116,300,200]}
                ]}}],"deviceInfo":{"density":1.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "btn_a"),
                "checks" to listOf(
                    mapOf(
                        "target2" to mapOf("resourceId" to "btn_b"),
                        "type" to "spacing",
                        "axis" to "y",
                        "op" to "gt",
                        "expected" to 8,
                        "tolerance" to 2,
                    )
                ),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.INVALID_PARAMS, result.errorCode)
        Assert.assertTrue("Expected tolerance not supported message: ${result.message}",
            result.message.contains("tolerance", ignoreCase = true) && result.message.contains("no longer supported", ignoreCase = true))
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
            "target" to mapOf("resourceId" to "view_a"), "checks" to listOf(mapOf("target2" to mapOf("resourceId" to "view_b"), "type" to "spacing", "axis" to "x", "expected" to 20, "op" to "eq")),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeRelationSpacingAxisXPass() {
        // A=[0,0,100,100], B=[120,0,220,100] -> axis=x spacing=20px
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,400,200],"id":"root",
                "children":[
                  {"className":"View","id":"com.example:id/view_a","bounds":[0,0,100,100]},
                  {"className":"View","id":"com.example:id/view_b","bounds":[120,0,220,100]}
                ]}}],"deviceInfo":{"density":1.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "view_a"), "checks" to listOf(mapOf("target2" to mapOf("resourceId" to "view_b"), "type" to "spacing", "axis" to "x", "expected" to 20, "op" to "eq")),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        Assert.assertTrue("Expected axis in message: ${result.message}", result.message.contains("spacing (axis=x)"))
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
            "target" to mapOf("resourceId" to "btn_a"), "checks" to listOf(mapOf("target2" to mapOf("resourceId" to "btn_b"), "type" to "spacing", "axis" to "y", "expected" to 30, "op" to "eq")),
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
            "target" to mapOf("resourceId" to "view_a"), "checks" to listOf(mapOf("target2" to mapOf("resourceId" to "view_b"), "type" to "alignment", "axis" to "x")),
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
            "target" to mapOf("resourceId" to "view_a"), "checks" to listOf(mapOf("target2" to mapOf("resourceId" to "view_b"), "type" to "alignment", "axis" to "x")),
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
            "target" to mapOf("resourceId" to "view_a"), "checks" to listOf(mapOf("target2" to mapOf("resourceId" to "view_b"), "type" to "alignment", "axis" to "y")),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeRelationAlignmentAxisXPass() {
        // axis=x checks X-center alignment
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,400,400],"id":"root",
                "children":[
                  {"className":"View","id":"com.example:id/view_a","bounds":[0,0,100,50]},
                  {"className":"View","id":"com.example:id/view_b","bounds":[0,60,100,110]}
                ]}}],"deviceInfo":{"density":1.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "view_a"), "checks" to listOf(mapOf("target2" to mapOf("resourceId" to "view_b"), "type" to "alignment", "axis" to "x")),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        Assert.assertTrue("Expected axis message: ${result.message}", result.message.contains("axis=x"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeRelationAlignmentAxisYPass() {
        // axis=y checks Y-center alignment
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,400,200],"id":"root",
                "children":[
                  {"className":"View","id":"com.example:id/view_a","bounds":[0,0,100,60]},
                  {"className":"View","id":"com.example:id/view_b","bounds":[110,0,210,60]}
                ]}}],"deviceInfo":{"density":1.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "view_a"), "checks" to listOf(mapOf("target2" to mapOf("resourceId" to "view_b"), "type" to "alignment", "axis" to "y")),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        Assert.assertTrue("Expected axis message: ${result.message}", result.message.contains("axis=y"))
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
            "target" to mapOf("resourceId" to "inner"), "checks" to listOf(mapOf("target2" to mapOf("resourceId" to "outer"), "type" to "containment")),
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
            "target" to mapOf("resourceId" to "inner"), "checks" to listOf(mapOf("target2" to mapOf("resourceId" to "outer"), "type" to "containment")),
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
            "target" to mapOf("resourceId" to "view_a"), "checks" to listOf(mapOf("target2" to mapOf("resourceId" to "view_b"), "type" to "order", "axis" to "y")),
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
            "target" to mapOf("resourceId" to "view_a"), "checks" to listOf(mapOf("target2" to mapOf("resourceId" to "view_b"), "type" to "order", "axis" to "x")),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue("Expected FAIL: ${result.message}", result.message.startsWith("FAIL"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeRelationTarget2MissingForRelation() {
        // Provide relation but no target2 → INVALID_PARAMS
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,400,400],"id":"root",
                "children":[{"className":"View","id":"com.example:id/view_a","bounds":[0,0,100,50]}]
            }}],"deviceInfo":{"density":1.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "view_a"),
            "checks" to listOf(mapOf("type" to "spacing", "axis" to "y", "expected" to 16)),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.INVALID_PARAMS, result.errorCode)
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
            "checks" to listOf(mapOf("type" to "property", "property" to "exists")),
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
            "checks" to listOf(mapOf("type" to "property", "property" to "exists")),
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
            "checks" to listOf(mapOf("type" to "property", "property" to "exists")),
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
            "checks" to listOf(mapOf("type" to "property", "property" to "exists")),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeSelectByClassNameSubstring() {
        // Node className=android.support.v7.widget.AppCompatTextView;
        // selector className=TextView (suffix of short name "AppCompatTextView") → PASS
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"android.support.v7.widget.AppCompatTextView","id":"com.example:id/tv_title","bounds":[0,0,1080,60],"text":"MCP Test Page"}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("text" to "MCP Test Page", "className" to "TextView"),
            "checks" to listOf(mapOf("type" to "property", "property" to "exists")),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertTextMatchesPartialRegexPass() {
        // "Waiting for interaction..." matches the pattern "Waiting.*interaction" via containsMatchIn
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"TextView","id":"com.example:id/tv","bounds":[0,0,300,60],"text":"Waiting for interaction..."}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "tv"),
            "checks" to listOf(mapOf("type" to "property", "property" to "text", "op" to "matches", "value" to "Waiting.*interaction")),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertTextNeqPass() {
        // op=neq: text != "Wrong" → PASS
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"TextView","id":"com.example:id/tv","bounds":[0,0,300,60],"text":"#FF1976D2"}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "tv"),
            "checks" to listOf(mapOf("type" to "property", "property" to "text", "op" to "neq", "value" to "#FFFFFFFF")),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertTextNeqFail() {
        // op=neq: text == expected → FAIL
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"TextView","id":"com.example:id/tv","bounds":[0,0,300,60],"text":"#FFFFFFFF"}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "tv"),
            "checks" to listOf(mapOf("type" to "property", "property" to "text", "op" to "neq", "value" to "#FFFFFFFF")),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue("Expected FAIL: ${result.message}", result.message.startsWith("FAIL"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertBoundsExpectedFieldCorrectWhenValuePassedAsString() {
        // value passed as String "100"; after fix, data.expected must echo 100 not 0
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"Button","id":"com.example:id/btn","bounds":[0,0,900,150]}}],"deviceInfo":{"density":3.0}}"""
        )
        // width=900px / density=3.0 = 300dp; assert gte "100"dp → PASS; expected field must be 100
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "btn"),
            "checks" to listOf(mapOf("type" to "property", "property" to "bounds.width", "op" to "gte", "value" to "100")),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        val data = result.data as? Map<*, *> ?: emptyMap<String, Any>()
        val items = data["checkResults"] as? List<*> ?: emptyList<Any>()
        val firstItem = items.firstOrNull() as? Map<*, *> ?: emptyMap<String, Any>()
        val itemMessage = firstItem["message"] as? String ?: ""
        Assert.assertTrue("Expected item message to include expected threshold: $itemMessage", itemMessage.contains("100dp"))
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
            // bounds.top=100px, density=3.0 → 33dp
            "checks" to listOf(mapOf("type" to "property", "property" to "bounds.top", "value" to 33)),
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
            "checks" to listOf(mapOf("type" to "property", "property" to "text", "value" to "Confirm")),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeSelectAmbiguousReturnsError() {
        // Two nodes with same resourceId → ambiguous selector → ERROR with multiple-match message
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
            "checks" to listOf(mapOf("type" to "property", "property" to "text", "value" to "First")),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue("Expected multiple-match error: ${result.message}", result.message.contains("Multiple elements"))
        dumpFile.delete()
    }

    // ---- dumpFile mode: edge cases / error handling ----

    @Test
    fun testDumpFileModeInvalidJsonReturnsError() {
        // File content is not valid JSON → INTERNAL_ERROR
        val dumpFile = writeDumpFile("this is not json {{{")
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "btn"),
            "checks" to listOf(mapOf("type" to "property", "property" to "exists")),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.INTERNAL_ERROR, result.errorCode)
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
            "checks" to listOf(mapOf("type" to "property", "property" to "exists")),
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
            "checks" to listOf(mapOf("type" to "property", "property" to "bounds.width", "value" to 0)),
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
            "checks" to listOf(mapOf("type" to "property", "property" to "nonexistentProperty")),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue("Expected 'unsupported' in message: ${result.message}",
            result.message.contains("unsupported", ignoreCase = true))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeWidthAliasMappedToBoundsWidth() {
        // width alias should be normalized to bounds.width
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"View","id":"com.example:id/v","bounds":[0,0,300,100]}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "v"),
            "checks" to listOf(mapOf("type" to "property", "property" to "width", "value" to 100)),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected normalized property in message: ${result.message}",
            result.message.contains("bounds.width"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeUnsupportedPropertyReturnsSuggestionAndObservedSize() {
        // Misspelled property should include supported-properties list and observed bounds hint.
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"View","id":"com.example:id/v","bounds":[0,0,300,100]}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "v"),
            "checks" to listOf(mapOf("type" to "property", "property" to "widht", "value" to 50)),
        ), buildRuntime(null))
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue("Expected observed width hint in message: ${result.message}",
            result.message.contains("reference bounds.width", ignoreCase = true))
        Assert.assertTrue("Expected supported properties list in message: ${result.message}",
            result.message.contains("supported properties", ignoreCase = true))
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
            "target" to mapOf("resourceId" to "view_a"), "checks" to listOf(mapOf("target2" to mapOf("resourceId" to "view_b"), "type" to "unknownRelationType")),
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
                "checks" to listOf(mapOf("type" to "property", "property" to "clickable", "value" to true)),
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
                    "checks" to listOf(mapOf("type" to "property", "property" to "textSizeSp", "op" to "gte", "value" to "12")),
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
                    "checks" to listOf(mapOf("type" to "property", "property" to "textSizeSp", "op" to "gte", "value" to "12")),
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
                    "checks" to listOf(mapOf("type" to "property", "property" to "exists")),
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
                "checks" to listOf(mapOf("type" to "property", "property" to "exists")),
            ),
            buildRuntimeWithDeployManager(projectDir, deployTargetManager),
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertEquals(McpErrorCode.NO_DEVICE, result.errorCode)
        projectDir.deleteRecursively()
    }

    @Test
    fun testAutoDumpModeWhenDumpFileMissing() {
        val projectDir = createTempDir(prefix = "jugg_verify_auto_dump_")
        val setup = setupDevice(projectDir, packageName = "com.example.app")
        val action = LayoutVerifyMcpToolAction()
        val dumpJson = """{"windows":[{"root":{"id":"com.example:id/btn_ok","className":"Button","bounds":[0,0,200,80],"text":"OK"}}],"deviceInfo":{"density":3.0}}"""

        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.dumpLayout(anyOrNull(), any(), any())).thenReturn(
                LayoutDumpResult(payloadJson = dumpJson, remoteFilePath = null)
            )
        }.use { mocked ->
            val result = action.execute(
                mapOf(
                    "projectDir" to projectDir.absolutePath,
                    "target" to mapOf("resourceId" to "btn_ok"),
                    "checks" to listOf(mapOf("type" to "property", "property" to "text", "value" to "OK")),
                ),
                setup.runtime,
            )
            Assert.assertEquals(McpToolStatus.OK, result.status)
            val constructed = mocked.constructed().first()
            Mockito.verify(constructed, Mockito.times(1)).dumpLayout(anyOrNull(), any(), any())
            Mockito.verify(constructed, Mockito.never()).verify(any())
        }
        projectDir.deleteRecursively()
    }

    @Test
    fun testBatchAssertsReturnsPartialFail() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"root":{"id":"com.example:id/btn_ok","className":"Button","bounds":[0,0,200,80],"text":"OK"}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "btn_ok"),
                "checks" to listOf(
                    mapOf("type" to "property", "property" to "text", "value" to "OK"),
                    mapOf("type" to "property", "property" to "text", "value" to "MISMATCH"),
                ),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        val data = result.data as Map<*, *>
        Assert.assertEquals("PARTIAL_FAIL", data["result"])
        val items = data["checkResults"] as List<*>
        Assert.assertEquals(2, items.size)
        dumpFile.delete()
    }

    @Test
    fun testAssertsAndRelationsCanRunTogether() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"root":{"id":"root","className":"FrameLayout","bounds":[0,0,400,400],"children":[
                {"id":"com.example:id/btn_ok","className":"Button","bounds":[0,0,100,50],"text":"OK"},
                {"id":"com.example:id/btn_ok_2","className":"Button","bounds":[0,70,100,120],"text":"OK2"}
            ]}}],"deviceInfo":{"density":1.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "btn_ok"),
                "checks" to listOf(
                    mapOf("type" to "property", "property" to "exists"),
                    mapOf(
                        "target2" to mapOf("resourceId" to "btn_ok_2"),
                        "type" to "spacing",
                        "axis" to "y",
                        "expected" to 20,
                        "op" to "eq",
                    )
                ),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        val data = result.data as Map<*, *>
        val items = data["checkResults"] as? List<*> ?: emptyList<Any>()
        Assert.assertEquals(2, items.size)
        dumpFile.delete()
    }

    @Test
    fun testLiveOnlyPropertyUsesLiveModeWithoutDump() {
        val projectDir = createTempDir(prefix = "jugg_verify_live_only_")
        val setup = setupDevice(projectDir, packageName = "com.example.app")
        val action = LayoutVerifyMcpToolAction()

        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.verify(any())).thenReturn(
                VerifyResult(result = "PASS", message = "textSizeSp = 14 (expected: gte 12)")
            )
        }.use { mocked ->
            val result = action.execute(
                mapOf(
                    "projectDir" to projectDir.absolutePath,
                    "target" to mapOf("resourceId" to "btn_ok"),
                    "checks" to listOf(mapOf("type" to "property", "property" to "textSizeSp", "op" to "gte", "value" to "12")),
                ),
                setup.runtime,
            )
            Assert.assertEquals(McpToolStatus.OK, result.status)
            val constructed = mocked.constructed().first()
            Mockito.verify(constructed, Mockito.never()).dumpLayout(anyOrNull(), any(), any())
            Mockito.verify(constructed, Mockito.times(1)).verify(any())
        }
        projectDir.deleteRecursively()
    }

    @Test
    fun testLiveQueryModeRelationAxisInjectsLegacyDirection() {
        val projectDir = createTempDir(prefix = "jugg_verify_live_axis_")
        val setup = setupDevice(projectDir, packageName = "com.example.app")
        val action = LayoutVerifyMcpToolAction()
        val capturedParams = mutableListOf<Map<String, Any?>>()

        Mockito.mockConstruction(ViewHierarchyClient::class.java) { mock, _ ->
            Mockito.`when`(mock.verify(any())).thenAnswer { invocation ->
                @Suppress("UNCHECKED_CAST")
                capturedParams.add(invocation.arguments[0] as Map<String, Any?>)
                VerifyResult(result = "PASS", message = "spacing (axis=y) = 16dp (expected: gt 0dp)")
            }
        }.use {
            val result = action.execute(
                mapOf(
                    "projectDir" to projectDir.absolutePath,
                    "target" to mapOf("resourceId" to "btn_a"),
                    "checks" to listOf(
                        mapOf(
                            "type" to "property",
                            "property" to "textSizeSp",
                            "op" to "gte",
                            "value" to 12,
                        ),
                        mapOf(
                            "type" to "spacing",
                            "target2" to mapOf("resourceId" to "btn_b"),
                            "axis" to "y",
                            "op" to "gt",
                            "expected" to 0,
                        )
                    ),
                ),
                setup.runtime,
            )
            Assert.assertEquals(McpToolStatus.OK, result.status)
        }
        Assert.assertEquals(2, capturedParams.size)
        val params = capturedParams[1]
        @Suppress("UNCHECKED_CAST")
        val relation = params["relation"] as Map<String, Any?>
        Assert.assertEquals("y", relation["axis"])
        Assert.assertEquals("gt", relation["op"])
        projectDir.deleteRecursively()
    }

    @Test
    fun testTargetNotFoundReturnsScoredCandidates() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"root":{"id":"root","className":"FrameLayout","bounds":[0,0,1000,1000],"children":[
                {"id":"com.example:id/btn_primary","className":"Button","text":"Primary","bounds":[0,0,200,80]},
                {"id":"com.example:id/btn_secondary","className":"Button","text":"Secondary","bounds":[0,100,200,180]}
            ]}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "btn_prmary"),
                "checks" to listOf(mapOf("type" to "property", "property" to "exists")),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        val data = result.data as Map<*, *>
        val candidates = data["candidates"] as? List<*> ?: emptyList<Any>()
        Assert.assertTrue(candidates.isNotEmpty())
        val first = candidates.first() as Map<*, *>
        Assert.assertTrue(first.containsKey("score"))
        Assert.assertTrue(first.containsKey("reason"))
        dumpFile.delete()
    }

    // ---- Helpers ----

    // ==== Tests for §1: alpha op support (gt/lt/neq) ====

    @Test
    fun testDumpFileModeAssertAlphaGtPass() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"View","id":"com.example:id/v","bounds":[0,0,100,100],"alpha":1.0}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "v"),
                "checks" to listOf(mapOf("type" to "property", "property" to "alpha", "op" to "gt", "value" to 0.5)),
            ), buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertAlphaGtPassWhenValueIsString() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"View","id":"com.example:id/v","bounds":[0,0,100,100],"alpha":1.0}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "v"),
                "checks" to listOf(mapOf("type" to "property", "property" to "alpha", "op" to "gt", "value" to "0.5")),
            ), buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected threshold 0.5 in message: ${result.message}", result.message.contains("gt 0.5"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertAlphaGtFailEqual() {
        // alpha=0.5, gt 0.5 → should FAIL (not strictly greater)
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"View","id":"com.example:id/v","bounds":[0,0,100,100],"alpha":0.5}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "v"),
                "checks" to listOf(mapOf("type" to "property", "property" to "alpha", "op" to "gt", "value" to 0.5)),
            ), buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue("Expected FAIL: ${result.message}", result.message.startsWith("FAIL"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertAlphaLtPass() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"View","id":"com.example:id/v","bounds":[0,0,100,100],"alpha":0.3}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "v"),
                "checks" to listOf(mapOf("type" to "property", "property" to "alpha", "op" to "lt", "value" to 0.5)),
            ), buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertAlphaNeqPass() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"View","id":"com.example:id/v","bounds":[0,0,100,100],"alpha":0.5}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "v"),
                "checks" to listOf(mapOf("type" to "property", "property" to "alpha", "op" to "neq", "value" to 1.0)),
            ), buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertAlphaNeqFailEqual() {
        // alpha=1.0, neq 1.0 → should FAIL
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"View","id":"com.example:id/v","bounds":[0,0,100,100],"alpha":1.0}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "v"),
                "checks" to listOf(mapOf("type" to "property", "property" to "alpha", "op" to "neq", "value" to 1.0)),
            ), buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue("Expected FAIL: ${result.message}", result.message.startsWith("FAIL"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertAlphaGteBoundary() {
        // alpha=0.5, gte 0.5 → PASS (epsilon tolerance)
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"View","id":"com.example:id/v","bounds":[0,0,100,100],"alpha":0.5}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "v"),
                "checks" to listOf(mapOf("type" to "property", "property" to "alpha", "op" to "gte", "value" to 0.5)),
            ), buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAssertAlphaLteBoundary() {
        // alpha=0.5, lte 0.5 → PASS (epsilon tolerance)
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"View","id":"com.example:id/v","bounds":[0,0,100,100],"alpha":0.5}}],"deviceInfo":{"density":3.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "v"),
                "checks" to listOf(mapOf("type" to "property", "property" to "alpha", "op" to "lte", "value" to 0.5)),
            ), buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    // ==== Tests for §2: alignment message format ====

    @Test
    fun testDumpFileModeAlignmentVerticalMessageContainsXCenter() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,400,400],"id":"root",
                "children":[
                  {"className":"View","id":"com.example:id/view_a","bounds":[0,0,100,50]},
                  {"className":"View","id":"com.example:id/view_b","bounds":[0,60,100,110]}
                ]}}],"deviceInfo":{"density":1.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "view_a"),
            "checks" to listOf(mapOf("target2" to mapOf("resourceId" to "view_b"), "type" to "alignment", "axis" to "x")),
        ), buildRuntime(null))
        Assert.assertTrue("Message should contain X-center check: ${result.message}", result.message.contains("X-center check"))
        Assert.assertTrue("Message should contain axis=x: ${result.message}", result.message.contains("axis=x"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeAlignmentHorizontalMessageContainsYCenter() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,400,200],"id":"root",
                "children":[
                  {"className":"View","id":"com.example:id/view_a","bounds":[0,0,100,60]},
                  {"className":"View","id":"com.example:id/view_b","bounds":[110,0,210,60]}
                ]}}],"deviceInfo":{"density":1.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "view_a"),
            "checks" to listOf(mapOf("target2" to mapOf("resourceId" to "view_b"), "type" to "alignment", "axis" to "y")),
        ), buildRuntime(null))
        Assert.assertTrue("Message should contain Y-center check: ${result.message}", result.message.contains("Y-center check"))
        Assert.assertTrue("Message should contain axis=y: ${result.message}", result.message.contains("axis=y"))
        dumpFile.delete()
    }

    // ==== Tests for §4: overlap expectOverlap parameter ====

    @Test
    fun testDumpFileModeOverlapExpectOverlapTrueOverlappingPass() {
        // Two overlapping views + expectOverlap=true → PASS
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
                "checks" to listOf(mapOf("target2" to mapOf("resourceId" to "view_b"), "type" to "overlap", "expectOverlap" to true)),
            ), buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        Assert.assertTrue("Message should indicate expectOverlap=true: ${result.message}", result.message.contains("expectOverlap=true"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeOverlapExpectOverlapTrueNoOverlapFail() {
        // Two non-overlapping views + expectOverlap=true → FAIL
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,400,400],"id":"root",
                "children":[
                  {"className":"View","id":"com.example:id/view_a","bounds":[0,0,100,100]},
                  {"className":"View","id":"com.example:id/view_b","bounds":[200,0,300,100]}
                ]}}],"deviceInfo":{"density":1.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "view_a"),
                "checks" to listOf(mapOf("target2" to mapOf("resourceId" to "view_b"), "type" to "overlap", "expectOverlap" to true)),
            ), buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue("Expected FAIL: ${result.message}", result.message.startsWith("FAIL"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeOverlapDefaultBehaviorPreserved() {
        // No expectOverlap param → default false → no overlap = PASS
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,400,400],"id":"root",
                "children":[
                  {"className":"View","id":"com.example:id/view_a","bounds":[0,0,100,100]},
                  {"className":"View","id":"com.example:id/view_b","bounds":[200,0,300,100]}
                ]}}],"deviceInfo":{"density":1.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(
            mapOf(
                "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
                "target" to mapOf("resourceId" to "view_a"),
                "checks" to listOf(mapOf("target2" to mapOf("resourceId" to "view_b"), "type" to "overlap")),
            ), buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS: ${result.message}", result.message.startsWith("PASS"))
        Assert.assertTrue("Message should indicate expectOverlap=false: ${result.message}", result.message.contains("expectOverlap=false"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeOverlapExpectOverlapFalseExplicit() {
        // Explicit expectOverlap=false, overlapping → FAIL
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
                "checks" to listOf(mapOf("target2" to mapOf("resourceId" to "view_b"), "type" to "overlap", "expectOverlap" to false)),
            ), buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue("Expected FAIL: ${result.message}", result.message.startsWith("FAIL"))
        dumpFile.delete()
    }

    // ==== Tests for §5: containment message format ====

    @Test
    fun testDumpFileModeContainmentPassMessageFormat() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,400,400],"id":"root",
                "children":[
                  {"className":"View","id":"com.example:id/inner","bounds":[10,10,90,90]},
                  {"className":"View","id":"com.example:id/outer","bounds":[0,0,100,100]}
                ]}}],"deviceInfo":{"density":1.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "inner"),
            "checks" to listOf(mapOf("target2" to mapOf("resourceId" to "outer"), "type" to "containment")),
        ), buildRuntime(null))
        Assert.assertTrue("Message should contain target(child): ${result.message}", result.message.contains("target(child)"))
        Assert.assertTrue("Message should contain target2(parent): ${result.message}", result.message.contains("target2(parent)"))
        dumpFile.delete()
    }

    @Test
    fun testDumpFileModeContainmentFailMessageFormat() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,400,400],"id":"root",
                "children":[
                  {"className":"View","id":"com.example:id/inner","bounds":[0,0,110,100]},
                  {"className":"View","id":"com.example:id/outer","bounds":[0,0,100,100]}
                ]}}],"deviceInfo":{"density":1.0}}"""
        )
        val result = LayoutVerifyMcpToolAction().execute(mapOf(
            "projectDir" to "/tmp", "dumpFile" to dumpFile.absolutePath,
            "target" to mapOf("resourceId" to "inner"),
            "checks" to listOf(mapOf("target2" to mapOf("resourceId" to "outer"), "type" to "containment")),
        ), buildRuntime(null))
        Assert.assertTrue("Message should contain target(child): ${result.message}", result.message.contains("target(child)"))
        Assert.assertTrue("Message should contain 'is NOT inside': ${result.message}", result.message.contains("is NOT inside"))
        Assert.assertTrue("Message should contain target2(parent): ${result.message}", result.message.contains("target2(parent)"))
        dumpFile.delete()
    }

    // ---- Multiple element matching tests ----

    @Test
    fun testMultipleMatchesWithExistsPropertyShouldPass() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,1080,1920],"children":[
                {"className":"TextView","text":"Suite","id":"com.example:id/text1","bounds":[0,0,200,100]},
                {"className":"TextView","text":"Suite","id":"com.example:id/text2","bounds":[0,100,200,200]},
                {"className":"TextView","text":"Suite","id":"com.example:id/text3","bounds":[0,200,200,300]}
            ]}}],"deviceInfo":{"density":3.0}}"""
        )
        val action = LayoutVerifyMcpToolAction()
        val result = action.execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "checks" to listOf(mapOf("type" to "property", "property" to "exists", "target" to mapOf("text" to "Suite"))),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS for exists check with multiple matches: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    @Test
    fun testMultipleMatchesWithNonExistsPropertyShouldFail() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"FrameLayout","bounds":[0,0,1080,1920],"children":[
                {"className":"TextView","text":"Suite","id":"com.example:id/text1","bounds":[0,0,200,100]},
                {"className":"TextView","text":"Suite","id":"com.example:id/text2","bounds":[0,100,200,200]}
            ]}}],"deviceInfo":{"density":3.0}}"""
        )
        val action = LayoutVerifyMcpToolAction()
        val result = action.execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "checks" to listOf(mapOf("type" to "property", "property" to "text", "value" to "Suite", "target" to mapOf("text" to "Suite"))),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.ERROR, result.status)
        Assert.assertTrue("Expected error message about multiple matches: ${result.message}", result.message.contains("Multiple elements"))
        dumpFile.delete()
    }

    @Test
    fun testSingleMatchWithAnyPropertyShouldPass() {
        val dumpFile = writeDumpFile(
            """{"windows":[{"title":"Main","root":{"className":"TextView","text":"Unique","id":"com.example:id/unique","bounds":[0,0,200,100]}}],"deviceInfo":{"density":3.0}}"""
        )
        val action = LayoutVerifyMcpToolAction()
        val result = action.execute(
            mapOf(
                "projectDir" to "/tmp",
                "dumpFile" to dumpFile.absolutePath,
                "checks" to listOf(mapOf("type" to "property", "property" to "text", "value" to "Unique", "target" to mapOf("text" to "Unique"))),
            ),
            buildRuntime(null),
        )
        Assert.assertEquals(McpToolStatus.OK, result.status)
        Assert.assertTrue("Expected PASS for single match: ${result.message}", result.message.startsWith("PASS"))
        dumpFile.delete()
    }

    private fun writeDumpFile(json: String): File {
        val f = File.createTempFile("jugg_verify_dump_", ".json")
        f.writeText(json, StandardCharsets.UTF_8)
        return f
    }

    private fun writeChecksFile(json: String): File {
        val f = File.createTempFile("jugg_verify_checks_", ".json")
        f.writeText(json, StandardCharsets.UTF_8)
        return f
    }

    private fun buildRuntime(packageName: String?): com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime {
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
    ): com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime {
        return object : com.sickworm.intellij.jugg.ai.mcp.TestMcpRuntime() {
            override val logger: com.intellij.openapi.diagnostic.Logger
                get() = com.intellij.openapi.diagnostic.Logger.getInstance("TestMcpRuntime")
            override val projectDir: String = projectDir.absolutePath
            override val deployTargetManager = deployTargetManager
            override val forceGradleCompileHelper get() = throw UnsupportedOperationException()
            override val juggConfigurationRunner get() = throw UnsupportedOperationException()
            override fun isAppReadyDeploy(): Boolean = true
        }
    }

    private data class SetupResult(val runtime: com.sickworm.intellij.jugg.ai.mcp.IMcpRuntime)

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
        override fun showUserAndPasswordInputDialog(content: String, subTitle: String?, isPassword: Boolean, defaultInputText: String?, title: String?): String? = null
        override fun allAvailableJavaHomes(): List<String> = emptyList()
        override fun getGradleJdkPath(project: com.intellij.openapi.project.Project, logger: com.intellij.openapi.diagnostic.Logger): String? = null
        override fun getAndroidHomePath(logger: com.intellij.openapi.diagnostic.Logger): String? = null
        override fun getIdeVersion(): String = "test"
        override fun getRuntimeInfo() = com.sickworm.intellij.jugg.project.runtime.RuntimeInfo("test", "test", "test", "")
        override fun toDeviceAdb(device: IDevice): IDeviceAdb? = adbByDevice[device]
        override fun isHasRelaunchActivityIssues(device: IDeviceAdb, logger: com.intellij.openapi.diagnostic.Logger): Boolean = false
        override fun invokeMcp(request: com.sickworm.intellij.jugg.ai.mcp.McpJsonRpcRequest): com.sickworm.intellij.jugg.ai.mcp.McpJsonRpcResponse = throw UnsupportedOperationException()
        override fun getInitializedProjectDirs(): List<File> = emptyList()
        override fun executeGradleCompile(autoConfirm: Boolean, useCleanAndReinstall: Boolean) = throw UnsupportedOperationException()
    }
}
