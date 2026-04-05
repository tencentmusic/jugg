package com.sickworm.intellij.jugg.mcp.actions

import com.google.gson.JsonParser
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.mcp.*
import com.sickworm.intellij.jugg.mcp.layout.FigmaLayoutVerifier
import com.sickworm.intellij.jugg.mcp.layout.model.AndroidNode
import com.sickworm.intellij.jugg.mcp.layout.parser.FigmaJsonParser
import java.io.File

class FigmaLayoutVerifyMcpToolAction : McpToolAction {
    override val toolName: String = "figma_layout_verify"

    override val definition: McpToolDefinition = McpToolDefinition(
        name = toolName,
        description = "Verify Android layout against Figma design by auto-extracting spacing/alignment relations from Figma JSON and comparing with actual layout.",
        inputSchema = McpJsonSchemaObject(
            properties = mapOf(
                "projectDir" to McpToolSchemas.projectDirProperty,
                "figmaJsonPath" to McpJsonSchemaProperty(
                    type = "string",
                    description = "Path to Figma JSON file (from get_design_context)."
                ),
                "dpr" to McpJsonSchemaProperty(
                    type = "number",
                    description = "Device pixel ratio for Figma design. Default: 1.0"
                )
            ),
            required = listOf("projectDir", "figmaJsonPath"),
            additionalProperties = false
        ),
        outputSchema = McpToolSchemas.baseOutputSchema
    )

    override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
        val logger = runtime.logger.getInstance("FigmaLayoutVerifyMcpToolAction")
        val figmaJsonPath = arguments["figmaJsonPath"] as? String
            ?: return McpToolResult.internalErrorResult(toolName, "figmaJsonPath is required")
        val dpr = (arguments["dpr"] as? Number)?.toFloat() ?: 1f

        // Dump android layout internally; passthrough any dump errors directly
        val dumpResult = LayoutDumpHelper.dump(runtime, toolName)
        if (dumpResult.status != McpToolStatus.OK) {
            return dumpResult
        }
        @Suppress("UNCHECKED_CAST")
        val androidJsonPath = (dumpResult.data as Map<String, Any>)["file"] as? String
            ?: return McpToolResult.internalErrorResult(toolName, "layout_dump did not return a file path")

        try {
            // Validate and parse Figma JSON
            val parser = FigmaJsonParser()
            val figmaJson = try {
                JsonParser.parseString(File(figmaJsonPath).readText()).asJsonObject
            } catch (e: Exception) {
                return McpToolResult.internalErrorResult(toolName, "Failed to parse Figma JSON: ${e.message}")
            }

            val validation = parser.validate(figmaJson)
            if (!validation.isValid) {
                return McpToolResult(
                    status = McpToolStatus.ERROR,
                    message = "Invalid Figma JSON format: ${validation.error}",
                    data = mapOf<String, Any>(
                        "error" to (validation.error ?: ""),
                        "expectedFormat" to (validation.expectedFormat ?: "")
                    ),
                    artifacts = emptyList(),
                    errorCode = "INVALID_FIGMA_FORMAT"
                )
            }

            // Parse Android JSON
            val androidJson = JsonParser.parseString(File(androidJsonPath).readText()).asJsonObject
            val androidNodes = parseAndroidNodes(androidJson)
            val deviceInfo = androidJson.getAsJsonObject("deviceInfo")
            val androidScreenSize = intArrayOf(
                deviceInfo.get("screenWidth").asInt,
                deviceInfo.get("screenHeight").asInt
            )

            // Parse Figma JSON to get screen size
            val figmaScreenSize = if (figmaJson.has("layout")) {
                val layout = figmaJson.getAsJsonArray("layout")
                intArrayOf(layout[2].asInt, layout[3].asInt)
            } else {
                val bounds = figmaJson.getAsJsonArray("bounds")
                intArrayOf(bounds[2].asInt, bounds[3].asInt)
            }

            // Run verification
            val verifier = FigmaLayoutVerifier(dpr)
            val report = verifier.verify(figmaJsonPath, androidNodes, figmaScreenSize, androidScreenSize)

            val data = mapOf(
                "total" to report.total,
                "passed" to report.passed,
                "failed" to report.failed,
                "results" to report.results.map { result ->
                    mapOf(
                        "type" to result.type,
                        "description" to result.description,
                        "match" to result.result.match,
                        "expected" to result.result.expected,
                        "actual" to result.result.actual,
                        "diff" to result.result.diff
                    )
                }
            )

            return McpToolResult(
                status = McpToolStatus.OK,
                message = "Verified ${report.total} relations: ${report.passed} passed, ${report.failed} failed",
                data = data,
                artifacts = emptyList(),
                errorCode = null
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
