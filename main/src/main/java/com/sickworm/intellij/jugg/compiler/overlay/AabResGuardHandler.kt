package com.sickworm.intellij.jugg.compiler.overlay

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.logger.getInstance
import java.io.File

/**
 * Handler for processing resources with AabResGuard obfuscation
 *
 * This class encapsulates all AabResGuard-related logic:
 * - Finding the mapping file
 * - Parsing the mapping file
 * - Processing resource files with obfuscation
 */
class AabResGuardHandler(
    private val mappingFile: File?,
    loggerArg: Logger
) {

    private val logger: Logger = loggerArg.getInstance("AabResGuardHandler")

    private var mappingCache: Map<String, AabResGuardMappingParser.ResourceMapping>? = null

    /**
     * Parse the mapping file
     * @return Map of resource mappings, or null if parsing failed
     */
    private fun parseMappingFile(mappingFile: File): Map<String, AabResGuardMappingParser.ResourceMapping>? {
        return try {
            if (mappingCache != null) {
                return mappingCache
            }
            val result = AabResGuardMappingParser.parse(mappingFile)
            mappingCache = result
            result
        } catch (e: Exception) {
            logger.warn("Failed to parse AabResGuard mapping file: ${mappingFile.absolutePath}", e)
            null // Parsing failed, should abort compilation
        }
    }

    fun writeAapt2IncLinkMappingFile(outputFile: File) {
        outputFile.parentFile.mkdirs()
        outputFile.delete()

        if (mappingFile == null) {
            return
        }
        val mappings = parseMappingFile(mappingFile)
        if (mappings == null || mappings.isEmpty()) {
            return
        }

        val content = StringBuilder()
        mappings.forEach { (_, mapping) ->
            content.append(mapping.resourceType)
            content.append(":")
            content.append(mapping.originalName)
            content.append(":")
            content.append(mapping.obfuscatedName)
            content.append("\n")
        }
        outputFile.writeText(content.toString())
    }
}
