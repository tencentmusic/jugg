package com.sickworm.intellij.jugg.ai.mcp.actions

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.sickworm.intellij.jugg.ai.mcp.viewhierarchy.compactVirtualId

/**
 * Builds a sparse JSON artifact from the normalized hierarchy without modifying its source.
 * Interaction mode prunes nonsemantic wrappers and folds offscreen descendants; UI mode
 * preserves the captured tree. Interaction mode omits hidden subtrees and visual fields.
 */
internal class LayoutJsonConverter {

    /** Keep synthetic details inline and put real child arrays and their closing brackets on separate lines. */
    fun serialize(root: JsonObject): String = buildString { appendJson(root, false) }

    private fun StringBuilder.appendJson(element: JsonElement, node: Boolean) {
        when {
            element.isJsonObject -> {
                if (node && !element.asJsonObject.has("jugg_details")) append('\n')
                append('{')
                element.asJsonObject.entrySet().forEachIndexed { index, (key, value) ->
                    if (index > 0) append(',')
                    append(JsonPrimitive(key)).append(':')
                    appendJson(value, key == "root" || key == "children")
                }
                append('}')
            }
            element.isJsonArray -> {
                val multiline = node && element.asJsonArray.any {
                    it.isJsonObject && !it.asJsonObject.has("jugg_details")
                }
                if (multiline) append('\n')
                append('[')
                element.asJsonArray.forEachIndexed { index, value ->
                    if (index > 0) append(',')
                    appendJson(value, node)
                }
                if (multiline) append('\n')
                append(']')
            }
            else -> append(element)
        }
    }

    fun convert(root: JsonObject, mode: String): JsonObject {
        val content = root.deepCopy()
        val output = JsonObject()
        for (key in listOf("truncated", "deviceInfo")) {
            content.remove(key)?.let { output.add(key, it) }
        }
        content.entrySet().forEach { (key, value) -> output.add(key, value) }
        output.addProperty("mode", mode)
        output.get("rootLayout")?.asStringOrNull()?.let { output.addProperty("rootLayout", it.compactVirtualId()) }
        val windows = output.getArrayOrNull("windows") ?: return output
        val retained = JsonArray()
        windows.forEach { window ->
            val windowObject = window.asJsonObjectOrNull()
            val windowRoot = windowObject?.get("root")?.asJsonObjectOrNull()
            if (windowRoot != null) {
                if (mode == "interaction" && windowRoot.isHidden()) return@forEach
                prepareNode(windowRoot, mode == "interaction")
            }
            if (mode == "interaction" && windowRoot != null) {
                // Subtree payloads do not carry their owning window's viewport.
                val viewport = if (output.has("rootLayout") || windowObject?.get("windowType")?.asStringOrNull() == "subtree") {
                    null
                } else {
                    windowRoot.boundsOrNull()?.takeIf { it[2] > it[0] && it[3] > it[1] }
                }
                pruneChildren(windowRoot, viewport)
            }
            retained.add(window)
        }
        output.add("windows", retained)
        return output
    }

    private fun prepareNode(node: JsonObject, interaction: Boolean) {
        node.get("id")?.asStringOrNull()?.let { node.addProperty("id", it.compactVirtualId()) }
        if (interaction) {
            node.remove("padding")
            node.remove("textColor")
        }
        val children = node.getArrayOrNull("children") ?: return
        val retained = JsonArray()
        children.forEach { child ->
            val childNode = child.asJsonObjectOrNull() ?: return@forEach
            if (interaction && childNode.isHidden()) return@forEach
            prepareNode(childNode, interaction)
            retained.add(childNode)
        }
        if (interaction) {
            node.remove("children")
            if (retained.size() > 0) node.add("children", retained)
        }
    }

    private fun pruneChildren(node: JsonObject, viewport: List<Double>?) {
        val children = node.getArrayOrNull("children") ?: return
        val retained = JsonArray()
        children.forEach { child -> child.asJsonObjectOrNull()?.let { appendNode(retained, it, viewport) } }
        node.remove("children")
        if (retained.size() > 0) node.add("children", retained)
    }

    private fun appendNode(output: JsonArray, node: JsonObject, viewport: List<Double>?) {
        val bounds = node.boundsOrNull()
        val outside = viewport != null && bounds != null && bounds[2] > bounds[0] && bounds[3] > bounds[1] &&
                (bounds[2] <= viewport[0] || bounds[0] >= viewport[2] || bounds[3] <= viewport[1] || bounds[1] >= viewport[3])
        val children = node.getArrayOrNull("children")
        if (outside && viewport != null && children != null && children.size() > 0 && children.all { canOmit(it, viewport) }) {
            node.add("children", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("jugg_details", "View is off-screen; descendants omitted in interaction mode.")
                })
            })
            output.add(node)
            return
        }
        if (!outside && isPruned(node)) {
            node.getArrayOrNull("children")?.forEach { child ->
                child.asJsonObjectOrNull()?.let { appendNode(output, it, viewport) }
            }
            return
        }
        pruneChildren(node, viewport)
        output.add(node)
    }

    /** Keep diagnostics, unknown geometry, and descendants that can still intersect the window. */
    private fun canOmit(element: JsonElement, viewport: List<Double>): Boolean {
        val node = element.asJsonObjectOrNull() ?: return false
        if (!node.get("message")?.asStringOrNull().isNullOrEmpty()) return false
        val bounds = node.boundsOrNull() ?: return false
        if (minOf(bounds[2], viewport[2]) > maxOf(bounds[0], viewport[0]) &&
            minOf(bounds[3], viewport[3]) > maxOf(bounds[1], viewport[1])) return false
        return node.getArrayOrNull("children")?.all { canOmit(it, viewport) } != false
    }

    private fun JsonObject.boundsOrNull(): List<Double>? {
        val bounds = getArrayOrNull("bounds")?.takeIf { it.size() == 4 } ?: return null
        return runCatching { bounds.map { it.asDouble } }.getOrNull()?.takeIf { values -> values.all { it.isFinite() } }
    }

    private fun isPruned(node: JsonObject): Boolean {
        if (!node.get("message")?.asStringOrNull().isNullOrEmpty()) return false
        if (!node.get("text")?.asStringOrNull().isNullOrEmpty()) return false
        if (!node.get("contentDesc")?.asStringOrNull().isNullOrEmpty()) return false
        if (node.get("clickable")?.runCatching { asBoolean }?.getOrDefault(false) == true) return false
        val id = node.get("id")?.asStringOrNull()
        // The former HTML predicate also pruned non-container nodes without semantic content.
        return id.isNullOrEmpty() || id.startsWith("_vid_")
    }

    private fun JsonObject.isHidden(): Boolean = get("visibility")?.asStringOrNull() in listOf("invisible", "gone")

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null

    private fun JsonElement.asStringOrNull(): String? = runCatching { asString }.getOrNull()

    private fun JsonObject.getArrayOrNull(key: String): JsonArray? = get(key)?.takeIf { it.isJsonArray }?.asJsonArray
}
