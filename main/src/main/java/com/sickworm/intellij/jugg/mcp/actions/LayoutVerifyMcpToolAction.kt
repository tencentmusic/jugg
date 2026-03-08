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
import com.sickworm.intellij.jugg.mcp.viewhierarchy.LayoutDumpResult
import com.sickworm.intellij.jugg.mcp.viewhierarchy.MatchCandidate
import com.sickworm.intellij.jugg.mcp.viewhierarchy.VerifyResult
import com.sickworm.intellij.jugg.mcp.viewhierarchy.ViewHierarchyClient
import com.sickworm.intellij.jugg.platform.PlatformApi
import com.sickworm.intellij.jugg.project.JuggPathManager
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * LayoutVerifyMcpToolAction verifies UI element properties or relations with
 * two internal modes:
 *  - auto dump (default behavior)
 *  - live query (used when any property check requires live-only data)
 * All numeric values (bounds, padding, spacing) are always in dp.
 */
class LayoutVerifyMcpToolAction : McpToolAction {
    override val toolName: String = "layout_verify"

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Verify UI element properties or relations. " +
            "Tool fetches the latest layout snapshot automatically. " +
            "Use checks array for batch verification. All numeric values are in dp. " +
            "live-only properties (e.g. textSizeSp) are verified via live query automatically.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
                "checksFile" to McpJsonSchemaProperty(
                    type = "string",
                    description = "Optional absolute path to JSON file containing checks. Used when inline checks is omitted.",
                ),
                "checks" to McpJsonSchemaProperty(
                    type = "array",
                    description = "Batch checks. All numeric values in dp.",
                    items = McpJsonSchemaProperty(
                        type = "object",
                        properties = mapOf(
                            "target" to McpJsonSchemaProperty(
                                type = "object",
                                description = "Element selector.",
                                properties = mapOf(
                                    "resourceId" to McpJsonSchemaProperty(type = "string"),
                                    "text" to McpJsonSchemaProperty(type = "string"),
                                    "contentDesc" to McpJsonSchemaProperty(type = "string"),
                                    "className" to McpJsonSchemaProperty(type = "string"),
                                ),
                                additionalProperties = false,
                            ),
                            "type" to McpJsonSchemaProperty(
                                type = "string",
                                description = "property: single-element check. spacing: gap check on axis x/y. " +
                                    "alignment: center alignment on axis x/y. " +
                                    "overlap: checks whether two elements overlap. Default: PASS=no overlap (asserts elements do NOT overlap). Set expectOverlap=true to reverse: PASS=elements DO overlap. " +
                                    "containment: PASS=target(child) is fully inside target2(parent). " +
                                    "order: PASS=target before target2.",
                                `enum` = listOf("property", "spacing", "alignment", "overlap", "containment", "order"),
                            ),
                            "property" to McpJsonSchemaProperty(
                                type = "string",
                                description = "For type=property. textColor/backgroundColor use #AARRGGBB (e.g. #FF1976D2). " +
                                    "textSizeSp and backgroundColor are live-only. " +
                                    "backgroundColor only works for solid-color backgrounds (ColorDrawable). " +
                                    "Aliases: width/height/left/top/right/bottom map to bounds.*.",
                                `enum` = PROPERTY_SCHEMA_VALUES,
                            ),
                            "op" to McpJsonSchemaProperty(
                                type = "string",
                                description = "Comparison operator. Default: eq. " +
                                    "For type=spacing: supports eq/neq/gte/lte/gt/lt. " +
                                    "When op is provided, tolerance must be omitted.",
                                `enum` = listOf("eq", "neq", "gte", "lte", "gt", "lt", "contains", "matches"),
                            ),
                            "value" to McpJsonSchemaProperty(
                                type = "string",
                                description = "Expected value. textColor: #AARRGGBB format.",
                            ),
                            "target2" to McpJsonSchemaProperty(
                                type = "object",
                                description = "Second element for relation checks. " +
                                    "containment: target=CHILD (inner element), target2=PARENT (outer container). " +
                                    "PASS means target is fully inside target2. " +
                                    "spacing/order: target is the 'from' element, target2 is the 'to' element.",
                                properties = mapOf(
                                    "resourceId" to McpJsonSchemaProperty(type = "string"),
                                    "text" to McpJsonSchemaProperty(type = "string"),
                                    "contentDesc" to McpJsonSchemaProperty(type = "string"),
                                    "className" to McpJsonSchemaProperty(type = "string"),
                                ),
                                additionalProperties = false,
                            ),
                            "axis" to McpJsonSchemaProperty(
                                type = "string",
                                description = "For spacing/alignment/order. " +
                                    "x = horizontal axis, y = vertical axis. " +
                                    "For alignment: axis=x checks X-center, axis=y checks Y-center.",
                                `enum` = listOf("x", "y"),
                            ),
                            "direction" to McpJsonSchemaProperty(
                                type = "string",
                                description = "Deprecated legacy field for spacing/alignment/order. " +
                                    "Use axis instead. direction=vertical/horizontal will be mapped internally.",
                                `enum` = listOf("horizontal", "vertical"),
                            ),
                            "expected" to McpJsonSchemaProperty(
                                type = "number",
                                description = "Expected gap/value in dp. Only for type=spacing.",
                            ),
                            "tolerance" to McpJsonSchemaProperty(
                                type = "number",
                                description = "Deviation in dp. Only for type=spacing when op is omitted. " +
                                    "NOT supported in type=property — use gte+lte instead.",
                            ),
                            "expectOverlap" to McpJsonSchemaProperty(
                                type = "boolean",
                                description = "Only for type=overlap. Default false (PASS=no overlap). Set true to assert elements DO overlap (PASS=overlap exists).",
                            ),
                        ),
                        additionalProperties = false,
                    ),
                ),
            ),
            required = listOf("projectDir"),
            additionalProperties = false,
        ),
        outputSchema = McpToolSchemas.baseOutputSchema.copy(
            properties = McpToolSchemas.baseOutputSchema.properties + mapOf(
                "data" to McpJsonSchemaProperty(
                    type = "object",
                    properties = mapOf(
                        "result" to McpJsonSchemaProperty(type = "string", `enum` = listOf("PASS", "PARTIAL_FAIL", "FAIL", "ERROR")),
                        "message" to McpJsonSchemaProperty(type = "string"),
                        "checkResults" to McpJsonSchemaProperty(type = "array"),
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
        val checksFile = arguments["checksFile"] as? String
        val legacyTarget = arguments["target"] as? Map<String, Any?>
        val inlineChecks = (arguments["checks"] as? List<*>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()

        val fileChecks = if (inlineChecks.isEmpty()) {
            try {
                loadChecksFromFile(checksFile)
            } catch (e: IllegalArgumentException) {
                return invalidParams(e.message ?: "layout_verify failed: invalid checksFile")
            }
        } else {
            emptyList()
        }
        val checks = if (inlineChecks.isNotEmpty()) inlineChecks else fileChecks
        if (checks.isEmpty()) {
            return invalidParams("layout_verify failed: checks is required and must be non-empty")
        }
        if (checks.any { (it["type"] as? String).isNullOrBlank() }) {
            return invalidParams("layout_verify failed: every check requires type")
        }
        if (checks.any { isPropertyCheck(it) && (it["property"] as? String).isNullOrBlank() }) {
            return invalidParams("layout_verify failed: type=property check requires property")
        }
        val invalidTargetIndex = checks.indexOfFirst { !isValidSelector(resolveCheckTarget(it, legacyTarget) ?: emptyMap()) }
        if (invalidTargetIndex >= 0) {
            return invalidParams("layout_verify failed: check[${invalidTargetIndex + 1}] requires valid target")
        }
        if (checks.any { !isPropertyCheck(it) && !isValidSelector((it["target2"] as? Map<String, Any?>) ?: emptyMap()) }) {
            return invalidParams("layout_verify failed: relation check requires valid target2 selector")
        }
        if (checks.any {
                isSpacingCheck(it) &&
                    !((it["op"] as? String).isNullOrBlank()) &&
                    it.containsKey("tolerance")
            }
        ) {
            return invalidParams("layout_verify failed: type=spacing check 'op' and 'tolerance' are mutually exclusive")
        }

        val shouldUseLive = checks.any { isLiveOnlyCheck(it) }
        if (!dumpFile.isNullOrBlank()) {
            return if (shouldUseLive) {
                executeLiveQueryMode(runtime, checks, legacyTarget, logger)
            } else {
                executeDumpFileMode(dumpFile, checks, legacyTarget, logger)
            }
        }
        return if (shouldUseLive) {
            executeLiveQueryMode(runtime, checks, legacyTarget, logger)
        } else {
            executeAutoDumpMode(runtime, checks, legacyTarget, logger)
        }
    }

    // ---- dumpFile mode ----

    private fun executeAutoDumpMode(
        runtime: IMcpRuntime,
        checks: List<Map<String, Any?>>,
        legacyTarget: Map<String, Any?>?,
        logger: com.intellij.openapi.diagnostic.Logger,
    ): McpToolResult {
        val selected = resolveOnlineDevice(runtime) ?: return noDeviceResult()
        val packageName = resolvePackageName(runtime)
            ?: return McpToolResult.internalErrorResult(toolName, "unable to resolve package name")
        return try {
            val client = ViewHierarchyClient(selected.adb, packageName)
            val dumpResult = client.dumpLayout(rootLayout = null, excludeGone = true, topWindowOnly = true)
                ?: return McpToolResult.internalErrorResult(toolName, "ViewHierarchy server is unavailable or returned invalid response")
            val autoDumpFile = saveAutoDumpFile(runtime, selected.adb, dumpResult)
                ?: return McpToolResult.internalErrorResult(toolName, "failed to fetch layout dump from ViewHierarchy server")
            executeDumpFileMode(autoDumpFile.absolutePath, checks, legacyTarget, logger)
        } catch (e: Exception) {
            logger.warn("layout_verify auto dump mode failed: ${e.message}", e)
            McpToolResult.internalErrorResult(toolName, e.message ?: "unknown error")
        }
    }

    private fun executeDumpFileMode(
        dumpFilePath: String,
        checks: List<Map<String, Any?>>,
        legacyTarget: Map<String, Any?>?,
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

            val items = checks.mapIndexed { index, check ->
                val targetSelector = resolveCheckTarget(check, legacyTarget)
                    ?: return invalidParams("layout_verify failed: check[${index + 1}] requires valid target")
                val targetNode = findNodeBySelector(allNodes, targetSelector)
                    ?: return errorResult(
                        message = "target not found for check[${index + 1}]: ${selectorDesc(targetSelector)}",
                        candidates = buildCandidates(allNodes, targetSelector),
                        checkIndex = index + 1,
                    )
                val verifyResult = if (isPropertyCheck(check)) {
                    assertDumpNode(targetNode, check, density)
                } else {
                    val target2Selector = check["target2"] as? Map<String, Any?>
                        ?: return invalidParams("layout_verify failed: relation check requires target2")
                    val target2Node = findNodeBySelector(allNodes, target2Selector)
                        ?: return errorResult(
                            message = "target2 not found for check[${index + 1}]: ${selectorDesc(target2Selector)}",
                            candidates = buildCandidates(allNodes, target2Selector),
                            checkIndex = index + 1,
                        )
                    relationDumpNodes(targetNode, target2Node, check, density)
                }
                VerifyItem(index = index + 1, result = verifyResult.result, message = verifyResult.message)
            }
            toAggregatedMcpResult(items)
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
        val rawProperty = assert["property"] as? String ?: return VerifyResult("ERROR", "assert.property is required")
        val property = normalizeProperty(rawProperty)
        val op = assert["op"] as? String ?: "eq"
        val value = assert["value"]

        if (assert.containsKey("tolerance")) {
            return propertyToleranceErrorResult()
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
                val actual = (node.optStringOrNull("textColor") ?: "#FF000000").uppercase()
                val expected = (value as? String ?: "").uppercase()
                assertStr(actual, op, expected, "textColor")
            }
            "alpha" -> {
                val actual = node.get("alpha")?.runCatching { asDouble }?.getOrDefault(1.0) ?: 1.0
                val expected = parseDoubleValue(value, default = 1.0)
                assertDouble(actual, op, expected, "alpha")
            }
            "bounds.width" -> assertBoundsDp(boundsWidth(node), op, value, density, "bounds.width")
            "bounds.height" -> assertBoundsDp(boundsHeight(node), op, value, density, "bounds.height")
            "bounds.left" -> assertBoundsDp(boundsLeft(node), op, value, density, "bounds.left")
            "bounds.top" -> assertBoundsDp(boundsTop(node), op, value, density, "bounds.top")
            "bounds.right" -> assertBoundsDp(boundsRight(node), op, value, density, "bounds.right")
            "bounds.bottom" -> assertBoundsDp(boundsBottom(node), op, value, density, "bounds.bottom")
            "padding.left" -> assertBoundsDp(paddingAt(node, 0), op, value, density, "padding.left")
            "padding.top" -> assertBoundsDp(paddingAt(node, 1), op, value, density, "padding.top")
            "padding.right" -> assertBoundsDp(paddingAt(node, 2), op, value, density, "padding.right")
            "padding.bottom" -> assertBoundsDp(paddingAt(node, 3), op, value, density, "padding.bottom")
            else -> unsupportedPropertyErrorResult(rawProperty, node, density)
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

    private fun assertBoundsDp(actualPx: Int, op: String, value: Any?, density: Double, property: String): VerifyResult {
        val actual = if (density > 0) Math.round(actualPx / density).toInt() else actualPx
        // Accept both Number and String to tolerate MCP callers that serialize numbers as strings.
        val expected = when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: 0
            else -> 0
        }
        val pass = when (op) {
            "gte" -> actual >= expected
            "lte" -> actual <= expected
            "gt" -> actual > expected
            "lt" -> actual < expected
            "neq" -> actual != expected
            else -> actual == expected
        }
        val msg = "$property = ${actual}dp (expected: $op ${expected}dp)"
        return if (pass) VerifyResult("PASS", msg, actual, expected, "dp")
        else VerifyResult("FAIL", msg, actual, expected, "dp")
    }

    private fun normalizeProperty(property: String): String {
        return PROPERTY_ALIASES[property.lowercase()] ?: property
    }

    private fun parseDoubleValue(value: Any?, default: Double): Double {
        return when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull() ?: default
            else -> default
        }
    }

    private fun unsupportedPropertyErrorResult(inputProperty: String, node: JsonObject, density: Double): VerifyResult {
        val suggestion = suggestProperty(inputProperty)
        val suggestionText = if (suggestion == null) "" else " did you mean \"$suggestion\"?"
        val observedText = buildObservedBoundsHint(node, density)
        val message = buildString {
            append("unsupported property in dumpFile mode: ")
            append(inputProperty)
            append(suggestionText)
            append(". supported properties: ")
            append(PROPERTY_SCHEMA_VALUES.joinToString(", "))
            if (observedText.isNotBlank()) {
                append(". ")
                append(observedText)
            }
        }
        return VerifyResult("ERROR", message)
    }

    private fun buildObservedBoundsHint(node: JsonObject, density: Double): String {
        val widthDp = if (density > 0) Math.round(boundsWidth(node) / density).toInt() else boundsWidth(node)
        val heightDp = if (density > 0) Math.round(boundsHeight(node) / density).toInt() else boundsHeight(node)
        return "reference bounds.width = ${widthDp}dp, bounds.height = ${heightDp}dp"
    }

    private fun suggestProperty(inputProperty: String): String? {
        val candidate = inputProperty.trim().lowercase()
        if (candidate.isEmpty()) {
            return null
        }
        val containsMatch = PROPERTY_SCHEMA_VALUES.firstOrNull { item ->
            item.equals(candidate, ignoreCase = true) ||
                item.contains(candidate, ignoreCase = true) ||
                candidate.contains(item, ignoreCase = true)
        }
        if (containsMatch != null) {
            return containsMatch
        }
        val best = PROPERTY_SCHEMA_VALUES.minByOrNull { levenshtein(candidate, it.lowercase()) } ?: return null
        return if (levenshtein(candidate, best.lowercase()) <= MAX_PROPERTY_DISTANCE_FOR_HINT) best else null
    }

    @Suppress("UNCHECKED_CAST")
    private fun relationDumpNodes(target: JsonObject, target2: JsonObject, relation: Map<String, Any?>, density: Double): VerifyResult {
        val type = relation["type"] as? String ?: return VerifyResult("ERROR", "relation.type is required")
        val axis = resolveRelationAxis(type, relation)
        val expected = parseIntValue(relation["expected"], default = 0)
        val tolerance = parseIntValue(relation["tolerance"], default = 0)
        val op = (relation["op"] as? String)?.ifBlank { null } ?: "eq"
        val hasTolerance = relation.containsKey("tolerance")

        return when (type) {
            "spacing" -> {
                if (axis == null) {
                    return VerifyResult("ERROR", "unsupported axis for spacing: ${relation["axis"]}. Use axis=x or axis=y.")
                }
                if (hasTolerance && !((relation["op"] as? String).isNullOrBlank())) {
                    return VerifyResult("ERROR", "type=spacing check 'op' and 'tolerance' are mutually exclusive")
                }
                val (aLeft, aTop, aRight, aBottom) = getBounds(target)
                val (bLeft, bTop, bRight, bBottom) = getBounds(target2)
                val spacingPx = if (axis == "y") {
                    val topOfLower = maxOf(aTop, bTop)
                    val bottomOfUpper = minOf(aBottom, bBottom)
                    topOfLower - bottomOfUpper
                } else {
                    val leftOfRight = maxOf(aLeft, bLeft)
                    val rightOfLeft = minOf(aRight, bRight)
                    leftOfRight - rightOfLeft
                }
                val actual = if (density > 0) Math.round(spacingPx / density).toInt() else spacingPx
                val boundsInfo = " [target:[$aLeft,$aTop,$aRight,$aBottom], target2:[$bLeft,$bTop,$bRight,$bBottom]]"
                val spacingResult = evaluateSpacingResult(actual, expected, op, tolerance, hasTolerance)
                if (spacingResult.errorMessage != null) {
                    return VerifyResult("ERROR", spacingResult.errorMessage)
                }
                val msg = if (hasTolerance) {
                    "spacing (axis=$axis) = ${actual}dp (expected: ${expected}dp ±${tolerance}dp)$boundsInfo"
                } else {
                    "spacing (axis=$axis) = ${actual}dp (expected: $op ${expected}dp)$boundsInfo"
                }
                val pass = spacingResult.pass
                if (pass) VerifyResult("PASS", msg, actual, expected, "dp")
                else VerifyResult("FAIL", msg, actual, expected, "dp")
            }
            "alignment" -> {
                if (axis == null) {
                    return VerifyResult("ERROR", "unsupported axis for alignment: ${relation["axis"]}. Use axis=x or axis=y.")
                }
                val (aLeft, aTop, aRight, aBottom) = getBounds(target)
                val (bLeft, bTop, bRight, bBottom) = getBounds(target2)
                val pass: Boolean
                val desc: String
                if (axis == "x") {
                    val centerA = (aLeft + aRight) / 2
                    val centerB = (bLeft + bRight) / 2
                    pass = Math.abs(centerA - centerB) <= 2
                    desc = "axis=x -> X-center check: $centerA vs $centerB"
                } else {
                    val centerA = (aTop + aBottom) / 2
                    val centerB = (bTop + bBottom) / 2
                    pass = Math.abs(centerA - centerB) <= 2
                    desc = "axis=y -> Y-center check: $centerA vs $centerB"
                }
                val msg = "alignment ($desc)"
                if (pass) VerifyResult("PASS", msg) else VerifyResult("FAIL", msg)
            }
            "overlap" -> {
                val (aLeft, aTop, aRight, aBottom) = getBounds(target)
                val (bLeft, bTop, bRight, bBottom) = getBounds(target2)
                val overlaps = aLeft < bRight && aRight > bLeft && aTop < bBottom && aBottom > bTop
                val expectOverlap = relation["expectOverlap"] as? Boolean ?: false
                val pass = if (expectOverlap) overlaps else !overlaps
                val msg = "overlap (expectOverlap=$expectOverlap): " +
                    if (overlaps) "elements overlap" else "no overlap"
                if (pass) VerifyResult("PASS", msg) else VerifyResult("FAIL", msg)
            }
            "containment" -> {
                val (aLeft, aTop, aRight, aBottom) = getBounds(target)
                val (bLeft, bTop, bRight, bBottom) = getBounds(target2)
                val contained = aLeft >= bLeft && aTop >= bTop && aRight <= bRight && aBottom <= bBottom
                val msg = "containment: target(child) " +
                    (if (contained) "is inside" else "is NOT inside") +
                    " target2(parent)"
                if (contained) VerifyResult("PASS", msg) else VerifyResult("FAIL", msg)
            }
            "order" -> {
                if (axis == null) {
                    return VerifyResult("ERROR", "unsupported axis for order: ${relation["axis"]}. Use axis=x or axis=y.")
                }
                val (aLeft, aTop, _, _) = getBounds(target)
                val (bLeft, bTop, _, _) = getBounds(target2)
                val inOrder = if (axis == "y") aTop < bTop else aLeft < bLeft
                val msg = "order (axis=$axis): ${if (inOrder) "correct" else "incorrect"}"
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

    private fun buildCandidates(nodes: List<JsonObject>, selector: Map<String, Any?>): List<CandidateHint> {
        return nodes.mapNotNull { node ->
            val candidate = MatchCandidate(
                text = node.optStringOrNull("text").orEmpty(),
                resourceId = node.optStringOrNull("id").orEmpty(),
                contentDesc = node.optStringOrNull("contentDesc").orEmpty(),
                className = node.optStringOrNull("className").orEmpty(),
                bounds = node.get("bounds")?.takeIf { it.isJsonArray }?.asJsonArray?.map { runCatching { it.asInt }.getOrDefault(0) },
                centerX = -1,
                centerY = -1,
            )
            scoreCandidate(selector, candidate)
        }.sortedByDescending { it.score }.take(MAX_CANDIDATES)
    }

    // ---- live query mode ----

    private fun executeLiveQueryMode(
        runtime: IMcpRuntime,
        checks: List<Map<String, Any?>>,
        legacyTarget: Map<String, Any?>?,
        logger: com.intellij.openapi.diagnostic.Logger,
    ): McpToolResult {
        val selected = resolveOnlineDevice(runtime) ?: return noDeviceResult()
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
            val items = mutableListOf<VerifyItem>()
            checks.forEachIndexed { index, check ->
                val checkTarget = resolveCheckTarget(check, legacyTarget)
                    ?: return invalidParams("layout_verify failed: check[${index + 1}] requires valid target")
                if (isPropertyCheck(check) && check.containsKey("tolerance")) {
                    items.add(VerifyItem(index + 1, "ERROR", propertyToleranceErrorResult().message))
                    return@forEachIndexed
                }
                val verifyResult = client.verify(buildLiveParams(checkTarget, check))
                    ?: return McpToolResult.internalErrorResult(toolName, "ViewHierarchy server unavailable")
                items.add(VerifyItem(index + 1, verifyResult.result, verifyResult.message))
            }
            toAggregatedMcpResult(items)
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
        check: Map<String, Any?>,
    ): Map<String, Any?> {
        val params = mutableMapOf<String, Any?>()
        params["target"] = target
        if (isPropertyCheck(check)) {
            val assert = check.toMutableMap().apply {
                remove("type")
                remove("target")
            }
            params["assert"] = assert
            return params
        }
        val target2 = check["target2"] as? Map<String, Any?>
            ?: return params
        val relation = check.toMutableMap().apply {
            remove("target")
            remove("target2")
        }
        normalizeRelationParamsForLive(relation)
        params["target2"] = target2
        params["relation"] = relation
        return params
    }

    // ---- Result conversion ----

    private fun toAggregatedMcpResult(items: List<VerifyItem>): McpToolResult {
        val aggregated = aggregateResult(items)
        val status = if (aggregated == "PASS") McpToolStatus.OK else McpToolStatus.ERROR
        val summary = if (items.size == 1) {
            items.first().message
        } else {
            when (aggregated) {
                "PASS" -> "all checks passed"
                "PARTIAL_FAIL" -> "some checks failed"
                "FAIL" -> "all checks failed"
                else -> "at least one check returned error"
            }
        }
        val data = mutableMapOf<String, Any>(
            "result" to aggregated,
            "message" to summary,
            "checkResults" to items.map {
                mapOf(
                    "index" to it.index,
                    "result" to it.result,
                    "message" to it.message,
                )
            },
        )
        return McpToolResult(
            status = status,
            message = "$aggregated: $summary",
            data = data,
            artifacts = emptyList(),
            errorCode = if (aggregated == "ERROR") McpErrorCode.MCP_INTERNAL_ERROR else null,
        )
    }

    private fun errorResult(message: String, candidates: List<CandidateHint>, checkIndex: Int = 1): McpToolResult {
        val data = mutableMapOf<String, Any>(
            "result" to "ERROR",
            "message" to message,
            "checkResults" to listOf(
                mapOf(
                    "index" to checkIndex,
                    "result" to "ERROR",
                    "message" to message,
                )
            )
        )
        if (candidates.isNotEmpty()) {
            data["candidates"] = candidates.map { c ->
                mapOf(
                    "text" to c.candidate.text,
                    "resourceId" to c.candidate.resourceId,
                    "contentDesc" to c.candidate.contentDesc,
                    "className" to c.candidate.className,
                    "score" to c.score,
                    "reason" to c.reason,
                )
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
        selector["className"]?.let { parts.add("className=$it") }
        return parts.joinToString(", ")
    }

    private fun scoreCandidate(selector: Map<String, Any?>, candidate: MatchCandidate): CandidateHint? {
        var score = 0.0
        val reasons = mutableListOf<String>()
        var hasSelector = false

        val selectorId = selector["resourceId"] as? String
        if (!selectorId.isNullOrBlank()) {
            hasSelector = true
            val selectorShort = shortId(selectorId).orEmpty()
            val candidateShort = shortId(candidate.resourceId).orEmpty()
            when {
                candidateShort == selectorShort -> {
                    score += 100.0
                    reasons.add("resourceId exact match")
                }
                candidateShort.startsWith(selectorShort) || selectorShort.startsWith(candidateShort) -> {
                    score += 80.0
                    reasons.add("resourceId prefix match")
                }
                candidateShort.contains(selectorShort) || selectorShort.contains(candidateShort) -> {
                    score += 65.0
                    reasons.add("resourceId contains match")
                }
                else -> {
                    val distance = levenshtein(selectorShort, candidateShort)
                    score += (50.0 - distance * 2).coerceAtLeast(0.0)
                    reasons.add("resourceId edit distance=$distance")
                }
            }
        }

        val textScore = accumulateTextScore(selector["text"] as? String, candidate.text, "text", reasons, score)
        hasSelector = textScore.first || hasSelector
        score = textScore.second

        val descScore = accumulateTextScore(selector["contentDesc"] as? String, candidate.contentDesc, "contentDesc", reasons, score)
        hasSelector = descScore.first || hasSelector
        score = descScore.second

        val classScore = accumulateTextScore(selector["className"] as? String, candidate.className, "className", reasons, score)
        hasSelector = classScore.first || hasSelector
        score = classScore.second

        if (!hasSelector) {
            return null
        }
        return CandidateHint(candidate = candidate, score = score, reason = reasons.joinToString("; "))
    }

    private fun accumulateTextScore(
        selectorValue: String?,
        candidateValue: String,
        label: String,
        reasons: MutableList<String>,
        score: Double,
    ): Pair<Boolean, Double> {
        if (selectorValue.isNullOrBlank()) {
            return false to score
        }
        val nextScore = when {
            selectorValue == candidateValue -> {
                reasons.add("$label exact match")
                score + 70.0
            }
            candidateValue.contains(selectorValue, ignoreCase = true) || selectorValue.contains(candidateValue, ignoreCase = true) -> {
                reasons.add("$label contains match")
                score + 50.0
            }
            else -> {
                val distance = levenshtein(selectorValue.lowercase(), candidateValue.lowercase())
                reasons.add("$label edit distance=$distance")
                score + (40.0 - distance * 2).coerceAtLeast(0.0)
            }
        }
        return true to nextScore
    }

    private fun levenshtein(left: String, right: String): Int {
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length
        val dp = IntArray(right.length + 1) { it }
        for (i in 1..left.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..right.length) {
                val temp = dp[j]
                val cost = if (left[i - 1] == right[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + cost)
                prev = temp
            }
        }
        return dp[right.length]
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolveCheckTarget(
        check: Map<String, Any?>,
        legacyTarget: Map<String, Any?>?,
    ): Map<String, Any?>? {
        return (check["target"] as? Map<String, Any?>) ?: legacyTarget
    }

    private fun loadChecksFromFile(checksFilePath: String?): List<Map<String, Any?>> {
        if (checksFilePath.isNullOrBlank()) {
            return emptyList()
        }
        val file = File(checksFilePath)
        if (!file.exists()) {
            throw IllegalArgumentException("layout_verify failed: checksFile not found: $checksFilePath")
        }
        val content = file.readText(StandardCharsets.UTF_8)
        if (content.isBlank()) {
            throw IllegalArgumentException("layout_verify failed: checksFile is empty: $checksFilePath")
        }
        val root = runCatching { JsonParser.parseString(content) }.getOrElse {
            throw IllegalArgumentException("layout_verify failed: checksFile is not valid JSON")
        }
        return when {
            root.isJsonArray -> parseChecksArray(root)
            root.isJsonObject -> parseChecksObject(root.asJsonObject)
            else -> throw IllegalArgumentException("layout_verify failed: checksFile must be JSON array or object")
        }
    }

    private fun parseChecksObject(root: JsonObject): List<Map<String, Any?>> {
        if (root.has("target")) {
            throw IllegalArgumentException("layout_verify failed: root target is not supported in checksFile; use checks[i].target")
        }
        val checksElement = root.get("checks")
        val checks = when {
            checksElement == null -> emptyList()
            !checksElement.isJsonArray -> throw IllegalArgumentException("layout_verify failed: checksFile.checks must be an array")
            else -> parseChecksArray(checksElement)
        }
        return checks
    }

    private fun parseChecksArray(element: JsonElement): List<Map<String, Any?>> {
        val checksArray = element.asJsonArray
        return checksArray.mapIndexed { index, item ->
            val checkObject = item.asJsonObjectOrNull()
                ?: throw IllegalArgumentException("layout_verify failed: checksFile checks[$index] must be object")
            jsonObjectToMap(checkObject)
        }
    }

    private fun jsonObjectToMap(jsonObject: JsonObject): Map<String, Any?> {
        val map = linkedMapOf<String, Any?>()
        jsonObject.entrySet().forEach { entry ->
            map[entry.key] = jsonElementToValue(entry.value)
        }
        return map
    }

    private fun jsonElementToValue(element: JsonElement): Any? {
        if (element.isJsonNull) return null
        if (element.isJsonObject) return jsonObjectToMap(element.asJsonObject)
        if (element.isJsonArray) return element.asJsonArray.map { jsonElementToValue(it) }
        val primitive = element.asJsonPrimitive
        return when {
            primitive.isBoolean -> primitive.asBoolean
            primitive.isNumber -> primitive.asNumber
            else -> primitive.asString
        }
    }

    private fun aggregateResult(items: List<VerifyItem>): String {
        if (items.isEmpty()) return "ERROR"
        val results = items.map { it.result }
        return when {
            results.any { it == "ERROR" } -> "ERROR"
            results.all { it == "FAIL" } -> "FAIL"
            results.any { it == "PASS" } && results.any { it == "FAIL" } -> "PARTIAL_FAIL"
            results.all { it == "PASS" } -> "PASS"
            else -> "ERROR"
        }
    }

    private fun invalidParams(message: String): McpToolResult {
        return McpToolResult(
            status = McpToolStatus.ERROR,
            message = message,
            data = emptyMap<String, Any>(),
            artifacts = emptyList(),
            errorCode = McpErrorCode.MCP_INVALID_PARAMS,
        )
    }

    private fun isValidSelector(selector: Map<String, Any?>): Boolean {
        return !((selector["resourceId"] as? String).isNullOrBlank()
            && (selector["text"] as? String).isNullOrBlank()
            && (selector["contentDesc"] as? String).isNullOrBlank()
            && (selector["className"] as? String).isNullOrBlank())
    }

    private fun isLiveOnlyCheck(check: Map<String, Any?>): Boolean {
        if (!isPropertyCheck(check)) {
            return false
        }
        val property = check["property"] as? String ?: return false
        return normalizeProperty(property) in LIVE_ONLY_PROPERTIES
    }

    private fun isPropertyCheck(check: Map<String, Any?>): Boolean {
        return (check["type"] as? String) == PROPERTY_CHECK_TYPE
    }

    private fun isSpacingCheck(check: Map<String, Any?>): Boolean {
        return (check["type"] as? String) == "spacing"
    }

    private fun parseIntValue(value: Any?, default: Int): Int {
        return when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: default
            else -> default
        }
    }

    private fun resolveRelationAxis(type: String, relation: Map<String, Any?>): String? {
        val axisFromInput = (relation["axis"] as? String)?.trim()?.lowercase()
        if (axisFromInput != null) {
            return when (axisFromInput) {
                "x", "y" -> axisFromInput
                else -> null
            }
        }
        val direction = (relation["direction"] as? String)?.trim()?.lowercase()
        if (!direction.isNullOrBlank()) {
            return mapDirectionToAxis(type, direction)
        }
        // Preserve historical default when direction was omitted:
        // spacing/order -> horizontal branch, alignment -> horizontal branch.
        return when (type) {
            "spacing", "order" -> "x"
            "alignment" -> "y"
            else -> null
        }
    }

    private fun mapDirectionToAxis(type: String, direction: String): String {
        return when (type) {
            "alignment" -> if (direction == "vertical") "x" else "y"
            "spacing", "order" -> if (direction == "vertical") "y" else "x"
            else -> "x"
        }
    }

    private fun mapAxisToDirection(type: String, axis: String): String {
        return when (type) {
            "alignment" -> if (axis == "x") "vertical" else "horizontal"
            "spacing", "order" -> if (axis == "y") "vertical" else "horizontal"
            else -> "horizontal"
        }
    }

    private fun normalizeRelationParamsForLive(relation: MutableMap<String, Any?>) {
        val type = relation["type"] as? String ?: return
        val axis = resolveRelationAxis(type, relation) ?: return
        relation["axis"] = axis
        if ((relation["direction"] as? String).isNullOrBlank()) {
            relation["direction"] = mapAxisToDirection(type, axis)
        }
    }

    private data class SpacingEvaluation(
        val pass: Boolean,
        val errorMessage: String? = null,
    )

    private fun evaluateSpacingResult(
        actual: Int,
        expected: Int,
        op: String,
        tolerance: Int,
        hasTolerance: Boolean,
    ): SpacingEvaluation {
        if (hasTolerance) {
            return SpacingEvaluation(pass = Math.abs(actual - expected) <= tolerance)
        }
        val pass = when (op) {
            "eq" -> actual == expected
            "neq" -> actual != expected
            "gte" -> actual >= expected
            "lte" -> actual <= expected
            "gt" -> actual > expected
            "lt" -> actual < expected
            else -> return SpacingEvaluation(pass = false, errorMessage = "unsupported op for spacing: $op")
        }
        return SpacingEvaluation(pass = pass)
    }

    private fun propertyToleranceErrorResult(): VerifyResult {
        return VerifyResult(
            "ERROR",
            "type=property check does not support 'tolerance'. " +
                "To verify approximate values, use two separate checks with 'gte' and 'lte'. " +
                "For layout gap checks with tolerance, use type=spacing with tolerance."
        )
    }

    private fun saveAutoDumpFile(runtime: IMcpRuntime, adb: IDeviceAdb, dumpResult: LayoutDumpResult): File? {
        val projectDir = runtime.project.basePath ?: return null
        val dir = File(JuggPathManager(File(projectDir)).mcpFetchDir, "layout_verify")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val file = File(dir, "auto_${System.currentTimeMillis()}.json")
        val payloadJson = dumpResult.payloadJson
        val remoteFilePath = dumpResult.remoteFilePath
        if (!payloadJson.isNullOrBlank()) {
            file.writeText(payloadJson, StandardCharsets.UTF_8)
        } else if (!remoteFilePath.isNullOrBlank()) {
            adb.pull(remoteFilePath, file)
        }
        if (!file.exists() || file.length() <= 0L) {
            return null
        }
        return file
    }

    private fun noDeviceResult(): McpToolResult {
        return McpToolResult(
            status = McpToolStatus.ERROR,
            message = "layout_verify failed: No connected device is available.",
            data = emptyMap<String, Any>(),
            artifacts = emptyList(),
            errorCode = McpErrorCode.MCP_NO_DEVICE,
        )
    }

    private data class CandidateHint(
        val candidate: MatchCandidate,
        val score: Double,
        val reason: String,
    )

    private data class VerifyItem(
        val index: Int,
        val result: String,
        val message: String,
    )

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

    private fun assertDouble(actual: Double, op: String, expected: Double, property: String): VerifyResult {
        val pass = when (op) {
            "gte" -> actual >= expected - DOUBLE_EPSILON
            "lte" -> actual <= expected + DOUBLE_EPSILON
            "gt"  -> actual > expected + DOUBLE_EPSILON
            "lt"  -> actual < expected - DOUBLE_EPSILON
            "neq" -> Math.abs(actual - expected) >= DOUBLE_EPSILON
            else  -> Math.abs(actual - expected) < DOUBLE_EPSILON // eq
        }
        val msg = "$property = $actual (expected: $op $expected)"
        return if (pass) VerifyResult("PASS", msg, actual, expected)
        else VerifyResult("FAIL", msg, actual, expected)
    }

    companion object {
        private const val PROPERTY_CHECK_TYPE = "property"
        private val PROPERTY_ALIASES = mapOf(
            "width" to "bounds.width",
            "height" to "bounds.height",
            "left" to "bounds.left",
            "top" to "bounds.top",
            "right" to "bounds.right",
            "bottom" to "bounds.bottom",
        )
        private val PROPERTY_SCHEMA_VALUES = listOf(
            "exists",
            "visibility",
            "clickable",
            "enabled",
            "text",
            "textColor",
            "backgroundColor",
            "alpha",
            "width",
            "height",
            "left",
            "top",
            "right",
            "bottom",
            "bounds.width",
            "bounds.height",
            "bounds.left",
            "bounds.top",
            "bounds.right",
            "bounds.bottom",
            "padding.left",
            "padding.top",
            "padding.right",
            "padding.bottom",
            "textSizeSp",
        )
        private val LIVE_ONLY_PROPERTIES = setOf("textSizeSp", "backgroundColor")
        private const val MAX_CANDIDATES = 5
        private const val MAX_PROPERTY_DISTANCE_FOR_HINT = 3
        private const val DOUBLE_EPSILON = 0.001
    }
}
