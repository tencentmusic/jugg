package com.sickworm.intellij.jugg.compiler.obfuscation

import com.sickworm.intellij.jugg.deploy.asmSigFormat
import com.sickworm.intellij.jugg.deploy.classSigName
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassReader
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassWriter
import com.sickworm.intellij.jugg.org.objectweb.asm.commons.ClassRemapper
import com.sickworm.intellij.jugg.org.objectweb.asm.commons.Remapper
import java.io.File
import java.io.InputStream

/**
 * Class file obfuscator using ASM.
 *
 * Remaps class names, method names, and field names in bytecode
 * based on the provided mapping information from R8MappingReader.
 */
class ClassObfuscator(private val mappingReader: R8MappingReader) {

    // Build lookup maps for efficient remapping
    // originalName (internal format) -> obfuscatedName (internal format)
    private val classNameMap: Map<String, String>
    private val antiClassNameMap: Map<String, String>
    // originalClassName + "." + originalFieldName -> obfuscatedFieldName
    private val fieldNameMap: Map<String, String>
    // originalClassName + "." + originalMethodName + methodDescriptor -> obfuscatedMethodName
    private val methodNameMap: Map<String, String>

    init {
        val classMap = mutableMapOf<String, String>()
        val antiClassMap = mutableMapOf<String, String>()
        val fieldMap = mutableMapOf<String, String>()
        val methodMap = mutableMapOf<String, String>()

        mappingReader.forEachClass { _, classMapping ->
            val originalInternal = classMapping.originalName.replace('.', '/')
            val obfuscatedInternal = classMapping.obfuscatedName.replace('.', '/')
            classMap[originalInternal] = obfuscatedInternal
            antiClassMap[obfuscatedInternal] = originalInternal

            // Build field mapping
            classMapping.fields.forEach { field ->
                val key = "${classMapping.originalName}.${field.originalName}"
                fieldMap[key] = field.obfuscatedName
            }

            // Build method mapping
            classMapping.methods.forEach { method ->
                val key = "${classMapping.originalName}.${method.originalName}(${method.parameters})"
                methodMap[key] = method.obfuscatedName
            }
        }

        classNameMap = classMap
        antiClassNameMap = antiClassMap
        fieldNameMap = fieldMap
        methodNameMap = methodMap
    }

    /**
     * Obfuscate a class file.
     *
     * @param inputFile The input class file
     * @param outputFile The output class file
     * @return true if obfuscation was applied, false if the class was not found in mapping
     */
    fun obfuscate(inputFile: File, outputFile: File): Boolean {
        val inputBytes = inputFile.readBytes()
        val result = obfuscate(inputBytes)
        if (result != null) {
            outputFile.parentFile?.mkdirs()
            outputFile.writeBytes(result)
            return true
        }
        return false
    }

    /**
     * Obfuscate class bytes.
     *
     * @param classBytes The input class bytes
     * @return The obfuscated class bytes, or null if no remapping was applied
     */
    fun obfuscate(classBytes: ByteArray): ByteArray? {
        return obfuscate(classBytes.inputStream())
    }

    /**
     * Obfuscate class from input stream.
     *
     * @param inputStream The input stream containing class bytes
     * @return The obfuscated class bytes, or null if no remapping was applied
     */
    fun obfuscate(inputStream: InputStream): ByteArray? {
        val classReader = ClassReader(inputStream)
        val classWriter = ClassWriter(classReader, 0)

        val remapper = ObfuscationRemapper()
        val classRemapper = ClassRemapper(classWriter, remapper)

        classReader.accept(classRemapper, ClassReader.EXPAND_FRAMES)

        return if (remapper.hasRemapped) {
            classWriter.toByteArray()
        } else {
            null
        }
    }

    /**
     * Get the obfuscated class name for an original class name.
     *
     * @param originalName The original class name (e.g., "com.example.MyClass")
     * @return The obfuscated class name, or null if not found
     */
    fun getObfuscatedClassName(originalName: String): String? {
        val internal = originalName.replace('.', '/')
        return classNameMap[internal]?.replace('/', '.')
    }

    fun getOriginClassSigName(obfuscateClassDexName: String): String? {
        val internal = obfuscateClassDexName.asmSigFormat
        return antiClassNameMap[internal]?.classSigName
    }

    /**
     * Get the expected output path for a class file after obfuscation.
     *
     * @param inputFile The input class file
     * @param baseDir The base directory for calculating relative paths
     * @return The expected output path, or the original relative path if no mapping exists
     */
    fun getObfuscatedClassPath(inputFile: File, baseDir: File): String {
        val relativePath = inputFile.relativeTo(baseDir).path
        val className = relativePath
            .removeSuffix(".class")
            .replace(File.separatorChar, '/')

        val obfuscatedInternal = classNameMap[className]
        return if (obfuscatedInternal != null) {
            obfuscatedInternal.replace('/', File.separatorChar) + ".class"
        } else {
            relativePath
        }
    }

    /**
     * Check if a class name exists in the mapping.
     *
     * @param className The class name in internal format (e.g., "com/example/MyClass")
     * @return true if the class is in the mapping
     */
    fun hasClassMapping(className: String): Boolean {
        return classNameMap.containsKey(className)
    }

    /**
     * Get statistics about the loaded mapping.
     */
    fun getMappingStats(): MappingStats {
        return MappingStats(
            classCount = classNameMap.size,
            fieldCount = fieldNameMap.size,
            methodCount = methodNameMap.size
        )
    }

    data class MappingStats(
        val classCount: Int,
        val fieldCount: Int,
        val methodCount: Int
    )

    /**
     * Custom remapper that uses the mapping information to rename classes, methods, and fields.
     */
    private inner class ObfuscationRemapper : Remapper() {
        var hasRemapped = false
            private set

        override fun map(internalName: String): String {
            val mapped = classNameMap[internalName]
            if (mapped != null) {
                hasRemapped = true
                return mapped
            }
            return internalName
        }

        override fun mapType(internalName: String?): String? {
            if (internalName == null) return null
            val mapped = classNameMap[internalName]
            if (mapped != null) {
                hasRemapped = true
                return mapped
            }
            return internalName
        }

        override fun mapTypes(internalNames: Array<out String>?): Array<String>? {
            if (internalNames == null) return null
            var hasChanges = false
            val result = internalNames.map { original ->
                val mapped = classNameMap[original]
                if (mapped != null) {
                    hasRemapped = true
                    hasChanges = true
                    mapped
                } else {
                    original
                }
            }.toTypedArray()
            return if (hasChanges) result else null
        }

        override fun mapSignature(signature: String?, typeSignature: Boolean): String? {
            return super.mapSignature(signature, typeSignature)
        }

        override fun mapFieldName(owner: String, name: String, descriptor: String): String {
            // Convert owner to dot notation for lookup
            val ownerDot = owner.replace('/', '.')
            val key = "$ownerDot.$name"
            val mapped = fieldNameMap[key]
            if (mapped != null) {
                hasRemapped = true
                return mapped
            }
            return name
        }

        override fun mapMethodName(owner: String, name: String, descriptor: String): String {
            // Skip special methods
            if (name == "<init>" || name == "<clinit>") {
                return name
            }

            val ownerDot = owner.replace('/', '.')
            // Convert descriptor to simple parameter format for lookup
            val params = descriptorToParams(descriptor)
            val key = "$ownerDot.$name($params)"
            val mapped = methodNameMap[key]
            if (mapped != null) {
                hasRemapped = true
                return mapped
            }
            return name
        }

        /**
         * Convert JVM method descriptor to simple parameter format.
         * e.g., "(Ljava/lang/String;I)V" -> "java.lang.String,int"
         */
        private fun descriptorToParams(descriptor: String): String {
            val params = mutableListOf<String>()
            var i = 1 // Skip opening '('

            while (i < descriptor.length && descriptor[i] != ')') {
                when (descriptor[i]) {
                    'B' -> { params.add("byte"); i++ }
                    'C' -> { params.add("char"); i++ }
                    'D' -> { params.add("double"); i++ }
                    'F' -> { params.add("float"); i++ }
                    'I' -> { params.add("int"); i++ }
                    'J' -> { params.add("long"); i++ }
                    'S' -> { params.add("short"); i++ }
                    'Z' -> { params.add("boolean"); i++ }
                    'V' -> { params.add("void"); i++ }
                    'L' -> {
                        val end = descriptor.indexOf(';', i)
                        val className = descriptor.substring(i + 1, end).replace('/', '.')
                        params.add(className)
                        i = end + 1
                    }
                    '[' -> {
                        var arrayDepth = 0
                        while (descriptor[i] == '[') {
                            arrayDepth++
                            i++
                        }
                        val baseType = when (descriptor[i]) {
                            'B' -> { i++; "byte" }
                            'C' -> { i++; "char" }
                            'D' -> { i++; "double" }
                            'F' -> { i++; "float" }
                            'I' -> { i++; "int" }
                            'J' -> { i++; "long" }
                            'S' -> { i++; "short" }
                            'Z' -> { i++; "boolean" }
                            'L' -> {
                                val end = descriptor.indexOf(';', i)
                                val className = descriptor.substring(i + 1, end).replace('/', '.')
                                i = end + 1
                                className
                            }
                            else -> { i++; "unknown" }
                        }
                        params.add(baseType + "[]".repeat(arrayDepth))
                    }
                    else -> i++
                }
            }

            return params.joinToString(",")
        }
    }

    companion object {

        private var classObfuscatorCache: ClassObfuscator? = null
        private var classObfuscatorCacheKey: String? = null

        /**
         * Create an obfuscator from a mapping file.
         */
        fun fromMappingFile(mappingFile: File): ClassObfuscator {
            val newKey = mappingFile.absolutePath + "_" + mappingFile.lastModified()
            classObfuscatorCache
                ?.takeIf { classObfuscatorCacheKey == newKey }
                ?.let { return it }
            classObfuscatorCacheKey = newKey
            val reader = R8MappingReader.fromFile(mappingFile)
            val result = ClassObfuscator(reader)
            return result
        }

        /**
         * Create an obfuscator from mapping content string.
         */
        fun fromMappingString(mappingContent: String): ClassObfuscator {
            val reader = R8MappingReader.fromString(mappingContent)
            return ClassObfuscator(reader)
        }
    }
}
