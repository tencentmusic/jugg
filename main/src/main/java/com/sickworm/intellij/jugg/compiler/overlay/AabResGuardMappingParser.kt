package com.sickworm.intellij.jugg.compiler.overlay

import java.io.File

/**
 * Parser for AabResGuard resources-mapping.txt file
 *
 * Example mapping format:
 * res id mapping:
 *     0x7f0c00ba : com.bytedance.android.app.R.style.RtlUnderlay -> com.bytedance.android.app.R.style.eb
 *     0x7f040002 : com.bytedance.android.app.R.color.abc_btn_colored_borderless_text_material -> com.bytedance.android.app.R.color.c
 */
object AabResGuardMappingParser {

    /**
     * Resource mapping data
     * @param originalName Original resource name (e.g., "abc_btn_colored_borderless_text_material")
     * @param obfuscatedName Obfuscated resource name (e.g., "c")
     * @param resourceType Resource type (e.g., "color", "style", "drawable")
     */
    data class ResourceMapping(
        val originalName: String,
        val obfuscatedName: String,
        val resourceType: String
    )

    /**
     * Parse the AabResGuard mapping file and return a map of resource mappings
     * @param mappingFile The resources-mapping.txt file
     * @return Map of (resourceType/originalName -> ResourceMapping)
     */
    fun parse(mappingFile: File): Map<String, ResourceMapping> {
        if (!mappingFile.exists() || !mappingFile.canRead()) {
            throw IllegalArgumentException("Mapping file does not exist or is not readable: ${mappingFile.absolutePath}")
        }

        val mappings = mutableMapOf<String, ResourceMapping>()
        var inResIdMappingSection = false

        mappingFile.forEachLine { line ->
            val trimmedLine = line.trim()

            // Check if we're entering the "res id mapping:" section
            if (trimmedLine == "res id mapping:") {
                inResIdMappingSection = true
                return@forEachLine
            }

            // Check if we're leaving the section (empty line or new section)
            if (inResIdMappingSection && (trimmedLine.isEmpty() || trimmedLine.endsWith(":"))) {
                inResIdMappingSection = false
                return@forEachLine
            }

            // Parse mapping lines
            if (inResIdMappingSection && trimmedLine.isNotEmpty()) {
                val mapping = parseMappingLine(trimmedLine)
                if (mapping != null) {
                    val key = "${mapping.resourceType}/${mapping.originalName}"
                    mappings[key] = mapping
                }
            }
        }

        return mappings
    }

    /**
     * Parse a single mapping line
     * Format: 0x7f0c00ba : com.bytedance.android.app.R.style.RtlUnderlay -> com.bytedance.android.app.R.style.eb
     */
    private fun parseMappingLine(line: String): ResourceMapping? {
        try {
            // Split by " -> " to get original and obfuscated parts
            val parts = line.split(" -> ")
            if (parts.size != 2) {
                return null
            }

            // Parse the original part (after the resource ID)
            val originalPart = parts[0].trim()
            val colonIndex = originalPart.indexOf(':')
            if (colonIndex == -1) {
                return null
            }

            val originalFullName = originalPart.substring(colonIndex + 1).trim()
            val obfuscatedFullName = parts[1].trim()

            // Extract resource type and name from full names
            val original = parseResourceName(originalFullName) ?: return null
            val obfuscated = parseResourceName(obfuscatedFullName) ?: return null

            // Ensure resource types match
            if (original.first != obfuscated.first) {
                return null
            }

            return ResourceMapping(
                originalName = original.second,
                obfuscatedName = obfuscated.second,
                resourceType = original.first
            )
        } catch (e: Exception) {
            // Ignore malformed lines
            return null
        }
    }

    /**
     * Extract resource type and name from full R class name
     * @param fullName e.g., "com.bytedance.android.app.R.color.abc_btn_colored_borderless_text_material"
     * @return Pair of (resourceType, resourceName) e.g., ("color", "abc_btn_colored_borderless_text_material")
     */
    private fun parseResourceName(fullName: String): Pair<String, String>? {
        val parts = fullName.split(".")
        val rIndex = parts.indexOf("R")

        if (rIndex == -1 || rIndex >= parts.size - 2) {
            return null
        }

        val type = parts[rIndex + 1]
        val name = parts.drop(rIndex + 2).joinToString(".")

        return type to name
    }
}
