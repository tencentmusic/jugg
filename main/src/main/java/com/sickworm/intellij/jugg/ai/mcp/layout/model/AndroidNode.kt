package com.sickworm.intellij.jugg.ai.mcp.layout.model

/**
 * Android View node data model
 */
data class AndroidNode(
    val className: String,
    val id: String?,
    val text: String?,
    val bounds: IntArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AndroidNode

        if (className != other.className) return false
        if (id != other.id) return false
        if (text != other.text) return false
        if (!bounds.contentEquals(other.bounds)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = className.hashCode()
        result = 31 * result + (id?.hashCode() ?: 0)
        result = 31 * result + (text?.hashCode() ?: 0)
        result = 31 * result + bounds.contentHashCode()
        return result
    }
}
