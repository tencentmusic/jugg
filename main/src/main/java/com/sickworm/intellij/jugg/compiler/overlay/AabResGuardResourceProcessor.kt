package com.sickworm.intellij.jugg.compiler.overlay

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.CompileFile
import java.io.File

/**
 * Processor for handling resource files with AabResGuard obfuscation
 * Replaces resource references in XML files with obfuscated names
 */
class AabResGuardResourceProcessor(
    private val mappings: Map<String, AabResGuardMappingParser.ResourceMapping>,
    private val logger: Logger
) {

    companion object {
        // Regex pattern to match resource references: @resourceType/resourceName
        // Matches: @color/abc_btn, @drawable/ic_launcher, @style/AppTheme, etc.
        // Does not match: @android:color/white, ?attr/colorPrimary
        private val RESOURCE_REF_PATTERN = Regex("""@([a-z]+)/([a-zA-Z0-9_]+)""")
    }

    /**
     * Process a list of resource files and output them to the specified directory
     * @param originalFiles List of resource files to process
     * @param outputDir Output directory for processed files
     * @param compileFile The CompileFile context
     * @return List of processed files
     */
    fun processResourceFiles(
        originalFiles: List<File>,
        outputDir: File,
        compileFile: CompileFile
    ): List<File> {
        outputDir.mkdirs()

        return originalFiles.map { originalFile ->
            val relativePath = if (compileFile.file.isDirectory) {
                originalFile.relativeTo(compileFile.file)
            } else {
                originalFile.relativeTo(compileFile.baseDir)
            }

            val outputFile = File(outputDir, relativePath.path)
            outputFile.parentFile.mkdirs()

            if (originalFile.extension == "xml") {
                processXmlFile(originalFile, outputFile)
            } else {
                // For non-XML files (images, etc.), just copy them
                originalFile.copyTo(outputFile, overwrite = true)
            }

            outputFile
        }
    }

    /**
     * Process an XML file by replacing resource references
     */
    private fun processXmlFile(inputFile: File, outputFile: File) {
        try {
            val content = inputFile.readText()
            val processedContent = replaceResourceReferences(content)
            outputFile.writeText(processedContent)
        } catch (e: Exception) {
            logger.error("Failed to process XML file: ${inputFile.absolutePath}", e)
            throw e
        }
    }

    /**
     * Replace resource references in XML content
     * @param xmlContent Original XML content
     * @return XML content with obfuscated resource references
     */
    private fun replaceResourceReferences(xmlContent: String): String {
        return RESOURCE_REF_PATTERN.replace(xmlContent) { matchResult ->
            val resourceType = matchResult.groupValues[1]
            val resourceName = matchResult.groupValues[2]

            // Check if this resource has a mapping
            val key = "$resourceType/$resourceName"
            val mapping = mappings[key]

            if (mapping != null) {
                // Replace with obfuscated name
                val obfuscatedRef = "@${mapping.resourceType}/${mapping.obfuscatedName}"
                logger.debug("Replacing resource reference: @$key -> $obfuscatedRef")
                obfuscatedRef
            } else {
                // No mapping found, keep original
                matchResult.value
            }
        }
    }
}
