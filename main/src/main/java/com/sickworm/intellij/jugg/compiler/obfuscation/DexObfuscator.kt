package com.sickworm.intellij.jugg.compiler.obfuscation

import com.googlecode.d2j.DexType
import com.googlecode.d2j.Field
import com.googlecode.d2j.Method
import com.googlecode.d2j.Proto
import com.googlecode.d2j.Visibility
import com.googlecode.d2j.dex.writer.DexFileWriter
import com.googlecode.d2j.reader.DexFileReader
import com.googlecode.d2j.visitors.*
import com.sickworm.intellij.jugg.deploy.asmSigFormat
import com.sickworm.intellij.jugg.deploy.classSigName
import java.io.File
import java.io.InputStream

/**
 * DEX file obfuscator using dex-reader/dex-writer.
 *
 * Remaps class names, method names, and field names in DEX bytecode
 * based on the provided mapping information from R8MappingReader.
 */
class DexObfuscator(mappingReader: R8MappingReader) {

    // Build lookup maps for efficient remapping
    // originalName (internal format) -> obfuscatedName (internal format)
    private val classNameMap: Map<String, String>
    private val antiClassNameMap: Map<String, String>
    // originalClassName + "." + originalFieldName -> obfuscatedFieldName
    private val fieldNameMap: Map<String, String>
    // originalClassName + "." + originalMethodName + methodDescriptor -> obfuscatedMethodName
    private val methodNameMap: Map<String, String>
    // Store method invocation information for inline detection
    // Key: originalClassName.methodName -> List of caller class names (original names)
    private val methodInvocationMap: Map<String, List<String>>

    init {
        val classMap = mutableMapOf<String, String>()
        val antiClassMap = mutableMapOf<String, String>()
        val fieldMap = mutableMapOf<String, String>()
        val methodMap = mutableMapOf<String, String>()
        val invocationMap = mutableMapOf<String, MutableList<String>>()

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

            // Build method mapping and invocation mapping
            classMapping.methods.forEach { method ->
                val key = "${classMapping.originalName}.${method.originalName}(${method.parameters})"
                methodMap[key] = method.obfuscatedName

                // Build invocation map: for each method that has invocations,
                // record which classes call which methods
                method.invocations.forEach { invocation ->
                    val invocationKey = "${invocation.calledClass}.${invocation.calledMethod}"
                    invocationMap.getOrPut(invocationKey) { mutableListOf() }
                        .add(classMapping.originalName)
                }
            }
        }

        classNameMap = classMap
        antiClassNameMap = antiClassMap
        fieldNameMap = fieldMap
        methodNameMap = methodMap
        methodInvocationMap = invocationMap.mapValues { it.value.distinct() }
    }

    /**
     * Obfuscate a DEX file.
     *
     * @param inputFile The input DEX file
     * @param outputFile The output DEX file
     * @return true if obfuscation was applied, false if no classes were found in mapping
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
     * Obfuscate DEX bytes.
     *
     * @param dexBytes The input DEX bytes
     * @return The obfuscated DEX bytes, or null if no remapping was applied
     */
    fun obfuscate(dexBytes: ByteArray): ByteArray? {
        return obfuscate(dexBytes.inputStream())
    }

    /**
     * Obfuscate DEX from input stream.
     *
     * @param inputStream The input stream containing DEX bytes
     * @return The obfuscated DEX bytes, or null if no remapping was applied
     */
    fun obfuscate(inputStream: InputStream): ByteArray? {
        val dexReader = DexFileReader(inputStream.readBytes())
        val dexWriter = DexFileWriter()

        val remapper = ObfuscationDexRemapper(dexWriter)
        dexReader.accept(remapper, 0)

        return if (remapper.hasRemapped) {
            dexWriter.toByteArray()
        } else {
            null
        }
    }

    /**
     * Obfuscate DEX with inline redirect support.
     *
     * @param dexBytes The input DEX bytes
     * @param minifyInfo Optional inline redirect information
     * @return The obfuscated DEX bytes, or null if no remapping was applied
     */
    fun obfuscateWithInlineRedirect(dexBytes: ByteArray, minifyInfo: MinifyInfo?): ByteArray? {
        if (minifyInfo == null) {
            return obfuscate(dexBytes)
        }

        val dexReader = DexFileReader(dexBytes)
        val dexWriter = DexFileWriter()

        val remapper = ObfuscationDexRemapper(dexWriter, minifyInfo)
        dexReader.accept(remapper, 0)

        return if (remapper.hasRemapped) {
            dexWriter.toByteArray()
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

    /**
     * Get the original class signature name from obfuscated DEX name.
     *
     * @param obfuscateClassDexName The obfuscated class name in DEX format (e.g., "La/b/c;")
     * @return The original class signature name, or null if not found
     */
    fun getOriginClassSigName(obfuscateClassDexName: String): String? {
        val internal = obfuscateClassDexName.asmSigFormat
        return antiClassNameMap[internal]?.classSigName
    }

    /**
     * Get the expected output path for a DEX file after obfuscation.
     *
     * @param inputFile The input DEX file
     * @param baseDir The base directory for calculating relative paths
     * @return The expected output path, or the original relative path if no mapping exists
     */
    fun getObfuscatedDexPath(inputFile: File, baseDir: File): String {
        // For DEX files, we typically keep the same file name
        // but this can be customized based on requirements
        return inputFile.relativeTo(baseDir).path
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
     * Find all classes that invoke a specific method (for inline detection).
     *
     * @param originalClassName The original class name in dot notation (e.g., "com.example.MyClass")
     * @param methodName The method name
     * @return List of original class names (in dot notation) that invoke this method
     */
    fun findInvocationsOf(originalClassName: String, methodName: String): List<String> {
        val key = "$originalClassName.$methodName"
        return methodInvocationMap[key] ?: emptyList()
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

    /**
     * MappingStats carries classCount, fieldCount, and methodCount.
     */
    data class MappingStats(
        val classCount: Int,
        val fieldCount: Int,
        val methodCount: Int
    )

    /**
     * Custom DEX remapper that uses the mapping information to rename classes, methods, and fields.
     */
    private inner class ObfuscationDexRemapper(
        dexWriter: DexFileWriter,
        private val minifyInfo: MinifyInfo? = null
    ) : DexFileVisitor(dexWriter) {
        var hasRemapped = false
            private set

        // Build redirect mapping: original class name -> redirect class name
        // className format: "Lcom/example/MyClass;" (ASM signature format)
        private val redirectClassMap: Map<String, String> = minifyInfo?.let { info ->
            info.inlineEffectedClasses.associate { effectedClass ->
                // className is already in ASM format (Lcom/example/MyClass;), convert to internal format (com/example/MyClass)
                val originalInternal = effectedClass.className.asmSigFormat
                val redirectInternal = originalInternal + SUFFIX
                originalInternal to redirectInternal
            }
        } ?: emptyMap()

        override fun visit(
            accessFlags: Int,
            className: String,
            superClass: String?,
            interfaceNames: Array<out String>?
        ): DexClassVisitor {
            // Map class name (className is in format "Lcom/example/MyClass;")
            val mappedClassName = mapType(className)
            val mappedSuperClass = superClass?.let { mapType(it) }
            val mappedInterfaces = interfaceNames?.map { mapType(it) }?.toTypedArray()

            if (mappedClassName != className || mappedSuperClass != superClass || 
                mappedInterfaces?.contentEquals(interfaceNames) == false) {
                hasRemapped = true
            }

            val classVisitor = super.visit(accessFlags, mappedClassName, mappedSuperClass, mappedInterfaces)

            return object : DexClassVisitor(classVisitor) {
                private val originalClassName = className

                override fun visitAnnotation(name: String?, visibility: Visibility?): DexAnnotationVisitor {
                    // Fix 1: map annotation type descriptor
                    val mappedName = name?.let { mapType(it) }
                    if (mappedName != name) hasRemapped = true
                    val annotationVisitor = super.visitAnnotation(mappedName, visibility)
                    return createAnnotationRemapper(annotationVisitor)
                }

                override fun visitField(accessFlags: Int, field: Field, value: Any?): DexFieldVisitor {
                    val mappedField = mapField(field)
                    if (mappedField != field) {
                        hasRemapped = true
                    }
                    // Fix 3: wrap field visitor to handle field-level annotations
                    val fieldVisitor = super.visitField(accessFlags, mappedField, value)
                    return object : DexFieldVisitor(fieldVisitor) {
                        override fun visitAnnotation(name: String?, visibility: Visibility?): DexAnnotationVisitor {
                            val mappedName = name?.let { mapType(it) }
                            if (mappedName != name) hasRemapped = true
                            val annotationVisitor = super.visitAnnotation(mappedName, visibility)
                            return createAnnotationRemapper(annotationVisitor)
                        }
                    }
                }

                override fun visitMethod(accessFlags: Int, method: Method): DexMethodVisitor {
                    val mappedMethod = mapMethod(method)
                    if (mappedMethod != method) {
                        hasRemapped = true
                    }

                    val methodVisitor = super.visitMethod(accessFlags, mappedMethod)

                    return object : DexMethodVisitor(methodVisitor) {
                        // Fix 2: handle method-level annotations
                        override fun visitAnnotation(name: String?, visibility: Visibility?): DexAnnotationVisitor {
                            val mappedName = name?.let { mapType(it) }
                            if (mappedName != name) hasRemapped = true
                            val annotationVisitor = super.visitAnnotation(mappedName, visibility)
                            return createAnnotationRemapper(annotationVisitor)
                        }

                        override fun visitCode(): DexCodeVisitor {
                            val codeVisitor = super.visitCode()
                            return object : DexCodeVisitor(codeVisitor) {
                                override fun visitConstStmt(op: com.googlecode.d2j.reader.Op?, a: Int, value: Any?) {
                                    // Handle const-class instructions where value is DexType
                                    val mappedValue = when (value) {
                                        is DexType -> {
                                            val mapped = mapType(value.desc)
                                            if (mapped != value.desc) {
                                                hasRemapped = true
                                                DexType(mapped)
                                            } else {
                                                value
                                            }
                                        }
                                        else -> value
                                    }
                                    super.visitConstStmt(op, a, mappedValue)
                                }

                                override fun visitFieldStmt(op: com.googlecode.d2j.reader.Op?, a: Int, b: Int, field: Field) {
                                    val mappedField = mapField(field)
                                    if (mappedField != field) {
                                        hasRemapped = true
                                    }
                                    super.visitFieldStmt(op, a, b, mappedField)
                                }

                                override fun visitMethodStmt(op: com.googlecode.d2j.reader.Op?, args: IntArray?, method: Method) {
                                    val remappedMethod = mapMethod(method)
                                    if (remappedMethod != method) {
                                        hasRemapped = true
                                    }
                                    super.visitMethodStmt(op, args, remappedMethod)
                                }

                                override fun visitMethodStmt(op: com.googlecode.d2j.reader.Op?, args: IntArray?, bsm: Method?, proto: Proto?) {
                                    // invoke-polymorphic: map the bootstrap method and proto
                                    val mappedBsm = bsm?.let { mapMethod(it) }
                                    val mappedProto = proto?.let { mapProto(it) }
                                    if (mappedBsm != bsm || mappedProto != proto) {
                                        hasRemapped = true
                                    }
                                    super.visitMethodStmt(op, args, mappedBsm, mappedProto)
                                }

                                override fun visitMethodStmt(
                                    op: com.googlecode.d2j.reader.Op?,
                                    args: IntArray?,
                                    name: String?,
                                    proto: Proto?,
                                    bsm: com.googlecode.d2j.MethodHandle?,
                                    vararg bsmArgs: Any?
                                ) {
                                    // invoke-custom: map proto and DexType values in bsmArgs
                                    val mappedProto = proto?.let { mapProto(it) }
                                    val mappedBsmArgs = bsmArgs.map { arg ->
                                        when (arg) {
                                            is DexType -> {
                                                val mapped = mapType(arg.desc)
                                                if (mapped != arg.desc) {
                                                    hasRemapped = true
                                                    DexType(mapped)
                                                } else {
                                                    arg
                                                }
                                            }
                                            is Method -> {
                                                val mapped = mapMethod(arg)
                                                if (mapped != arg) hasRemapped = true
                                                mapped
                                            }
                                            is Proto -> {
                                                val mapped = mapProto(arg)
                                                if (mapped != arg) hasRemapped = true
                                                mapped
                                            }
                                            else -> arg
                                        }
                                    }.toTypedArray()
                                    if (mappedProto != proto) hasRemapped = true
                                    super.visitMethodStmt(op, args, name, mappedProto, bsm, *mappedBsmArgs)
                                }

                                override fun visitFilledNewArrayStmt(op: com.googlecode.d2j.reader.Op?, args: IntArray?, type: String) {
                                    val mappedType = mapType(type)
                                    if (mappedType != type) {
                                        hasRemapped = true
                                    }
                                    super.visitFilledNewArrayStmt(op, args, mappedType)
                                }

                                override fun visitTryCatch(
                                    start: com.googlecode.d2j.DexLabel?,
                                    end: com.googlecode.d2j.DexLabel?,
                                    handler: Array<out com.googlecode.d2j.DexLabel>?,
                                    types: Array<out String>?
                                ) {
                                    val mappedTypes = types?.map { type ->
                                        val mapped = mapType(type)
                                        if (mapped != type) hasRemapped = true
                                        mapped
                                    }?.toTypedArray()
                                    super.visitTryCatch(start, end, handler, mappedTypes)
                                }

                                override fun visitTypeStmt(op: com.googlecode.d2j.reader.Op?, a: Int, b: Int, type: String) {
                                    val mappedType = mapType(type)
                                    if (mappedType != type) {
                                        hasRemapped = true
                                    }
                                    super.visitTypeStmt(op, a, b, mappedType)
                                }
                            }
                        }
                    }
                }
            }
        }

        /**
         * Create a DexAnnotationVisitor that remaps DexType values in annotation elements.
         * Reused for class-level, method-level, and field-level annotation handling.
         */
        private fun createAnnotationRemapper(delegate: DexAnnotationVisitor): DexAnnotationVisitor {
            return object : DexAnnotationVisitor(delegate) {
                override fun visit(name: String?, value: Any?) {
                    val mappedValue = when (value) {
                        is DexType -> {
                            val mapped = mapType(value.desc)
                            if (mapped != value.desc) {
                                hasRemapped = true
                                DexType(mapped)
                            } else {
                                value
                            }
                        }
                        else -> value
                    }
                    super.visit(name, mappedValue)
                }

                override fun visitArray(name: String?): DexAnnotationVisitor {
                    val arrayVisitor = super.visitArray(name)
                    return createAnnotationRemapper(arrayVisitor)
                }
            }
        }

        /**
         * Map a type descriptor (e.g., "Lcom/example/MyClass;")
         */
        private fun mapType(typeDesc: String): String {
            if (typeDesc.startsWith("[")) {
                // Handle array descriptors like [I / [Ljava/lang/String; / [[Lcom/foo/Bar;
                var arrayDepth = 0
                while (arrayDepth < typeDesc.length && typeDesc[arrayDepth] == '[') {
                    arrayDepth++
                }
                if (arrayDepth >= typeDesc.length) {
                    return typeDesc
                }
                val elementDesc = typeDesc.substring(arrayDepth)
                val mappedElementDesc = if (elementDesc.startsWith("L") && elementDesc.endsWith(";")) {
                    mapType(elementDesc)
                } else {
                    elementDesc
                }
                return if (mappedElementDesc == elementDesc) {
                    typeDesc
                } else {
                    "[".repeat(arrayDepth) + mappedElementDesc
                }
            }
            if (!typeDesc.startsWith("L") || !typeDesc.endsWith(";")) {
                // Primitive type or array of primitives
                return typeDesc
            }

            val internal = typeDesc.asmSigFormat

            // First check if redirection is needed
            val redirected = redirectClassMap[internal]
            if (redirected != null) {
                return redirected.classSigName
            }

            // Then apply obfuscation mapping
            val mapped = classNameMap[internal]
            return if (mapped != null) {
                mapped.classSigName
            } else {
                typeDesc
            }
        }

        /**
         * Map a field reference.
         */
        private fun mapField(field: Field): Field {
            val ownerMapped = mapType(field.owner)
            
            // Map field name
            val ownerDot = field.owner.asmSigFormat.replace('/', '.')
            val key = "$ownerDot.${field.name}"
            val nameMapped = fieldNameMap[key] ?: field.name

            val typeMapped = mapType(field.type)

            return if (ownerMapped != field.owner || nameMapped != field.name || typeMapped != field.type) {
                Field(ownerMapped, nameMapped, typeMapped)
            } else {
                field
            }
        }

        /**
         * Map a method reference.
         */
        private fun mapMethod(method: Method): Method {
            // Skip special methods
            if (method.name == "<init>" || method.name == "<clinit>") {
                // Still need to map owner and proto
                val ownerMapped = mapType(method.owner)
                val protoMapped = mapProto(method.proto)
                return if (ownerMapped != method.owner || protoMapped != method.proto) {
                    Method(ownerMapped, method.name, protoMapped)
                } else {
                    method
                }
            }

            val ownerMapped = mapType(method.owner)
            
            // Map method name
            val ownerDot = method.owner.asmSigFormat.replace('/', '.')
            val params = protoToParams(method.proto)
            val key = "$ownerDot.${method.name}($params)"
            val nameMapped = methodNameMap[key] ?: method.name

            val protoMapped = mapProto(method.proto)

            return if (ownerMapped != method.owner || nameMapped != method.name || protoMapped != method.proto) {
                Method(ownerMapped, nameMapped, protoMapped)
            } else {
                method
            }
        }

        /**
         * Map a method prototype (parameters and return type).
         */
        private fun mapProto(proto: Proto): Proto {
            val paramsMapped = proto.parameterTypes.map { mapType(it) }.toTypedArray()
            val returnMapped = mapType(proto.returnType)

            return if (!paramsMapped.contentEquals(proto.parameterTypes) || returnMapped != proto.returnType) {
                Proto(paramsMapped, returnMapped)
            } else {
                proto
            }
        }

        /**
         * Convert Proto to simple parameter format for method lookup.
         * e.g., Proto with ["Ljava/lang/String;", "I"] -> "java.lang.String,int"
         */
        private fun protoToParams(proto: Proto): String {
            return proto.parameterTypes.joinToString(",") { typeDesc ->
                when (typeDesc) {
                    "B" -> "byte"
                    "C" -> "char"
                    "D" -> "double"
                    "F" -> "float"
                    "I" -> "int"
                    "J" -> "long"
                    "S" -> "short"
                    "Z" -> "boolean"
                    "V" -> "void"
                    else -> {
                        if (typeDesc.startsWith("L") && typeDesc.endsWith(";")) {
                            typeDesc.asmSigFormat.replace('/', '.')
                        } else if (typeDesc.startsWith("[")) {
                            // Array type
                            var depth = 0
                            var i = 0
                            while (typeDesc[i] == '[') {
                                depth++
                                i++
                            }
                            val baseType = when (typeDesc[i]) {
                                'B' -> "byte"
                                'C' -> "char"
                                'D' -> "double"
                                'F' -> "float"
                                'I' -> "int"
                                'J' -> "long"
                                'S' -> "short"
                                'Z' -> "boolean"
                                'L' -> typeDesc.substring(i + 1, typeDesc.length - 1).replace('/', '.')
                                else -> "unknown"
                            }
                            baseType + "[]".repeat(depth)
                        } else {
                            typeDesc
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val SUFFIX = "_jugg_fix"  // Keep consistent with EffectedClassNode.SUFFIX

        private var dexObfuscatorCache: DexObfuscator? = null
        private var dexObfuscatorCacheKey: String? = null

        /**
         * Create an obfuscator from a mapping file.
         */
        fun fromMappingFile(mappingFile: File): DexObfuscator {
            val newKey = mappingFile.absolutePath + "_" + mappingFile.lastModified()
            dexObfuscatorCache
                ?.takeIf { dexObfuscatorCacheKey == newKey }
                ?.let { return it }
            dexObfuscatorCacheKey = newKey
            val reader = R8MappingReader.fromFile(mappingFile)
            val result = DexObfuscator(reader)
            dexObfuscatorCache = result
            return result
        }

        /**
         * Create an obfuscator from mapping content string.
         */
        fun fromMappingString(mappingContent: String): DexObfuscator {
            val reader = R8MappingReader.fromString(mappingContent)
            return DexObfuscator(reader)
        }
    }
}
