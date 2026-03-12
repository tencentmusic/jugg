package com.sickworm.intellij.jugg.mcp.layout.model

/**
 * Figma node data model
 */
data class FigmaNode(
    val id: String,
    val name: String?,
    val bounds: IntArray,
    val children: List<FigmaNode>?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as FigmaNode

        if (id != other.id) return false
        if (name != other.name) return false
        if (!bounds.contentEquals(other.bounds)) return false
        if (children != other.children) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + (name?.hashCode() ?: 0)
        result = 31 * result + bounds.contentHashCode()
        result = 31 * result + (children?.hashCode() ?: 0)
        return result
    }
}
