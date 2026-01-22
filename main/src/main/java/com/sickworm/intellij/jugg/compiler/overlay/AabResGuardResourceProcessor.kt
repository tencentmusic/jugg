package com.sickworm.intellij.jugg.compiler.overlay

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.manifest.XmlParser
import org.w3c.dom.Element
import org.w3c.dom.Node
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
        private val RESOURCE_REF_PATTERN = Regex("""="@([a-z+]+)/([a-zA-Z0-9_]+)"""")
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
    ): List<File> {
        outputDir.mkdirs()

        return originalFiles.map { originalFile ->
            val resourceType = originalFile.parentFile.name
            val resourceName = originalFile.nameWithoutExtension

            // Check if this resource file has a mapping
            val key = "$resourceType/$resourceName"
            val mapping = mappings[key]
            val finalName = mapping?.obfuscatedName ?: resourceName

            val outputFile = File(outputDir, "$resourceType/$finalName.${originalFile.extension}")
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
            val xmlParser = XmlParser()
            val xmlNode = xmlParser.parse(inputFile)

            // Process the XML node to replace resource references
            processXmlNode(xmlNode.node, inputFile)

            // Write the processed XML to output file
            outputFile.writeText(xmlNode.printXml())
        } catch (e: Exception) {
            logger.error("Failed to process XML file: ${inputFile.absolutePath}", e)
            throw e
        }
    }

    /**
     * Recursively process XML nodes to replace resource references
     */
    private fun processXmlNode(element: Element, inputFile: File) {
        val replaceMapForLog = mutableMapOf<String, String>()

        // Process attributes of the current element
        val toReplaceNames = mutableListOf<ReplaceAttr>()
        val attributes = element.attributes
        for (i in 0 until attributes.length) {
            val attr = attributes.item(i)
            val attrValue = attr.nodeValue

            // Check if this attribute value contains a resource reference
            if (attrValue.startsWith("@")) {
                val processedValue = processResourceReference(attrValue, replaceMapForLog)
                if (processedValue != attrValue) {
                    attr.nodeValue = processedValue
                }
            }

            // replace attribute like app:layout_constraintBottom_toBottomOf
            val attrName = attr.nodeName
            if (attrName.contains(":")) {
                val (nameSpace, name) = attrName.split(":")
                if (!nameSpace.startsWith("android")) {
                    val mappingName = mappings["attr/$name"]
                    val mappingValue = mappings["id/$attrValue"]
                    if (mappingName != null) {
                        toReplaceNames.add(ReplaceAttr(
                            attr.nodeName,
                            "$nameSpace:${mappingName.obfuscatedName}",
                            mappingValue?.obfuscatedName ?: attr.nodeValue
                        ))
                    }
                }
            }
        }

        toReplaceNames.forEach { (originName, replaceName, value) ->
            element.removeAttribute(originName)
            element.setAttribute(replaceName, value)
        }

        // Recursively process child elements
        val childNodes = element.childNodes
        for (i in 0 until childNodes.length) {
            val childNode = childNodes.item(i)
            if (childNode.nodeType == Node.ELEMENT_NODE) {
                processXmlNode(childNode as Element, inputFile)
            }
        }

        // Log the replacements
        if (replaceMapForLog.isNotEmpty()) {
            logger.debug("Resource references replaced, file: ${inputFile.name}, ${replaceMapForLog.map { (key, value) -> "@$key -> @$value" }}")
        }
    }

    /**
     * Process a single resource reference
     */
    private fun processResourceReference(refValue: String, replaceMap: MutableMap<String, String>): String {
        // Resource reference format: @[+][type]/[name]
        if (!refValue.startsWith("@") || refValue.length < 3) {
            return refValue
        }

        val refParts = refValue.substring(1).split("/")
        if (refParts.size != 2) {
            return refValue
        }

        var resourceType = refParts[0]
        val resourceName = refParts[1]

        // Handle + prefix for private resources
        val hasAdd = resourceType.startsWith("+")
        if (hasAdd) {
            resourceType = resourceType.substring(1)
        }

        // Check if this resource has a mapping
        val key = "$resourceType/$resourceName"
        val mapping = mappings[key]

        if (mapping != null) {
            // Replace with obfuscated name
            val obfuscatedRef = "${mapping.resourceType}/${mapping.obfuscatedName}"
            replaceMap[key] = obfuscatedRef
            val prefix = if (hasAdd) "+" else ""
            return "@$prefix$obfuscatedRef"
        }

        // No mapping found, keep original
        return refValue
    }

    private data class ReplaceAttr(
        val originName: String,
        val newName: String,
        val value: String,
    )
}
