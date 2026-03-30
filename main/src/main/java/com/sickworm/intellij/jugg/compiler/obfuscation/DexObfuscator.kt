package com.sickworm.intellij.jugg.compiler.obfuscation

import com.googlecode.d2j.DexConstants
import com.googlecode.d2j.DexType
import com.googlecode.d2j.Field
import com.googlecode.d2j.Method
import com.googlecode.d2j.Proto
import com.googlecode.d2j.Visibility
import com.googlecode.d2j.dex.writer.DexFileWriter
import com.googlecode.d2j.reader.DexFileReader
import com.googlecode.d2j.reader.Op
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

        // Deferred method entries: collected during first pass, processed after classMap is complete.
        // Each entry is (classOriginalName, simpleMethodName, parameters, obfuscatedName, invocations).
        data class DeferredMethod(
            val classOriginalName: String,
            val simpleMethodName: String,
            val parameters: String,
            val obfuscatedName: String,
            val invocations: List<R8MappingReader.MethodInvocation>
        )
        val deferredMethods = mutableListOf<DeferredMethod>()

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

            // Collect method entries for deferred processing
            classMapping.methods.forEach { method ->
                // R8 synthesized methods in facade classes may have qualified original names
                // e.g., "xxx.CollectionsKt.listOf" instead of just "listOf".
                // Extract the simple method name (last segment after '.') for the lookup key,
                // since mapMethod() always uses "ownerClassName.simpleMethodName(params)".
                val simpleMethodName = method.originalName.substringAfterLast('.')
                deferredMethods.add(DeferredMethod(
                    classOriginalName = classMapping.originalName,
                    simpleMethodName = simpleMethodName,
                    parameters = method.parameters,
                    obfuscatedName = method.obfuscatedName,
                    invocations = method.invocations
                ))
            }
        }

        // Build intermediate-form-to-original mapping for class names.
        // R8 synthesized methods may use "intermediate form" class names in parameters:
        //   e.g., "xxx.ClosedRange" (obfuscated package + original simple name)
        //   where original is "kotlin.ranges.ClosedRange" and fully obfuscated is "xxx.z07".
        // This mapping resolves such intermediate forms to the canonical original name.
        val intermediateToOriginal = mutableMapOf<String, String>()
        classMap.forEach { (originalInternal, obfuscatedInternal) ->
            val originalDot = originalInternal.replace('/', '.')
            val obfuscatedDot = obfuscatedInternal.replace('/', '.')
            val originalSimpleName = originalDot.substringAfterLast('.')
            val obfuscatedPackage = obfuscatedDot.substringBeforeLast('.', "")
            if (obfuscatedPackage.isNotEmpty()) {
                val intermediateForm = "$obfuscatedPackage.$originalSimpleName"
                // Only add if intermediate form differs from original (avoids self-mapping)
                if (intermediateForm != originalDot) {
                    intermediateToOriginal[intermediateForm] = originalDot
                }
            }
        }

        // Process deferred methods: normalize parameter types and build methodMap
        // When multiple entries share the same key (e.g., normal + synthesized entries),
        // prefer the entry that actually renames the method (obfuscatedName != simpleMethodName).
        // R8 synthesized entries often keep the original name (e.g., d -> d), while the
        // normal entry contains the real renaming (e.g., d -> a). Without this priority,
        // the synthesized entry can overwrite the correct mapping.
        deferredMethods.forEach { deferred ->
            val normalizedParams = normalizeMethodParams(deferred.parameters, intermediateToOriginal)
            val key = "${deferred.classOriginalName}.${deferred.simpleMethodName}($normalizedParams)"
            val existing = methodMap[key]
            if (existing == null) {
                // No existing entry; store unconditionally
                methodMap[key] = deferred.obfuscatedName
            } else if (existing == deferred.simpleMethodName && deferred.obfuscatedName != deferred.simpleMethodName) {
                // Existing entry is an identity mapping (name unchanged, e.g., d -> d),
                // but the new entry is a real rename (e.g., d -> a). Prefer the real rename.
                methodMap[key] = deferred.obfuscatedName
            }
            // Otherwise, keep the existing entry (it is already a real rename).

            // Build invocation map
            deferred.invocations.forEach { invocation ->
                val invocationKey = "${invocation.calledClass}.${invocation.calledMethod}"
                invocationMap.getOrPut(invocationKey) { mutableListOf() }
                    .add(deferred.classOriginalName)
            }
        }

        classNameMap = classMap
        antiClassNameMap = antiClassMap
        fieldNameMap = fieldMap
        methodNameMap = methodMap
        methodInvocationMap = invocationMap.mapValues { it.value.distinct() }
    }

    /**
     * Normalize method parameter types by resolving R8 intermediate-form class names
     * to their canonical original names.
     *
     * R8 synthesized methods may reference class types using an "intermediate form":
     * the obfuscated package prefix + the original simple class name (e.g., "xxx.ClosedRange"
     * instead of "kotlin.ranges.ClosedRange"). This method replaces such intermediate forms
     * with the canonical original name so that mapMethod() lookups match correctly.
     *
     * @param params Comma-separated parameter string from mapping (e.g., "int,xxx.ClosedRange")
     * @param intermediateToOriginal Mapping from intermediate form to canonical original name
     * @return Normalized parameter string (e.g., "int,kotlin.ranges.ClosedRange")
     */
    private fun normalizeMethodParams(
        params: String,
        intermediateToOriginal: Map<String, String>
    ): String {
        if (params.isEmpty() || intermediateToOriginal.isEmpty()) return params
        return params.split(",").joinToString(",") { param ->
            val trimmed = param.trim()
            // Handle array types: strip [] suffix, normalize base type, re-attach suffix
            val arraySuffix = trimmed.length - trimmed.trimEnd('[', ']').length
            val baseType = if (arraySuffix > 0) trimmed.substring(0, trimmed.length - arraySuffix) else trimmed
            val normalized = intermediateToOriginal[baseType] ?: baseType
            if (arraySuffix > 0) normalized + trimmed.substring(trimmed.length - arraySuffix) else normalized
        }
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

        // Build redirect mapping: original class name -> redirect class name (obfuscated + suffix).
        // Only include classes that have corresponding .class files (effectiveInlineEffectedClasses),
        // preventing redirection to non-existent _jugg_fix classes (e.g., boot classpath classes).
        //
        // Plan A: redirect target uses the obfuscated class name + _jugg_fix suffix.
        // Example: com/example/LogUtil -> a/b/c_jugg_fix (not com/example/LogUtil_jugg_fix).
        // This ensures incremental DEX call targets match _jugg_fix class declarations
        // produced by the obfuscate-then-rename pipeline.
        private val redirectClassMap: Map<String, String> = minifyInfo?.let { info ->
            info.effectiveInlineEffectedClasses.associate { effectedClass ->
                // className is already in ASM format (Lcom/example/MyClass;), convert to internal format (com/example/MyClass)
                val originalInternal = effectedClass.className.asmSigFormat
                val obfuscatedInternal = classNameMap[originalInternal] ?: originalInternal
                val redirectInternal = obfuscatedInternal + SUFFIX
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

            val classVisitor = super.visit(widenAccessFlags(accessFlags), mappedClassName, mappedSuperClass, mappedInterfaces)

            return object : DexClassVisitor(classVisitor) {
                private val originalClassName = className
                private val currentMappedClassName = mappedClassName
                // Plan L: record original hierarchy for method name resolution
                private val originalInterfaces: Array<out String> = interfaceNames ?: emptyArray()
                private val originalSuperClass: String? = superClass

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
                    val fieldVisitor = super.visitField(widenAccessFlags(accessFlags), mappedField, value)
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
                    val mappedMethod = mapMethodForCurrentClass(method)
                    if (mappedMethod != method) {
                        hasRemapped = true
                    }

                    val methodVisitor = super.visitMethod(widenAccessFlags(accessFlags), mappedMethod)

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
                                    // Plan E': when access flags are widened from private to public,
                                    // the method moves from the direct section to the virtual section.
                                    // Callers within the same class still use invoke-direct, which
                                    // will cause IncompatibleClassChangeError at runtime.
                                    // Fix: change invoke-direct → invoke-virtual for non-<init>,
                                    // same-class method calls.
                                    // Note: must also handle invoke-direct/range (INVOKE_DIRECT_RANGE)
                                    // which is used when register arguments exceed 4-bit encoding.
                                    val finalOp = if ((op == Op.INVOKE_DIRECT || op == Op.INVOKE_DIRECT_RANGE)
                                        && remappedMethod.owner == currentMappedClassName
                                        && remappedMethod.name != "<init>") {
                                        hasRemapped = true
                                        if (op == Op.INVOKE_DIRECT) Op.INVOKE_VIRTUAL else Op.INVOKE_VIRTUAL_RANGE
                                    } else {
                                        op
                                    }
                                    super.visitMethodStmt(finalOp, args, remappedMethod)
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
                                    // In DEX format, catch-all handlers have null as the exception
                                    // type. The Java library may pass arrays containing null elements,
                                    // so each element must be null-checked before calling mapType().
                                    @Suppress("SENSELESS_COMPARISON")
                                    val mappedTypes = types?.map { type ->
                                        if (type == null) {
                                            null
                                        } else {
                                            val mapped = mapType(type)
                                            if (mapped != type) hasRemapped = true
                                            mapped
                                        }
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

                /**
                 * Plan L: Map a method declared by the current class using hierarchy-first resolution.
                 * 1. Search interfaces and superclass for method name mapping
                 * 2. Fall back to the current class's own mapping
                 * 3. Keep original name if not found anywhere
                 *
                 * Only used for visitMethod() (class-declared methods).
                 * visitMethodStmt() (call instructions) continues to use mapMethod() directly
                 * because the callee's owner type already points to the declaring class/interface.
                 */
                private fun mapMethodForCurrentClass(method: Method): Method {
                    // Skip special methods
                    if (method.name == "<init>" || method.name == "<clinit>") {
                        val ownerMapped = mapType(method.owner)
                        val protoMapped = mapProto(method.proto)
                        return if (ownerMapped != method.owner || protoMapped != method.proto) {
                            Method(ownerMapped, method.name, protoMapped)
                        } else {
                            method
                        }
                    }

                    val ownerMapped = mapType(method.owner)

                    // Plan L: hierarchy-first method name resolution
                    val nameMapped = mapMethodNameFromHierarchy(method)
                        ?: run {
                            // Fallback: existing class-own lookup
                            val ownerDot = method.owner.asmSigFormat.replace('/', '.')
                            val params = protoToParams(method.proto)
                            val key = "$ownerDot.${method.name}($params)"
                            methodNameMap[key] ?: method.name
                        }

                    val protoMapped = mapProto(method.proto)

                    return if (ownerMapped != method.owner || nameMapped != method.name || protoMapped != method.proto) {
                        Method(ownerMapped, nameMapped, protoMapped)
                    } else {
                        method
                    }
                }

                /**
                 * Plan L: Search interfaces and superclass for a method name mapping.
                 * @return The obfuscated method name if found, null otherwise.
                 */
                private fun mapMethodNameFromHierarchy(method: Method): String? {
                    val methodName = method.name
                    val params = protoToParams(method.proto)

                    // Step 1: search all interfaces
                    for (iface in originalInterfaces) {
                        val ifaceDot = iface.asmSigFormat.replace('/', '.')
                        val key = "$ifaceDot.$methodName($params)"
                        methodNameMap[key]?.let { return it }
                    }

                    // Step 2: search superclass (skip java.lang.Object)
                    if (originalSuperClass != null && originalSuperClass != "Ljava/lang/Object;") {
                        val superDot = originalSuperClass.asmSigFormat.replace('/', '.')
                        val key = "$superDot.$methodName($params)"
                        methodNameMap[key]?.let { return it }
                    }

                    return null
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
         * Widen access flags to public.
         * R8 with -allowaccessmodification unconditionally widens all private/protected/package-private
         * members to public. Jugg must replicate this to avoid IllegalAccessError / AbstractMethodError
         * when APK-resident classes (e.g., ExternalSyntheticLambda) call into incrementally-compiled classes.
         */
        private fun widenAccessFlags(accessFlags: Int): Int {
            // Clear private and protected bits, set public bit
            return (accessFlags and DexConstants.ACC_PRIVATE.inv() and DexConstants.ACC_PROTECTED.inv()) or
                    DexConstants.ACC_PUBLIC
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

    /**
     * Rename only the class declaration in a DEX file, preserving all internal references.
     *
     * This is the key method for Plan A (obfuscate-then-rename). After obfuscation,
     * the _jugg_fix class must be a bridge/proxy whose internal method calls still
     * point to the original obfuscated class (e.g., La/b/c;), NOT to itself
     * (La/b/c_jugg_fix;). This ensures that when ClassA is updated incrementally,
     * callers through _jugg_fix still reach the new implementation in ClassA.
     *
     * What gets renamed (declaration level):
     *   - Class declaration (visit() className)
     *   - Method declaration owners (visitMethod() method.owner)
     *   - Field declaration owners (visitField() field.owner)
     *
     * What is NOT renamed (code body references):
     *   - Method call owners in visitMethodStmt()
     *   - Field access owners in visitFieldStmt()
     *   - Type references in visitTypeStmt(), visitConstStmt(), etc.
     *   - Super class and interface references
     *
     * @param dexBytes The input DEX bytes (already obfuscated)
     * @param oldClassName The current class name in sig format (e.g., "La/b/c;")
     * @param newClassName The new class name in sig format (e.g., "La/b/c_jugg_fix;")
     * @return The DEX bytes with only the class declaration renamed
     */
    fun renameDexClassDeclaration(
        dexBytes: ByteArray,
        oldClassName: String,
        newClassName: String
    ): ByteArray {
        val dexReader = DexFileReader(dexBytes)
        val dexWriter = DexFileWriter()

        dexReader.accept(object : DexFileVisitor(dexWriter) {
            override fun visit(
                accessFlags: Int,
                className: String,
                superClass: String?,
                interfaceNames: Array<out String>?
            ): DexClassVisitor {
                // Rename only the class declaration
                val renamedClassName = if (className == oldClassName) newClassName else className
                val classVisitor = super.visit(accessFlags, renamedClassName, superClass, interfaceNames)

                return object : DexClassVisitor(classVisitor) {
                    override fun visitField(accessFlags: Int, field: Field, value: Any?): DexFieldVisitor {
                        // Rename field declaration owner
                        val renamedField = if (field.owner == oldClassName) {
                            Field(newClassName, field.name, field.type)
                        } else {
                            field
                        }
                        return super.visitField(accessFlags, renamedField, value)
                    }

                    override fun visitMethod(accessFlags: Int, method: Method): DexMethodVisitor {
                        // Rename method declaration owner
                        val renamedMethod = if (method.owner == oldClassName) {
                            Method(newClassName, method.name, method.proto)
                        } else {
                            method
                        }
                        // Pass through to writer WITHOUT intercepting code body visitors.
                        // All method call owners, field access owners, and type references
                        // inside the code body remain unchanged — this is the core design.
                        return super.visitMethod(accessFlags, renamedMethod)
                    }
                }
            }
        }, 0)

        return dexWriter.toByteArray()
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
