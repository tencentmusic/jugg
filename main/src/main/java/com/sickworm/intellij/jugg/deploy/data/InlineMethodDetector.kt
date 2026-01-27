package com.sickworm.intellij.jugg.deploy.data

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.ClassNode
import com.sickworm.intellij.jugg.compiler.obfuscation.R8MappingReader
import java.io.File

/**
 * Detector for finding classes affected by method inlining in R8 optimized builds.
 *
 * When R8 inlines a method from class A into class B, modifying class A requires
 * recompiling class B because it contains the inlined code.
 */
class InlineMethodDetector(
    private val mappingFile: File?,
    private val logger: Logger
) {

    /**
     * Find classes that are affected by method inlining.
     *
     * @param changedClasses The classes being deployed (potentially containing inlined methods)
     * @param existingClassInfoMap Map of existing classes in the APK with their metadata
     * @return List of EffectedClassNodes representing classes that need to be recompiled due to inlining
     */
    fun findInlineEffectedClasses(
        changedClasses: ParsedDex?,
        existingClassInfoMap: Map<String, ClassNode>
    ): List<EffectedClassNode> {
        if (changedClasses == null) {
            return emptyList()
        }
        if (mappingFile == null || !mappingFile.exists()) {
            logger.debug("InlineMethodDetector: No mapping file available, skipping inline detection")
            return emptyList()
        }

        logger.debug("InlineMethodDetector: checking method inlining using mapping file: ${mappingFile.absolutePath}")

        val result = mutableListOf<EffectedClassNode>()

        try {
            val mappingReader = R8MappingReader.fromFile(mappingFile)

            // For each class that we're deploying, check if any of its methods are inlined into other classes
            changedClasses.classDeployItems.forEach { classDeployItem ->
                classDeployItem.classNodes.forEach { classNode ->
                    val originalClassName = classNode.className.substring(1, classNode.className.length - 1).replace("/", ".")

                    // For each method in this class, find where it's invoked (potentially inlined)
                    classNode.methods.forEach { method ->
                        val invocationSites = mappingReader.findInvocationsOf(originalClassName, method.name)

                        if (invocationSites.isNotEmpty()) {
                            logger.debug("InlineMethodDetector: Found ${invocationSites.size} invocation sites for $originalClassName.${method.name}")

                            // Create a set of classes that have this method inlined
                            val inlinedIntoClasses = invocationSites.map { site ->
                                "L${site.callerClass.replace(".", "/")};"
                            }.toSet()

                            if (inlinedIntoClasses.isNotEmpty()) {
                                // Add EffectedClassNode for classes that have the method inlined
                                // These classes need to be recompiled because they contain inlined code
                                inlinedIntoClasses.forEach { inlinedIntoClass ->
                                    logger.debug("InlineMethodDetector: Class ${classNode.className} method ${method.name} is inlined into $inlinedIntoClass")

                                    // Check if this inlined class is not already in the result
                                    val existing = result.find { it.className == inlinedIntoClass }
                                    if (existing != null) {
                                        // Merge with existing
                                        val updated = existing.copy(
                                            effectedByClasses = (existing.effectedByClasses + classNode.className).distinct()
                                        )
                                        result[result.indexOf(existing)] = updated
                                    } else {
                                        // Find source file name for the inlined class
                                        val inlinedClassSource = existingClassInfoMap[inlinedIntoClass]?.source
                                            ?: EffectedClassNode.SOURCE_NOT_FOUND
                                        result.add(EffectedClassNode(
                                            className = inlinedIntoClass,
                                            sourceFileName = inlinedClassSource,
                                            effectedByClasses = listOf(classNode.className),
                                            effectedType = EffectedClassNode.EffectedType.CLASS
                                        ))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("InlineMethodDetector: Failed to parse R8 mapping file for inline detection: ${e.message}")
        }

        logger.debug("InlineMethodDetector: returning ${result.size} effected class nodes")
        return result
    }
}
