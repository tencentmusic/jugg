package com.sickworm.intellij.jugg.mcp.actions

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Converts a layout-dump JSON (px→dp already applied) into a compact HTML representation.
 *
 * Design goals:
 * - High information density: each visible node renders as one HTML element with inline attributes.
 * - Virtual-node pruning: purely structural wrapper nodes (no semantic value) are removed; their
 *   children are promoted up the tree.  A node is "virtual" when ALL of:
 *     1. id is absent or starts with "_vir_id_"
 *     2. No text, no contentDesc, not clickable
 *     3. alpha == 0.0 OR className is a generic container (FrameLayout/LinearLayout/RelativeLayout/
 *        ConstraintLayout/ViewGroup) AND the node has no real id
 *   Special case: the root node of a window is never pruned regardless.
 * - Familiar to LLMs: resembles simplified HTML with meaningful tag names and attributes.
 *
 * Output format example:
 * ```html
 * <html>
 * <body>
 * <!-- Window: MainActivity -->
 * <FrameLayout bounds="0,0,360,640">
 *   <Button id="btn_ok" bounds="8,8,100,48" clickable>OK</Button>
 *   <TextView bounds="0,60,200,100">Hello World</TextView>
 * </FrameLayout>
 * </body>
 * </html>
 * ```
 */
internal class LayoutHtmlConverter {

    fun convert(root: JsonObject): String {
        val sb = StringBuilder()
        sb.appendLine("<html>")
        sb.appendLine("<body>")

        val windows = root.getArrayOrNull("windows") ?: JsonArray()
        windows.forEach { windowElement ->
            val window = windowElement.asJsonObjectOrNull() ?: return@forEach
            val title = window.get("title")?.asStringOrNull() ?: ""
            if (title.isNotEmpty()) {
                sb.appendLine("<!-- Window: ${escapeHtml(title)} -->")
            }
            val rootNode = window.get("root")?.asJsonObjectOrNull() ?: return@forEach
            appendNode(sb, rootNode, indent = 0, isWindowRoot = true)
        }

        sb.appendLine("</body>")
        sb.append("</html>")
        return sb.toString()
    }

    private fun appendNode(sb: StringBuilder, node: JsonObject, indent: Int, isWindowRoot: Boolean = false) {
        if (!isWindowRoot && isVirtualNode(node)) {
            // Prune this wrapper node: recurse directly into children
            node.getArrayOrNull("children")?.forEach { child ->
                child.asJsonObjectOrNull()?.let { appendNode(sb, it, indent) }
            }
            return
        }

        val className = node.get("className")?.asStringOrNull() ?: "View"
        val rawId = node.get("id")?.asStringOrNull()
        // Suppress auto-generated virtual ids (_vir_id_*) from the HTML output
        val id = if (rawId != null && rawId.startsWith("_vir_id_")) null else rawId
        val text = node.get("text")?.asStringOrNull()
        val contentDesc = node.get("contentDesc")?.asStringOrNull()
        val bounds = node.getArrayOrNull("bounds")?.let { formatBounds(it) }
        val clickable = node.get("clickable")?.runCatching { asBoolean }?.getOrDefault(false) ?: false
        val enabled = node.get("enabled")?.runCatching { asBoolean }?.getOrDefault(true) ?: true
        val children = node.getArrayOrNull("children")

        val prefix = "  ".repeat(indent)

        sb.append("$prefix<$className")
        if (!id.isNullOrEmpty()) sb.append(""" id="${escapeAttr(id)}"""")
        if (bounds != null) sb.append(""" bounds="$bounds"""")
        if (clickable) sb.append(" clickable")
        if (!enabled) sb.append(" disabled")
        if (!contentDesc.isNullOrEmpty()) sb.append(""" desc="${escapeAttr(contentDesc)}"""")

        val hasChildren = children != null && children.size() > 0
        if (!hasChildren && text.isNullOrEmpty()) {
            sb.appendLine("/>")
            return
        }

        sb.append(">")

        if (hasChildren) {
            sb.appendLine()
            children!!.forEach { child ->
                child.asJsonObjectOrNull()?.let { appendNode(sb, it, indent + 1) }
            }
            if (!text.isNullOrEmpty()) {
                sb.appendLine("$prefix  ${escapeHtml(text)}")
            }
            sb.appendLine("$prefix</$className>")
        } else {
            // leaf node: text inline
            sb.append(escapeHtml(text ?: ""))
            sb.appendLine("</$className>")
        }
    }

    // --- Pruning logic ---

    private val CONTAINER_CLASS_NAMES = setOf(
        "FrameLayout", "LinearLayout", "RelativeLayout",
        "ConstraintLayout", "ViewGroup", "View"
    )

    /**
     * Returns true if the node is a purely structural wrapper with no semantic value.
     * Such nodes are removed from the HTML output and their children are promoted.
     */
    private fun isVirtualNode(node: JsonObject): Boolean {
        val id = node.get("id")?.asStringOrNull()
        val text = node.get("text")?.asStringOrNull()
        val contentDesc = node.get("contentDesc")?.asStringOrNull()
        val clickable = node.get("clickable")?.runCatching { asBoolean }?.getOrDefault(false) ?: false
        val alpha = node.get("alpha")?.runCatching { asFloat }?.getOrNull()
        val className = node.get("className")?.asStringOrNull() ?: ""

        // Keep any node that has semantic content
        if (!text.isNullOrEmpty()) return false
        if (!contentDesc.isNullOrEmpty()) return false
        if (clickable) return false

        // Keep real IDs (non-virtual, non-empty)
        val isVirId = id != null && id.startsWith("_vir_id_")
        if (!id.isNullOrEmpty() && !isVirId) return false

        // Transparent alpha or generic container without id → prune
        if (alpha != null && alpha == 0f) return true
        if (CONTAINER_CLASS_NAMES.contains(className)) return true

        // No id, no text, no contentDesc, no click → prune
        return id.isNullOrEmpty() || isVirId
    }

    // --- Formatting helpers ---

    private fun formatBounds(arr: JsonArray): String? {
        if (arr.size() < 4) return null
        return "${arr[0].asInt},${arr[1].asInt},${arr[2].asInt},${arr[3].asInt}"
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun escapeAttr(text: String): String = escapeHtml(text)
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    // --- JsonElement extension helpers ---

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null

    private fun JsonElement.asStringOrNull(): String? = runCatching { asString }.getOrNull()

    private fun JsonObject.getArrayOrNull(key: String): JsonArray? {
        val v = get(key)
        return if (v != null && v.isJsonArray) v.asJsonArray else null
    }
}
