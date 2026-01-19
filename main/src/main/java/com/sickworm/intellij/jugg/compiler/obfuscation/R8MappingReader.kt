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
        val returnType: String,
        val originalName: String,
        val parameters: String,
        val obfuscatedName: String,
        val lineRange: IntRange?
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

        // Get method mappings
        classNaming.allMethodNamings().forEach { memberNaming ->
            val signature = memberNaming.originalSignature as? MemberNaming.MethodSignature ?: return@forEach
            methods.add(
                MethodMapping(
                    returnType = signature.type,
                    originalName = signature.name,
                    parameters = signature.parameters.joinToString(","),
                    obfuscatedName = memberNaming.renamedName,
                    lineRange = null // R8 line number info is in different structure
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
