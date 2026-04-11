package com.sickworm.intellij.jugg.mcp.actions

import com.google.gson.JsonParser
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.mcp.*
import com.sickworm.intellij.jugg.mcp.layout.matcher.ElementMatcher
import com.sickworm.intellij.jugg.mcp.layout.model.AndroidNode
import com.sickworm.intellij.jugg.mcp.layout.model.FigmaNode
import java.io.File

class UiFindMcpToolAction : McpToolAction {
    override val toolName: String = "view-locate"

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Locate a UI element and return its spatial position and size (bounds, center). " +
            "Data comes from a layout snapshot (layout-dump). Uses fuzzy matching with IoU algorithm. " +
            "✅ Use for: spacing calculation, alignment check, confirming where an element is on screen. " +
            "❌ Do NOT use for: text content, colors, maxLines, ellipsize, or any View-internal property " +
            "not visible in the layout tree — use view-inspect instead.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
                "target" to McpJsonSchemaProperty(
                    type = "object",
                    description = "Element selector.",
                    properties = mapOf(
                        "text" to McpJsonSchemaProperty(type = "string"),
                        "resourceId" to McpJsonSchemaProperty(type = "string"),
                        "contentDesc" to McpJsonSchemaProperty(type = "string")
                    )
                ),
                "figmaNode" to McpJsonSchemaProperty(
                    type = "object",
                    description = "Optional Figma node for fuzzy matching (id, name, bounds)."
                )
            ),
            required = listOf("projectDir", "target"),
            additionalProperties = false
        ),
        outputSchema = McpToolSchemas.baseOutputSchema
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        val logger = runtime.logger.getInstance("UiFindMcpToolAction")
        val target = arguments["target"] as? Map<*, *>
            ?: return McpToolResult.internalErrorResult(toolName, "target is required")

        try {
            val dumpAction = LayoutDumpMcpToolAction()
            val dumpResult = dumpAction.execute(arguments, runtime)

            if (dumpResult.status != McpToolStatus.OK) {
                return dumpResult
            }

            val dataMap = dumpResult.data as? Map<*, *>
                ?: return McpToolResult.internalErrorResult(toolName, "layout_dump returned invalid data")
            val layoutFilePath = dataMap["file"] as? String
                ?: return McpToolResult.internalErrorResult(toolName, "layout_dump did not return file path")
            val layoutFile = File(layoutFilePath)
            val layoutJson = JsonParser.parseString(layoutFile.readText()).asJsonObject
            val androidNodes = parseAndroidNodes(layoutJson)

            val text = target["text"] as? String
            val resourceId = target["resourceId"] as? String
            val contentDesc = target["contentDesc"] as? String

            val matched = androidNodes.find { node ->
                (text != null && node.text == text) ||
                (resourceId != null && node.id == resourceId) ||
                (contentDesc != null && node.id == contentDesc)
            }

            if (matched != null) {
                val data = mapOf(
                    "found" to true,
                    "bounds" to matched.bounds.toList(),
                    "position" to mapOf("x" to matched.bounds[0], "y" to matched.bounds[1]),
                    "size" to mapOf(
                        "width" to (matched.bounds[2] - matched.bounds[0]),
                        "height" to (matched.bounds[3] - matched.bounds[1])
                    ),
                    "className" to matched.className
                )
                return McpToolResult(
                    status = McpToolStatus.OK,
                    message = "Element found",
                    data = data,
                    artifacts = emptyList(),
                    errorCode = null
                )
            }

            return McpToolResult(
                status = McpToolStatus.ERROR,
                message = "Element not found",
                data = mapOf("found" to false),
                artifacts = emptyList(),
                errorCode = "ELEMENT_NOT_FOUND"
            )
        } catch (e: Exception) {
            logger.warn("$toolName failed: ${e.message}", e)
            return McpToolResult.internalErrorResult(toolName, e.message ?: "unknown error")
        }
    }

    private fun parseAndroidNodes(json: com.google.gson.JsonObject): List<AndroidNode> {
        val nodes = mutableListOf<AndroidNode>()
        val windows = json.getAsJsonArray("windows")
        windows.forEach { windowElement ->
            val window = windowElement.asJsonObject
            val root = window.getAsJsonObject("root")
            collectNodes(root, nodes)
        }
        return nodes
    }

    private fun collectNodes(node: com.google.gson.JsonObject, result: MutableList<AndroidNode>) {
        val className = node.get("className")?.asString ?: ""
        val id = node.get("id")?.asString
        val text = node.get("text")?.asString
        val boundsArray = node.getAsJsonArray("bounds")
        val bounds = IntArray(4) { boundsArray[it].asInt }

        result.add(AndroidNode(className, id, text, bounds))

        node.getAsJsonArray("children")?.forEach { child ->
            collectNodes(child.asJsonObject, result)
        }
    }
}
