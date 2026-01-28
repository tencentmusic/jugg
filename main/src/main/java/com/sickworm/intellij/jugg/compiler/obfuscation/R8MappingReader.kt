package com.sickworm.intellij.jugg.compiler.obfuscation

import com.android.tools.r8.naming.ClassNameMapper
import com.android.tools.r8.naming.ClassNamingForNameMapper
import com.android.tools.r8.naming.MemberNaming
import com.android.tools.r8.naming.mappinginformation.MapVersionMappingInformation
import java.io.File
import java.nio.file.Path

/**
 * R8 mapping.txt file parser.
 *
 * Based on R8's ClassNameMapper implementation, supports parsing standard ProGuard/R8 mapping file format.
 * Used for secondary obfuscation of incremental build artifacts.
 */
class R8MappingReader private constructor(
    private val mapper: ClassNameMapper
) {
    /**
     * Class mapping information.
     */
    data class ClassMapping(
        val originalName: String,
        val obfuscatedName: String,
        val fields: List<FieldMapping>,
        val methods: List<MethodMapping>
    )

    /**
     * Field mapping information.
     */
    data class FieldMapping(
        val type: String,
        val originalName: String,
        val obfuscatedName: String
    )

    /**
     * Method mapping information.
     */
    data class MethodMapping(
        val originalName: String,
        val parameters: String,
        val obfuscatedName: String,
        val invocations: List<MethodInvocation> = emptyList()
    )

    /**
     * Method invocation information (inlined method call).
     * Represents a method that was called and potentially inlined into the caller method.
     */
    data class MethodInvocation(
        val calledClass: String,
        val calledMethod: String,
        val parameters: String
    )

    /**
     * Get all class mappings (obfuscated name -> mapping info).
     */
    fun getClassMappings(): Map<String, ClassMapping> {
        val result = mutableMapOf<String, ClassMapping>()
        mapper.classNameMappings.forEach { (obfuscatedName, classNaming) ->
            result[obfuscatedName] = convertClassNaming(classNaming)
        }
        return result
    }

    /**
     * Get the number of class mappings.
     */
    fun getClassCount(): Int = mapper.classNameMappings.size

    /**
     * Find original class name by obfuscated name.
     */
    fun getOriginalClassName(obfuscatedName: String): String? {
        return mapper.getClassNaming(obfuscatedName)?.originalName
    }

    /**
     * Find obfuscated class name by original name.
     */
    fun getObfuscatedClassName(originalName: String): String? {
        return mapper.classNameMappings.entries.find { it.value.originalName == originalName }?.key
    }

    /**
     * Get detailed mapping info for a specific class.
     */
    fun getClassMapping(obfuscatedName: String): ClassMapping? {
        val classNaming = mapper.getClassNaming(obfuscatedName) ?: return null
        return convertClassNaming(classNaming)
    }

    /**
     * Get detailed mapping info by original class name.
     */
    fun getClassMappingByOriginalName(originalName: String): ClassMapping? {
        val obfuscatedName = getObfuscatedClassName(originalName) ?: return null
        return getClassMapping(obfuscatedName)
    }

    /**
     * Get mapping file version info.
     */
    fun getMapVersion(): String? {
        return mapper.mapVersions
            .filterIsInstance<MapVersionMappingInformation>()
            .firstOrNull()
            ?.mapVersion?.name
    }

    /**
     * Iterate over all class mappings.
     */
    fun forEachClass(action: (obfuscatedName: String, classMapping: ClassMapping) -> Unit) {
        mapper.classNameMappings.forEach { (obfuscatedName, classNaming) ->
            action(obfuscatedName, convertClassNaming(classNaming))
        }
    }

    /**
     * Find all classes with the specified original name prefix.
     */
    fun findClassesByOriginalPrefix(prefix: String): List<ClassMapping> {
        return mapper.classNameMappings.values
            .filter { it.originalName.startsWith(prefix) }
            .map { convertClassNaming(it) }
    }

    /**
     * Find all classes with the specified obfuscated name prefix.
     */
    fun findClassesByObfuscatedPrefix(prefix: String): List<ClassMapping> {
        return mapper.classNameMappings
            .filter { it.key.startsWith(prefix) }
            .map { convertClassNaming(it.value) }
    }

    private fun convertClassNaming(classNaming: ClassNamingForNameMapper): ClassMapping {
        val fields = mutableListOf<FieldMapping>()
        val methods = mutableListOf<MethodMapping>()

        // Get field mappings
        classNaming.allFieldNamings().forEach { memberNaming ->
            val signature = memberNaming.originalSignature as? MemberNaming.FieldSignature ?: return@forEach
            fields.add(
                FieldMapping(
                    type = signature.type,
                    originalName = signature.name,
                    obfuscatedName = memberNaming.renamedName
                )
            )
        }

        // Get method mappings with invocation information
        // Build a map from renamed name to invocations
        val invocationsByRenamedName = mutableMapOf<String, List<MethodInvocation>>()

        classNaming.mappedRangesByRenamedName.forEach { (renamedName, mappedRangesOfName) ->
            val mappedRanges = mappedRangesOfName.mappedRanges
            if (mappedRanges.isEmpty()) return@forEach

            val invocations = mutableSetOf<MethodInvocation>()

            // Find signatures that appear to be from other classes (qualified signatures)
            mappedRanges.forEach { range ->
                val sig = range.signature
                if (sig != null) {
                    // A qualified signature indicates an inlined method from another class
                    if (sig.isQualified) {
                        // For qualified signatures, the toString() already has the full class name
                        // Format: "returnType fullyQualifiedClassName.methodName(params)"
                        val sigString = sig.toString()

                        // Extract just the class name from the qualified signature
                        // Find the opening parenthesis first
                        val openParenIndex = sigString.indexOf('(')
                        val searchPart = if (openParenIndex > 0) {
                            sigString.substring(0, openParenIndex)
                        } else {
                            sigString
                        }

                        // Now find the last dot before the method name
                        val dotBeforeMethod = searchPart.lastIndexOf('.')
                        if (dotBeforeMethod > 0) {
                            val beforeMethod = searchPart.substring(0, dotBeforeMethod)
                            val spaceIndex = beforeMethod.indexOf(' ')
                            var calledClass = if (spaceIndex > 0) {
                                beforeMethod.substring(spaceIndex + 1)
                            } else {
                                classNaming.originalName
                            }

                            // R8's qualified signature sometimes duplicates the class name
                            // e.g., "com.example.MyClass.com.example.MyClass"
                            // Check if there's an exact duplication at the midpoint
                            val segments = calledClass.split(".")
                            if (segments.size >= 4 && segments.size % 2 == 0) {
                                val midPoint = segments.size / 2
                                val firstHalf = segments.subList(0, midPoint).joinToString(".")
                                val secondHalf = segments.subList(midPoint, segments.size).joinToString(".")
                                if (firstHalf == secondHalf) {
                                    // Exact duplication at midpoint
                                    calledClass = firstHalf
                                }
                            }

                            // Extract just the method name from sig.name
                            // sig.name might be qualified like "com.example.Class.method"
                            val methodName = sig.name.substringAfterLast('.')

                            invocations.add(
                                MethodInvocation(
                                    calledClass = calledClass,
                                    calledMethod = methodName,
                                    parameters = sig.parameters.joinToString(",")
                                )
                            )
                        }
                    }
                }
            }
            invocationsByRenamedName[renamedName] = invocations.toList()
        }

        // Now get methods from allMethodNamings and attach invocation information
        classNaming.allMethodNamings().forEach { memberNaming ->
            val signature = memberNaming.originalSignature as? MemberNaming.MethodSignature ?: return@forEach
            val invocations = invocationsByRenamedName[memberNaming.renamedName] ?: emptyList()

            methods.add(
                MethodMapping(
                    originalName = signature.name,
                    parameters = signature.parameters.joinToString(","),
                    obfuscatedName = memberNaming.renamedName,
                    invocations = invocations
                )
            )
        }

        return ClassMapping(
            originalName = classNaming.originalName,
            obfuscatedName = classNaming.renamedName,
            fields = fields,
            methods = methods
        )
    }

    /**
     * Find all invocations of a specific method (by original class and method name).
     * Returns a list of caller information including the class, method, and line where the call occurred.
     */
    fun findInvocationsOf(calledClass: String, calledMethod: String): List<InvocationSite> {
        val results = mutableListOf<InvocationSite>()
        mapper.classNameMappings.forEach { (_, classNaming) ->
            val classMapping = convertClassNaming(classNaming)
            classMapping.methods.forEach { method ->
                method.invocations.forEach { invocation ->
                    if (invocation.calledClass == calledClass && invocation.calledMethod == calledMethod) {
                        results.add(
                            InvocationSite(
                                callerClass = classMapping.originalName,
                                callerMethod = method.originalName,
                                invocation = invocation
                            )
                        )
                    }
                }
            }
        }
        return results
    }

    /**
     * Get all methods invoked within a specific method (by original class and method name).
     */
    fun getMethodInvocations(className: String, methodName: String): List<MethodInvocation> {
        val classMapping = getClassMappingByOriginalName(className) ?: return emptyList()
        val method = classMapping.methods.find { it.originalName == methodName } ?: return emptyList()
        return method.invocations
    }

    /**
     * Information about where a method is invoked/called.
     */
    data class InvocationSite(
        val callerClass: String,
        val callerMethod: String,
        val invocation: MethodInvocation
    )

    companion object {
        /**
         * Load mapping from file.
         */
        fun fromFile(file: File): R8MappingReader {
            return fromPath(file.toPath())
        }

        /**
         * Load mapping from path.
         */
        fun fromPath(path: Path): R8MappingReader {
            val mapper = ClassNameMapper.mapperFromFile(path, ClassNameMapper.MissingFileAction.MISSING_FILE_IS_EMPTY_MAP)
            return R8MappingReader(mapper)
        }

        /**
         * Load mapping from string content.
         */
        fun fromString(content: String): R8MappingReader {
            val mapper = ClassNameMapper.mapperFromString(content)
            return R8MappingReader(mapper)
        }
    }
}
