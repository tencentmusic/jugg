package com.sickworm.intellij.jugg.mcp.actions

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sickworm.intellij.jugg.deploy.IDeviceAdb
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.mcp.DeviceSelectionResolver
import com.sickworm.intellij.jugg.mcp.DeviceSelectionResult
import com.sickworm.intellij.jugg.mcp.IMcpRuntime
import com.sickworm.intellij.jugg.mcp.McpErrorCode
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaObject
import com.sickworm.intellij.jugg.mcp.McpJsonSchemaProperty
import com.sickworm.intellij.jugg.mcp.McpToolDefinition
import com.sickworm.intellij.jugg.mcp.McpToolResult
import com.sickworm.intellij.jugg.mcp.McpToolStatus
import com.sickworm.intellij.jugg.mcp.viewhierarchy.MatchCandidate
import com.sickworm.intellij.jugg.mcp.viewhierarchy.VerifyResult
import com.sickworm.intellij.jugg.mcp.viewhierarchy.ViewHierarchyClient
import com.sickworm.intellij.jugg.platform.PlatformApi
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * LayoutVerifyMcpToolAction implements MCP tool `layout_verify`.
 * Supports two modes:
 *  - dumpFile mode: parses a previously saved layout_dump JSON file (0 extra App communication).
 *  - live query mode: queries the running App via socket (when dumpFile is not provided).
 * Each call performs either an `assert` (single element property check) or a `relation`
 * (two-element spatial/structural check), returning PASS/FAIL/ERROR.
 */
class LayoutVerifyMcpToolAction : McpToolAction {
    override val toolName: String = "layout_verify"

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Verify UI element properties or relations. " +
            "Use dumpFile mode (pass dumpFile path from layout_dump) for 0 extra App communication, " +
            "or omit dumpFile for live query mode to access properties not in dump (e.g. textSizeSp). " +
            "Either assert (single element) or relation (two elements) must be provided.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
                "dumpFile" to McpJsonSchemaProperty(
                    type = "string",
                    description = "Optional path to layout_dump JSON file. When provided, uses dumpFile mode (no App communication).",
                ),
                "target" to McpJsonSchemaProperty(
                    type = "object",
                    description = "Element selector for the primary target. At least one of resourceId/text/contentDesc/className required.",
                    properties = mapOf(
                        "resourceId" to McpJsonSchemaProperty(type = "string"),
                        "text" to McpJsonSchemaProperty(type = "string"),
                        "contentDesc" to McpJsonSchemaProperty(type = "string"),
                        "className" to McpJsonSchemaProperty(type = "string"),
                    ),
                    additionalProperties = false,
                ),
                "target2" to McpJsonSchemaProperty(
                    type = "object",
                    description = "Second element selector, required for relation checks.",
                    properties = mapOf(
                        "resourceId" to McpJsonSchemaProperty(type = "string"),
                        "text" to McpJsonSchemaProperty(type = "string"),
                        "contentDesc" to McpJsonSchemaProperty(type = "string"),
                        "className" to McpJsonSchemaProperty(type = "string"),
                    ),
                    additionalProperties = false,
                ),
                "assert" to McpJsonSchemaProperty(
                    type = "object",
                    description = "Property assertion. Mutually exclusive with relation.",
                    properties = mapOf(
                        "property" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Property to check: exists/visibility/clickable/enabled/text/bounds.width/bounds.height/" +
                                "bounds.left/bounds.top/bounds.right/bounds.bottom/alpha/textColor/textSizeSp/padding.left/padding.top/padding.right/padding.bottom",
                        ),
                        "op" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Comparison operator: eq (default)/gte/lte/gt/lt/contains/matches/neq. " +
                                "'matches' treats value as a Java regex and checks if the pattern is found anywhere in the string (substring match, not full-string match). " +
                                "'neq' checks that the actual value does NOT equal the expected value.",
                            `enum` = listOf("eq", "neq", "gte", "lte", "gt", "lt", "contains", "matches"),
                        ),
                        "value" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Expected value. Numeric values can be passed as strings (e.g. \"100\") or numbers.",
                        ),
                        "unit" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Unit: dp or px (default px for coordinates)",
                            `enum` = listOf("dp", "px"),
                        ),
                    ),
                    additionalProperties = false,
                ),
                "relation" to McpJsonSchemaProperty(
                    type = "object",
                    description = "Relation check between target and target2. Mutually exclusive with assert.",
                    properties = mapOf(
                        "type" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Relation type: spacing/alignment/overlap/containment/order. " +
                                "spacing measures the actual on-screen pixel gap between two element bounds — this is NOT the same as layout margin.",
                            `enum` = listOf("spacing", "alignment", "overlap", "containment", "order"),
                        ),
                        "direction" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Direction for spacing/alignment/order: " +
                                "vertical = elements are stacked top-to-bottom (checks same horizontal/X center for alignment); " +
                                "horizontal = elements are placed left-to-right (checks same vertical/Y center for alignment)",
                            `enum` = listOf("horizontal", "vertical"),
                        ),
                        "expected" to McpJsonSchemaProperty(type = "number", description = "Expected value (for spacing). Defaults to 0 when omitted."),
                        "tolerance" to McpJsonSchemaProperty(type = "number", description = "Tolerance for spacing (default 0). Used to account for dp rounding errors."),
                        "unit" to McpJsonSchemaProperty(
                            type = "string",
                            description = "Unit: dp or px",
                            `enum` = listOf("dp", "px"),
                        ),
                    ),
                    additionalProperties = false,
                ),
            ),
            required = listOf("projectDir", "target"),
            additionalProperties = false,
        ),
        outputSchema = McpToolSchemas.baseOutputSchema.copy(
            properties = McpToolSchemas.baseOutputSchema.properties + mapOf(
                "data" to McpJsonSchemaProperty(
                    type = "object",
                    properties = mapOf(
                        "result" to McpJsonSchemaProperty(type = "string", `enum` = listOf("PASS", "FAIL", "ERROR")),
                        "message" to McpJsonSchemaProperty(type = "string"),
                        "actual" to McpJsonSchemaProperty(type = "string"),
                        "expected" to McpJsonSchemaProperty(type = "string"),
                        "unit" to McpJsonSchemaProperty(type = "string"),
                    ),
                    additionalProperties = true,
                )
            )
        ),
    )

    @Suppress("UNCHECKED_CAST")
    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        val logger = runtime.logger.getInstance("LayoutVerifyMcpToolAction")

        val dumpFile = arguments["dumpFile"] as? String
        val target = arguments["target"] as? Map<String, Any?>
        val target2 = arguments["target2"] as? Map<String, Any?>
        val assert = arguments["assert"] as? Map<String, Any?>
        val relation = arguments["relation"] as? Map<String, Any?>

        if (target == null) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "layout_verify failed: target is required",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_INVALID_PARAMS,
            )
        }
        if (assert == null && relation == null) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "layout_verify failed: assert or relation is required",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_INVALID_PARAMS,
            )
        }

        return if (!dumpFile.isNullOrBlank()) {
            executeDumpFileMode(dumpFile, target, target2, assert, relation, logger)
        } else {
            executeLiveQueryMode(arguments, runtime, target, target2, assert, relation, logger)
        }
    }

    // ---- dumpFile mode ----

    private fun executeDumpFileMode(
        dumpFilePath: String,
        target: Map<String, Any?>,
        target2: Map<String, Any?>?,
        assert: Map<String, Any?>?,
        relation: Map<String, Any?>?,
        logger: com.intellij.openapi.diagnostic.Logger,
    ): McpToolResult {
        val file = File(dumpFilePath)
        if (!file.exists()) {
            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "layout_verify failed: dumpFile not found: $dumpFilePath",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_INVALID_PARAMS,
            )
        }
        return try {
            val jsonContent = file.readText(StandardCharsets.UTF_8)
            val root = JsonParser.parseString(jsonContent).asJsonObject
            val density = root.optDouble("deviceInfo", "density") ?: 1.0f.toDouble()
            val allNodes = collectAllNodes(root)

            val targetNode = findNodeBySelector(allNodes, target)
                ?: return errorResult("target not found: ${selectorDesc(target)}", buildCandidates(allNodes))

            if (assert != null) {
                val verifyResult = assertDumpNode(targetNode, assert, density)
                return toMcpResult(verifyResult)
            }

            val t2Selector = target2
                ?: return McpToolResult(
                    status = McpToolStatus.ERROR,
                    message = "layout_verify failed: target2 required for relation",
                    data = emptyMap<String, Any>(),
                    artifacts = emptyList(),
                    errorCode = McpErrorCode.MCP_INVALID_PARAMS,
                )
            val target2Node = findNodeBySelector(allNodes, t2Selector)
                ?: return errorResult("target2 not found: ${selectorDesc(t2Selector)}", buildCandidates(allNodes))

            val verifyResult = relationDumpNodes(targetNode, target2Node, relation!!, density)
            toMcpResult(verifyResult)
        } catch (e: Exception) {
            logger.warn("layout_verify dumpFile mode failed: ${e.message}", e)
            McpToolResult(
                status = McpToolStatus.ERROR,
                message = "layout_verify failed: ${e.message}",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
            )
        }
    }

    private fun JsonObject.optDouble(vararg path: String): Double? {
        var current: JsonElement = this
        for (key in path) {
            current = (current as? JsonObject)?.get(key) ?: return null
        }
        return runCatching { current.asDouble }.getOrNull()
    }

    private fun collectAllNodes(root: JsonObject): List<JsonObject> {
        val nodes = mutableListOf<JsonObject>()
        val windows = root.get("windows")?.takeIf { it.isJsonArray }?.asJsonArray ?: return nodes
        for (window in windows) {
            val rootNode = window.asJsonObjectOrNull()?.get("root")?.asJsonObjectOrNull() ?: continue
            collectNodeRecursive(rootNode, nodes)
        }
        return nodes
    }

    private fun collectNodeRecursive(node: JsonObject, out: MutableList<JsonObject>) {
        out.add(node)
        val children = node.get("children")?.takeIf { it.isJsonArray }?.asJsonArray ?: return
        for (child in children) {
            val childObj = child.asJsonObjectOrNull() ?: continue
            collectNodeRecursive(childObj, out)
        }
    }

    private fun findNodeBySelector(nodes: List<JsonObject>, selector: Map<String, Any?>): JsonObject? {
        val resourceId = selector["resourceId"] as? String
        val text = selector["text"] as? String
        val contentDesc = selector["contentDesc"] as? String
        val className = selector["className"] as? String

        return nodes.firstOrNull { node ->
            val nodeId = node.optStringOrNull("id")
            val nodeText = node.optStringOrNull("text")
            val nodeContentDesc = node.optStringOrNull("contentDesc")
            val nodeClassName = node.optStringOrNull("className")

            val idMatch = resourceId == null || shortId(resourceId) == shortId(nodeId)
            val textMatch = text == null || text == nodeText
            val descMatch = contentDesc == null || contentDesc == nodeContentDesc
            // Support both simple name (e.g. "TextView") and full name matching.
            // A class like "AppCompatTextView" ends with "TextView", so we use endsWith for the short name.
            val classMatch = className == null || className == nodeClassName
                || className == nodeClassName?.substringAfterLast('.')
                || nodeClassName?.substringAfterLast('.')?.contains(className) == true
            idMatch && textMatch && descMatch && classMatch
        }
    }

    private fun shortId(id: String?): String? {
        if (id.isNullOrEmpty()) return id
        val slash = id.lastIndexOf('/')
        return if (slash >= 0 && slash < id.length - 1) id.substring(slash + 1) else id
    }

    @Suppress("UNCHECKED_CAST")
    private fun assertDumpNode(node: JsonObject, assert: Map<String, Any?>, density: Double): VerifyResult {
        val property = assert["property"] as? String ?: return VerifyResult("ERROR", "assert.property is required")
        val op = assert["op"] as? String ?: "eq"
        val value = assert["value"]
        val unit = assert["unit"] as? String

        if (assert.containsKey("tolerance")) {
            return VerifyResult(
                "ERROR",
                "assert does not support 'tolerance'. " +
                    "To verify approximate values, use two separate asserts with 'gte' and 'lte'. " +
                    "For layout gap checks with tolerance, use relation.type=spacing with tolerance."
            )
        }

        return when (property) {
            "exists" -> VerifyResult("PASS", "exists = true (expected: exists)")
            "visibility" -> {
                val actual = node.optStringOrNull("visibility") ?: "visible"
                val expected = value as? String ?: "visible"
                assertStr(actual, op, expected, "visibility")
            }
            "clickable" -> {
                val actual = node.get("clickable")?.runCatching { asBoolean }?.getOrDefault(false) ?: false
                val expected = when (value) {
                    is Boolean -> value
                    is String -> value.toBoolean()
                    else -> true
                }
                if (actual == expected) VerifyResult("PASS", "clickable = $actual")
                else VerifyResult("FAIL", "clickable = $actual (expected: $expected)", actual, expected)
            }
            "enabled" -> {
                val actual = node.get("enabled")?.runCatching { asBoolean }?.getOrDefault(true) ?: true
                val expected = when (value) {
                    is Boolean -> value
                    is String -> value.toBoolean()
                    else -> true
                }
                if (actual == expected) VerifyResult("PASS", "enabled = $actual")
                else VerifyResult("FAIL", "enabled = $actual (expected: $expected)", actual, expected)
            }
            "text" -> {
                val actual = node.optStringOrNull("text") ?: ""
                val expected = value as? String ?: ""
                assertStr(actual, op, expected, "text")
            }
            "textColor" -> {
                val actual = node.optStringOrNull("textColor") ?: "#FF000000"
                val expected = value as? String ?: ""
                if (actual.equals(expected, ignoreCase = true))
                    VerifyResult("PASS", "textColor = $actual", actual, expected)
                else
                    VerifyResult("FAIL", "textColor = $actual (expected: $expected)", actual, expected)
            }
            "alpha" -> {
                val actual = node.get("alpha")?.runCatching { asDouble }?.getOrDefault(1.0) ?: 1.0
                val expected = (value as? Number)?.toDouble() ?: 1.0
                if (Math.abs(actual - expected) < 0.001)
                    VerifyResult("PASS", "alpha = $actual", actual, expected)
                else
                    VerifyResult("FAIL", "alpha = $actual (expected: $op $expected)", actual, expected)
            }
            "bounds.width" -> assertBoundsDp(boundsWidth(node), op, value, unit, density, "bounds.width")
            "bounds.height" -> assertBoundsDp(boundsHeight(node), op, value, unit, density, "bounds.height")
            "bounds.left" -> assertBoundsDp(boundsLeft(node), op, value, unit, density, "bounds.left")
            "bounds.top" -> assertBoundsDp(boundsTop(node), op, value, unit, density, "bounds.top")
            "bounds.right" -> assertBoundsDp(boundsRight(node), op, value, unit, density, "bounds.right")
            "bounds.bottom" -> assertBoundsDp(boundsBottom(node), op, value, unit, density, "bounds.bottom")
            "padding.left" -> assertBoundsDp(paddingAt(node, 0), op, value, unit, density, "padding.left")
            "padding.top" -> assertBoundsDp(paddingAt(node, 1), op, value, unit, density, "padding.top")
            "padding.right" -> assertBoundsDp(paddingAt(node, 2), op, value, unit, density, "padding.right")
            "padding.bottom" -> assertBoundsDp(paddingAt(node, 3), op, value, unit, density, "padding.bottom")
            else -> VerifyResult("ERROR", "unsupported property in dumpFile mode: $property")
        }
    }

    private fun assertStr(actual: String, op: String, expected: String, property: String): VerifyResult {
        if (op == "matches") {
            val regex = runCatching { Regex(expected) }.getOrElse {
                return VerifyResult("ERROR", "invalid regex pattern for $property: \"$expected\" (${it.message})")
            }
            // Use containsMatchIn() so the pattern can match a substring, not the whole string.
            val pass = regex.containsMatchIn(actual)
            val msg = "$property = \"$actual\" (expected: matches \"$expected\")"
            return if (pass) VerifyResult("PASS", msg, actual, expected)
            else VerifyResult("FAIL", msg, actual, expected)
        }
        val pass = when (op) {
            "contains" -> actual.contains(expected)
            "neq" -> actual != expected
            else -> actual == expected
        }
        val msg = "$property = \"$actual\" (expected: $op \"$expected\")"
        return if (pass) VerifyResult("PASS", msg, actual, expected)
        else VerifyResult("FAIL", msg, actual, expected)
    }

    private fun assertBoundsDp(actualPx: Int, op: String, value: Any?, unit: String?, density: Double, property: String): VerifyResult {
        val actual = if (unit == "dp" && density > 0) Math.round(actualPx / density).toInt() else actualPx
        // Accept both Number and String to tolerate MCP callers that serialize numbers as strings.
        val expected = when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: 0
            else -> 0
        }
        val unitLabel = unit ?: "px"
        val pass = when (op) {
            "gte" -> actual >= expected
            "lte" -> actual <= expected
            "gt" -> actual > expected
            "lt" -> actual < expected
            "neq" -> actual != expected
            else -> actual == expected
        }
        val msg = "$property = $actual$unitLabel (expected: $op $expected$unitLabel)"
        return if (pass) VerifyResult("PASS", msg, actual, expected, unitLabel)
        else VerifyResult("FAIL", msg, actual, expected, unitLabel)
    }

    @Suppress("UNCHECKED_CAST")
    private fun relationDumpNodes(target: JsonObject, target2: JsonObject, relation: Map<String, Any?>, density: Double): VerifyResult {
        val type = relation["type"] as? String ?: return VerifyResult("ERROR", "relation.type is required")
        val direction = relation["direction"] as? String
        val expected = (relation["expected"] as? Number)?.toInt() ?: 0
        val tolerance = (relation["tolerance"] as? Number)?.toInt() ?: 0
        val unit = relation["unit"] as? String

        return when (type) {
            "spacing" -> {
                val (aLeft, aTop, aRight, aBottom) = getBounds(target)
                val (bLeft, bTop, bRight, bBottom) = getBounds(target2)
                val spacingPx = if ("vertical".equals(direction, ignoreCase = true)) {
                    val topOfLower = maxOf(aTop, bTop)
                    val bottomOfUpper = minOf(aBottom, bBottom)
                    topOfLower - bottomOfUpper
                } else {
                    val leftOfRight = maxOf(aLeft, bLeft)
                    val rightOfLeft = minOf(aRight, bRight)
                    leftOfRight - rightOfLeft
                }
                val actual = if (unit == "dp" && density > 0) Math.round(spacingPx / density).toInt() else spacingPx
                val unitLabel = unit ?: "px"
                val pass = Math.abs(actual - expected) <= tolerance
                val boundsInfo = " [target:[$aLeft,$aTop,$aRight,$aBottom], target2:[$bLeft,$bTop,$bRight,$bBottom]]"
                val msg = "spacing ($direction) = $actual$unitLabel (expected: $expected$unitLabel ±$tolerance$unitLabel)$boundsInfo"
                if (pass) VerifyResult("PASS", msg, actual, expected, unitLabel)
                else VerifyResult("FAIL", msg, actual, expected, unitLabel)
            }
            "alignment" -> {
                val (aLeft, aTop, aRight, aBottom) = getBounds(target)
                val (bLeft, bTop, bRight, bBottom) = getBounds(target2)
                val pass: Boolean
                val desc: String
                if ("vertical".equals(direction, ignoreCase = true)) {
                    val centerA = (aLeft + aRight) / 2
                    val centerB = (bLeft + bRight) / 2
                    pass = Math.abs(centerA - centerB) <= 2
                    desc = "horizontal centers: $centerA vs $centerB"
                } else {
                    val centerA = (aTop + aBottom) / 2
                    val centerB = (bTop + bBottom) / 2
                    pass = Math.abs(centerA - centerB) <= 2
                    desc = "vertical centers: $centerA vs $centerB"
                }
                val msg = "alignment ($direction): $desc"
                if (pass) VerifyResult("PASS", msg) else VerifyResult("FAIL", msg)
            }
            "overlap" -> {
                val (aLeft, aTop, aRight, aBottom) = getBounds(target)
                val (bLeft, bTop, bRight, bBottom) = getBounds(target2)
                val overlaps = aLeft < bRight && aRight > bLeft && aTop < bBottom && aBottom > bTop
                val msg = "overlap: " + if (overlaps) "elements overlap" else "no overlap"
                if (!overlaps) VerifyResult("PASS", msg) else VerifyResult("FAIL", msg)
            }
            "containment" -> {
                val (aLeft, aTop, aRight, aBottom) = getBounds(target)
                val (bLeft, bTop, bRight, bBottom) = getBounds(target2)
                val contained = aLeft >= bLeft && aTop >= bTop && aRight <= bRight && aBottom <= bBottom
                val msg = "containment: target " + if (contained) "is inside" else "is NOT inside" + " container"
                if (contained) VerifyResult("PASS", msg) else VerifyResult("FAIL", msg)
            }
            "order" -> {
                val (aLeft, aTop, _, _) = getBounds(target)
                val (bLeft, bTop, _, _) = getBounds(target2)
                val inOrder = if ("vertical".equals(direction, ignoreCase = true)) aTop < bTop else aLeft < bLeft
                val msg = "order ($direction): ${if (inOrder) "correct" else "incorrect"}"
                if (inOrder) VerifyResult("PASS", msg) else VerifyResult("FAIL", msg)
            }
            else -> VerifyResult("ERROR", "unsupported relation type: $type")
        }
    }

    private data class BoundsQuad(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private fun getBounds(node: JsonObject): BoundsQuad {
        val arr = node.get("bounds")?.takeIf { it.isJsonArray }?.asJsonArray
        return if (arr != null && arr.size() == 4) {
            BoundsQuad(arr[0].asInt, arr[1].asInt, arr[2].asInt, arr[3].asInt)
        } else {
            BoundsQuad(0, 0, 0, 0)
        }
    }

    private fun boundsWidth(node: JsonObject): Int {
        val b = getBounds(node); return b.right - b.left
    }
    private fun boundsHeight(node: JsonObject): Int {
        val b = getBounds(node); return b.bottom - b.top
    }
    private fun boundsLeft(node: JsonObject): Int = getBounds(node).left
    private fun boundsTop(node: JsonObject): Int = getBounds(node).top
    private fun boundsRight(node: JsonObject): Int = getBounds(node).right
    private fun boundsBottom(node: JsonObject): Int = getBounds(node).bottom

    private fun paddingAt(node: JsonObject, index: Int): Int {
        val arr = node.get("padding")?.takeIf { it.isJsonArray }?.asJsonArray ?: return 0
        return if (arr.size() > index) arr[index].asInt else 0
    }

    private fun buildCandidates(nodes: List<JsonObject>): List<MatchCandidate> {
        return nodes.filter { it.get("clickable")?.runCatching { asBoolean }?.getOrDefault(false) == true }
            .take(5)
            .map { node ->
                val bounds = node.get("bounds")?.takeIf { it.isJsonArray }?.asJsonArray
                val boundsInts = bounds?.mapIndexed { _, e -> runCatching { e.asInt }.getOrDefault(0) }
                MatchCandidate(
                    text = node.optStringOrNull("text").orEmpty(),
                    resourceId = node.optStringOrNull("id").orEmpty(),
                    contentDesc = node.optStringOrNull("contentDesc").orEmpty(),
                    className = node.optStringOrNull("className").orEmpty(),
                    bounds = boundsInts,
                    centerX = -1,
                    centerY = -1,
                )
            }
    }

    // ---- live query mode ----

    private fun executeLiveQueryMode(
        arguments: Map<String, Any?>,
        runtime: IMcpRuntime,
        target: Map<String, Any?>,
        target2: Map<String, Any?>?,
        assert: Map<String, Any?>?,
        relation: Map<String, Any?>?,
        logger: com.intellij.openapi.diagnostic.Logger,
    ): McpToolResult {
        val selected = resolveOnlineDevice(runtime)
            ?: return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "layout_verify failed: No connected device is available.",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_NO_DEVICE,
            )
        val packageName = resolvePackageName(runtime)
            ?: return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "layout_verify failed: unable to resolve package name",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
            )

        return try {
            val client = ViewHierarchyClient(selected.adb, packageName)
            val params = buildLiveParams(target, target2, assert, relation)
            val verifyResult = client.verify(params)
                ?: return McpToolResult(
                    status = McpToolStatus.ERROR,
                    message = "layout_verify failed: ViewHierarchy server unavailable",
                    data = emptyMap<String, Any>(),
                    artifacts = emptyList(),
                    errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
                )
            toMcpResult(verifyResult)
        } catch (e: Exception) {
            logger.warn("layout_verify live mode failed: ${e.message}", e)
            McpToolResult(
                status = McpToolStatus.ERROR,
                message = "layout_verify failed: ${e.message}",
                data = emptyMap<String, Any>(),
                artifacts = emptyList(),
                errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildLiveParams(
        target: Map<String, Any?>,
        target2: Map<String, Any?>?,
        assert: Map<String, Any?>?,
        relation: Map<String, Any?>?,
    ): Map<String, Any?> {
        val params = mutableMapOf<String, Any?>()
        params["target"] = target
        if (target2 != null) params["target2"] = target2
        if (assert != null) params["assert"] = assert
        if (relation != null) params["relation"] = relation
        return params
    }

    // ---- Result conversion ----

    private fun toMcpResult(verifyResult: VerifyResult): McpToolResult {
        val resultData = mutableMapOf<String, Any>()
        resultData["result"] = verifyResult.result
        resultData["message"] = verifyResult.message
        verifyResult.actual?.let { resultData["actual"] = it }
        verifyResult.expected?.let { resultData["expected"] = it }
        verifyResult.unit?.let { resultData["unit"] = it }
        if (verifyResult.candidates.isNotEmpty()) {
            resultData["candidates"] = verifyResult.candidates.map { c ->
                mapOf(
                    "text" to c.text,
                    "resourceId" to c.resourceId,
                    "contentDesc" to c.contentDesc,
                    "className" to c.className,
                )
            }
        }

        val status = when (verifyResult.result) {
            "PASS" -> McpToolStatus.OK
            "FAIL" -> McpToolStatus.ERROR
            else -> McpToolStatus.ERROR
        }
        return McpToolResult(
            status = status,
            message = "${verifyResult.result}: ${verifyResult.message}",
            data = resultData,
            artifacts = emptyList(),
            errorCode = if (verifyResult.result == "ERROR") McpErrorCode.MCP_INTERNAL_ERROR else null,
        )
    }

    private fun errorResult(message: String, candidates: List<MatchCandidate>): McpToolResult {
        val data = mutableMapOf<String, Any>(
            "result" to "ERROR",
            "message" to message,
        )
        if (candidates.isNotEmpty()) {
            data["candidates"] = candidates.map { c ->
                mapOf("text" to c.text, "resourceId" to c.resourceId)
            }
        }
        return McpToolResult(
            status = McpToolStatus.ERROR,
            message = "ERROR: $message",
            data = data,
            artifacts = emptyList(),
            errorCode = McpErrorCode.MCP_INTERNAL_ERROR,
        )
    }

    private fun selectorDesc(selector: Map<String, Any?>): String {
        val parts = mutableListOf<String>()
        selector["resourceId"]?.let { parts.add("resourceId=$it") }
        selector["text"]?.let { parts.add("text=$it") }
        selector["contentDesc"]?.let { parts.add("contentDesc=$it") }
        return parts.joinToString(", ")
    }

    // ---- Device helpers ----

    private data class SelectedAdb(val adb: IDeviceAdb)

    private fun resolveOnlineDevice(runtime: IMcpRuntime): SelectedAdb? {
        val selectionResult = DeviceSelectionResolver().resolve(runtime.deployTargetManager)
        if (selectionResult !is DeviceSelectionResult.Selected) return null
        val adb = PlatformApi.toDeviceAdb(selectionResult.device) ?: return null
        if (!adb.isOnline) return null
        return SelectedAdb(adb = adb)
    }

    private fun resolvePackageName(runtime: IMcpRuntime): String? {
        return try {
            runtime.deployTargetManager.getPackageName().takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    // ---- JSON extensions ----

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null

    private fun JsonObject.optStringOrNull(name: String): String? {
        val value = get(name) ?: return null
        if (value.isJsonNull) return null
        return runCatching { value.asString }.getOrNull()
    }
}
