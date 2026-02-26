package com.sickworm.intellij.jugg.deploy.data

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.obfuscation.ClassObfuscator
import com.sickworm.intellij.jugg.deploy.classSigName
import com.sickworm.intellij.jugg.deploy.sigFormatToPackage
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
     * @return List of EffectedClassNodes representing classes that need to be recompiled due to inlining
     */
    fun findInlineEffectedClasses(
        changedClasses: ParsedDex?,
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

        val classObfuscator = ClassObfuscator.fromMappingFile(mappingFile)

        // For each class that we're deploying, check if any of its methods are inlined into other classes
        changedClasses.classDeployItems.forEach { classDeployItem ->
            classDeployItem.classNodes.forEach { classNode ->
                val originClassDexName = classObfuscator.getOriginClassSigName(classNode.className) ?: classNode.className
                val originClassName = originClassDexName.sigFormatToPackage

                // For each method in this class, find where it's invoked (potentially inlined)
                classNode.methods.forEach { method ->
                    val invocationSites = classObfuscator.findInvocationsOf(originClassName, method.name)
                    // logger.debug("InlineMethodDetector: Found ${invocationSites.size} invocation sites for $originalClassName.${method.name}")
                    // Create a set of classes that have this method inlined

                    // Add EffectedClassNode for classes that have the method inlined
                    // These classes need to be recompiled because they contain inlined code
                    invocationSites.forEach { inlinedIntoClass ->
                        // logger.debug("InlineMethodDetector: Class ${classNode.className} method ${method.name} is inlined into $inlinedIntoClass")

                        // Check if this inlined class is not already in the result
                        val existing = result.find { it.className == inlinedIntoClass }
                        if (existing != null) {
                            // Merge with existing
                            val updated = existing.copy(
                                effectedByClasses = (existing.effectedByClasses + classNode.className).distinct()
                            )
                            result[result.indexOf(existing)] = updated
                        } else {
                            result.add(EffectedClassNode(
                                className = inlinedIntoClass.classSigName,
                                sourceFileName = EffectedClassNode.SOURCE_NOT_FOUND,
                                effectedByClasses = listOf(classNode.className),
                                effectedType = EffectedClassNode.EffectedType.INLINE_IMPL_CHANGE
                            ))
                        }
                    }
                }
            }
        }

        logger.debug("InlineMethodDetector: returning ${result.size} effected class nodes: ${result.map { it.className }}")
        return result
    }
}
