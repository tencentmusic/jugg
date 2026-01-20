package com.sickworm.intellij.jugg.compiler.overlay

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileTask
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
    private val logger: Logger
) {

    /**
     * Process a ResCompileSet with AabResGuard obfuscation if applicable
     *
     * @param resCompileSet The resource compile set to process
     * @return Processed ResCompileSet, or null if processing failed (should abort compilation)
     *         Returns original ResCompileSet if no mapping file exists (skip processing)
     */
    fun process(resCompileSet: ResourceCompiler.ResCompileSet): ResourceCompiler.ResCompileSet? {
        // 1. Get mapping file path
        val mappingFile = findMappingFile(resCompileSet.originTask)
        if (mappingFile == null) {
            // No mapping file, skip processing
            logger.debug("AabResGuard mapping file not found, skipping obfuscation processing")
            return resCompileSet
        }

        // 2. Parse mapping file
        val mappings = parseMappingFile(mappingFile) ?: return null

        if (mappings.isEmpty()) {
            logger.info("No resource mappings found in ${mappingFile.absolutePath}, skipping AabResGuard processing")
            return resCompileSet
        }

        logger.info("Found ${mappings.size} resource mappings, processing with AabResGuard")

        // 3. Process resource files
        return processResourceFiles(resCompileSet, mappings)
    }

    /**
     * Find the AabResGuard mapping file
     * @return Mapping file if exists, null otherwise
     */
    private fun findMappingFile(task: CompileTask): File? {
        // Get ModuleInfo from task
        val module = task.files.firstOrNull()?.module
        if (module == null) {
            logger.debug("Cannot find module info from task, skipping AabResGuard processing")
            return null
        }

        val buildVariant = module.buildVariant
        val moduleRootDir = module.moduleRootDir

        // Construct mapping file path: build/outputs/bundle/{variant}/resources-mapping.txt
        val mappingFile = File(moduleRootDir, "build/outputs/bundle/$buildVariant/resources-mapping.txt")

        if (!mappingFile.exists()) {
            logger.debug("AabResGuard mapping file not found at: ${mappingFile.absolutePath}")
            return null
        }

        logger.info("Found AabResGuard mapping file at: ${mappingFile.absolutePath}")
        return mappingFile
    }

    /**
     * Parse the mapping file
     * @return Map of resource mappings, or null if parsing failed
     */
    private fun parseMappingFile(mappingFile: File): Map<String, AabResGuardMappingParser.ResourceMapping>? {
        return try {
            AabResGuardMappingParser.parse(mappingFile)
        } catch (e: Exception) {
            logger.error("Failed to parse AabResGuard mapping file: ${mappingFile.absolutePath}", e)
            null // Parsing failed, should abort compilation
        }
    }

    /**
     * Process resource files with the given mappings
     * @return Processed ResCompileSet, or null if processing failed
     */
    private fun processResourceFiles(
        resCompileSet: ResourceCompiler.ResCompileSet,
        mappings: Map<String, AabResGuardMappingParser.ResourceMapping>
    ): ResourceCompiler.ResCompileSet? {
        val processor = AabResGuardResourceProcessor(mappings, logger)
        val tempDir = resCompileSet.outputDir.resolve("aabresguard_temp")
        tempDir.mkdirs()

        val processedCompileFileMap = try {
            resCompileSet.compileFileMap.mapValues { (compileFile, files) ->
                processor.processResourceFiles(files, tempDir, compileFile)
            }
        } catch (e: Exception) {
            logger.error("Failed to process resources with AabResGuard", e)
            return null // Processing failed, should abort compilation
        }

        // Return processed ResCompileSet
        return resCompileSet.copy(compileFileMap = processedCompileFileMap)
    }
}
