package com.sickworm.intellij.jugg.mcp.layout.parser

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sickworm.intellij.jugg.mcp.layout.model.FigmaNode
import java.io.File

/**
 * Figma JSON parser with automatic format detection
 */
class FigmaJsonParser {
    fun parse(jsonPath: String): FigmaNode {
        val json = JsonParser.parseString(File(jsonPath).readText()).asJsonObject

        // Auto-detect format and extract root node
        val rootNode = when {
            // Format 1: Direct node format {id, layout/bounds, children}
            json.has("id") && (json.has("layout") || json.has("bounds")) -> json

            // Format 2: Wrapped format {nodes: {...}}
            json.has("nodes") -> {
                val nodes = json.getAsJsonObject("nodes")
                nodes.entrySet().firstOrNull()?.value?.asJsonObject
                    ?: throw IllegalArgumentException("Empty nodes object")
            }

            // Format 3: Document format {document: {children: [...]}}
            json.has("document") -> {
                val doc = json.getAsJsonObject("document")
                doc.getAsJsonArray("children")?.get(0)?.asJsonObject
                    ?: throw IllegalArgumentException("Empty document children")
            }

            else -> throw IllegalArgumentException(
                "Unrecognized Figma JSON format. Expected one of:\n" +
                "1. Direct node: {\"id\": \"...\", \"layout\": [x,y,w,h], ...}\n" +
                "2. Nodes wrapper: {\"nodes\": {\"id\": {...}}}\n" +
                "3. Document wrapper: {\"document\": {\"children\": [...]}}"
            )
        }

        val validation = validate(rootNode)
        if (!validation.isValid) {
            throw IllegalArgumentException("Invalid Figma node: ${validation.error}\n${validation.expectedFormat}")
        }

        return parseNode(rootNode)
    }

    fun validate(json: JsonObject): ValidationResult {
        if (!json.has("id")) {
            return ValidationResult.error(
                "Missing required field: id",
                "Expected: {\"id\": \"...\", \"layout\": [x,y,w,h] or \"bounds\": [...]}"
            )
        }

        if (!json.has("bounds") && !json.has("layout")) {
            return ValidationResult.error(
                "Missing required field: bounds or layout",
                "Expected: {\"id\": \"...\", \"layout\": [x,y,w,h]} or {\"id\": \"...\", \"bounds\": [l,t,r,b]}"
            )
        }

        val boundsArray = if (json.has("layout")) {
            json.getAsJsonArray("layout")
        } else {
            json.getAsJsonArray("bounds")
        }

        if (boundsArray.size() != 4) {
            return ValidationResult.error(
                "Invalid bounds/layout: expected 4 elements, got ${boundsArray.size()}",
                "Expected: \"layout\": [x, y, width, height] or \"bounds\": [left, top, right, bottom]"
            )
        }

        return ValidationResult.ok()
    }

    fun flattenNodes(root: FigmaNode): List<FigmaNode> {
        val result = mutableListOf<FigmaNode>()
        fun traverse(node: FigmaNode) {
            result.add(node)
            node.children?.forEach { traverse(it) }
        }
        traverse(root)
        return result
    }

    private fun parseNode(json: JsonObject): FigmaNode {
        val id = json.get("id").asString
        val name = json.get("name")?.asString

        // Support both "layout" [x,y,w,h] and "bounds" [l,t,r,b]
        val bounds = if (json.has("layout")) {
            val layout = json.getAsJsonArray("layout")
            val x = layout[0].asInt
            val y = layout[1].asInt
            val w = layout[2].asInt
            val h = layout[3].asInt
            intArrayOf(x, y, x + w, y + h)
        } else {
            val boundsArray = json.getAsJsonArray("bounds")
            IntArray(4) { boundsArray[it].asInt }
        }

        val children = json.getAsJsonArray("children")?.map { parseNode(it.asJsonObject) }

        return FigmaNode(id, name, bounds, children)
    }

    data class ValidationResult(
        val isValid: Boolean,
        val error: String? = null,
        val expectedFormat: String? = null
    ) {
        companion object {
            fun ok() = ValidationResult(true)
            fun error(msg: String, expected: String) = ValidationResult(false, msg, expected)
        }
    }
}
