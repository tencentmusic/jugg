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

    /**
     * Process a ResCompileSet with AabResGuard obfuscation if applicable
     *
     * @param resCompileSet The resource compile set to process
     * @return Processed ResCompileSet, or null if processing failed (should abort compilation)
     *         Returns original ResCompileSet if no mapping file exists (skip processing)
     */
    fun process(resCompileSet: ResourceCompiler.ResCompileSet, outputDir: File): ResourceCompiler.ResCompileSet? {
        // 1. Get mapping file path
        if (mappingFile == null) {
            // No mapping file, skip processing
            return resCompileSet
        }

        // 2. Parse mapping file
        val mappings = parseMappingFile(mappingFile)
        if (mappings == null || mappings.isEmpty()) {
            logger.info("No resource mappings found in ${mappingFile.absolutePath}, skipping AabResGuard processing")
            return resCompileSet
        }

        logger.info("Found ${mappings.size} resource mappings, processing with AabResGuard")

        // 3. Process resource files
        return processResourceFiles(resCompileSet, mappings, outputDir)
    }

    fun convertAttr(attrName: String): String? {
        if (mappingFile == null) {
            return null
        }
        val mappings = parseMappingFile(mappingFile)
        if (mappings == null || mappings.isEmpty()) {
            return null
        }
        return mappings["attr/$attrName"]?.obfuscatedName
    }

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

    /**
     * Process resource files with the given mappings
     * @return Processed ResCompileSet, or null if processing failed
     */
    private fun processResourceFiles(
        resCompileSet: ResourceCompiler.ResCompileSet,
        mappings: Map<String, AabResGuardMappingParser.ResourceMapping>,
        outputDir: File,
    ): ResourceCompiler.ResCompileSet? {
        val processor = AabResGuardResourceProcessor(mappings, logger)
        outputDir.deleteRecursively()
        outputDir.mkdirs()

        val processedCompileFileMap = try {
            resCompileSet.compileFileMap.mapValues { (_, files) ->
                processor.processResourceFiles(files, outputDir)
            }
        } catch (e: Exception) {
            logger.error("Failed to process resources with AabResGuard", e)
            return null // Processing failed, should abort compilation
        }

        // Return processed ResCompileSet
        return resCompileSet.copy(compileFileMap = processedCompileFileMap)
    }
}
