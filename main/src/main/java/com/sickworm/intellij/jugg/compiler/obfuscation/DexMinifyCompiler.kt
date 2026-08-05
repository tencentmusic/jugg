package com.sickworm.intellij.jugg.compiler.obfuscation

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.org.objectweb.asm.AnnotationVisitor
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassReader
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassVisitor
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassWriter
import com.sickworm.intellij.jugg.org.objectweb.asm.Handle
import com.sickworm.intellij.jugg.org.objectweb.asm.Label
import com.sickworm.intellij.jugg.org.objectweb.asm.MethodVisitor
import com.sickworm.intellij.jugg.org.objectweb.asm.Opcodes
import com.sickworm.intellij.jugg.org.objectweb.asm.Type
import com.sickworm.intellij.jugg.org.objectweb.asm.TypePath
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

/**
 * Compiler that re-obfuscates dex files based on mapping.txt.
 *
 * This compiler reads the mapping file from the deployed APK and applies
 * the obfuscation mapping to incremental dex files, ensuring consistency
 * with the original obfuscated APK.
 *
 * ## R8 Inline Handling (Phase 1)
 *
 * When R8 inlines methods, it copies the method body into the caller class.
 * If the inlined method implementation changes, we need to detect which classes
 * contain the inlined code to avoid runtime errors.
 *
 * **Phase 1 (Current)**: Detection only
 * - Detects classes affected by inline changes via MinifyInfo
 * - Logs warnings about inline-affected classes
 * - Does NOT yet implement full redirection (Phase 2)
 *
 * **Phase 2 (Future)**: Full redirection
 * - Generate _jugg_fix classes for inline-affected classes
 * - Redirect calls in DEX to _jugg_fix classes
 * - Enable hot-reload without recompiling inline-affected classes
 */
class DexMinifyCompiler(
    context: ICompileContext,
    parent: Disposable,
): BaseCompiler(context, parent) {

    override val supportedTypes = listOf(CompileFile.Type.Dex)

    override val beforeCompileOrderRange: IntRange = CompileOrder.beforeMinify
    override val afterCompileOrderRange: IntRange = CompileOrder.afterMinify

    private lateinit var obfuscator: DexObfuscator
    private var usageReader: R8UsageReader? = null
    private var usageReaderCacheKey: String? = null

    override fun compile(task: CompileTask): CompileResult {
        initIfNeeded(task)?.let { failedResult ->
            return failedResult
        }

        return process(task)
    }

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        // no need implement
        return CompileResult.empty(task)
    }

    private fun initIfNeeded(task: CompileTask): CompileResult? {
        if (!task.isNeedCompile) {
            return task.wrapToResult()
        }

        // Try to find mapping file from incremental data directory
        val mappingFile = context.mappingFile
        if (!context.isMinified || mappingFile == null || !mappingFile.exists()) {
            if (context.isReleaseApk) {
                logger.warn("This appears to be a release build, but mapping file not found, skip obfuscation.")
                logger.warn("Compile result may not correct.")
            } else {
                logger.debug("No mapping file found, skip obfuscation.")
            }
            return task.wrapToResult()
        }

        // Initialize obfuscator if mapping file changed
        if (!::obfuscator.isInitialized) {
            TimeLogger.start("load mapping file")
            logger.debug("Loading mapping file: ${mappingFile.absolutePath}")
            obfuscator = DexObfuscator.fromMappingFile(mappingFile)
            val stats = obfuscator.getMappingStats()
            logger.debug("Mapping loaded: ${stats.classCount} classes, ${stats.fieldCount} fields, ${stats.methodCount} methods")
            TimeLogger.end("load mapping file", logger)
        }

        initUsageReader()
        return null
    }

    private fun process(task: CompileTask): CompileResult {
        val details = mutableListOf<Result<CompileFile, CompileError>>()
        val outputs = mutableListOf<CompileOutput>()

        // 1. Get inline impact information
        // Pre-obfuscate dex files before passing to getMinifyInfo, because task.files
        // contain un-obfuscated dex (original class names) from DexCompiler, but the
        // deploy database stores obfuscated class names from the APK. Without this step,
        // parseDex reads original names that can't match DB entries, causing false
        // "missing classes" detection.
        val obfuscatedCompileFiles = preObfuscateForMinifyInfo(task)
        val minifyInfo = context.getMinifyInfo(obfuscatedCompileFiles)

        if (minifyInfo != null) {
            logger.info("Found ${minifyInfo.inlineEffectedClasses.size} inline effected classes")
            logger.debug("Inline effected classes: ${minifyInfo.inlineEffectedClasses.map { it.className }}")
            logger.info("MinifyInfo classFiles size: ${minifyInfo.classFiles.size}")
            logger.debug("MinifyInfo classFiles: ${minifyInfo.classFiles}")
        }

        // 2. Phase 2: Generate DEX files for _jugg_fix classes
        if (minifyInfo != null && minifyInfo.classFiles.isNotEmpty()) {
            try {
                val fixClassOutputs = generateJuggFixClasses(minifyInfo, task.outputDir)
                outputs.addAll(fixClassOutputs)
                logger.info("Generated ${fixClassOutputs.size} _jugg_fix DEX files")
            } catch (e: Exception) {
                logger.warn("Failed to generate _jugg_fix classes", e)
            }
        }

        val detailLog = StringBuilder("Obfuscated: ")
        for (compileFile in task.files) {
            try {
                val result = obfuscateDexFile(compileFile, task.outputDir, obfuscator, minifyInfo)
                if (result != null) {
                    details.add(Result.success(compileFile))
                    outputs.add(result)
                    detailLog.append("${compileFile.file.name} -> ${result.file.name}\"")
                } else {
                    // No mapping for this class, output as-is
                    val output = copyDexFile(compileFile, task.outputDir)
                    details.add(Result.success(compileFile))
                    outputs.add(output)
                }
            } catch (e: Exception) {
                logger.debug("Failed to obfuscate ${compileFile.file.name}", e)
                details.add(
                    Result.failure(
                        CompileError(compileFile, listOf(-1L to (e.message ?: "Unknown error")))
                    )
                )
            }
        }
        logger.debug(detailLog.toString())

        return CompileResult(task, details, outputs)
    }

    /**
     * Pre-obfuscate dex files for getMinifyInfo consumption.
     *
     * task.files from DexCompiler contain un-obfuscated dex (original class names).
     * getMinifyInfo -> parseDex -> DB lookup expects obfuscated class names.
     * This method applies plain obfuscation (without inline redirect) to produce
     * temporary dex files with obfuscated content, so class names match the DB.
     *
     * @return List of CompileFiles pointing to pre-obfuscated temporary dex files.
     *         Files that have no mapping are included as-is.
     */
    private fun preObfuscateForMinifyInfo(task: CompileTask): List<CompileFile> {
        val preObfuscateDir = File(context.tempCompileDir, "pre_obfuscate_for_minify")
        preObfuscateDir.deleteRecursively()
        preObfuscateDir.mkdirs()

        return task.files.map { compileFile ->
            if (compileFile.type != CompileFile.Type.Dex) {
                return@map compileFile
            }
            try {
                val inputBytes = compileFile.file.readBytes()
                val obfuscatedBytes = obfuscator.obfuscate(inputBytes)
                if (obfuscatedBytes != null) {
                    val obfuscatedPath = obfuscator.getObfuscatedDexPath(
                        compileFile.file, compileFile.baseDir
                    )
                    val tempFile = File(preObfuscateDir, obfuscatedPath)
                    tempFile.parentFile?.mkdirs()
                    tempFile.writeBytes(obfuscatedBytes)
                    compileFile.copy(file = tempFile, baseDir = preObfuscateDir)
                } else {
                    compileFile
                }
            } catch (e: Exception) {
                logger.debug("Pre-obfuscate failed for ${compileFile.file.name}, using original", e)
                compileFile
            }
        }
    }

    /**
     * Obfuscate a class file and write to output directory.
     *
     * @return CompileOutput if obfuscation was applied, null otherwise
     */
    private fun obfuscateDexFile(
        compileFile: CompileFile,
        outputDir: File,
        obfuscator: DexObfuscator,
        minifyInfo: MinifyInfo?
    ): CompileOutput? {
        val inputFile = compileFile.file
        val baseDir = compileFile.baseDir

        // Get the obfuscated output path
        val obfuscatedPath = obfuscator.getObfuscatedDexPath(inputFile, baseDir)
        val outputFile = File(outputDir, obfuscatedPath)

        // Read and obfuscate
        val inputBytes = inputFile.readBytes()
        val outputBytes = if (minifyInfo != null) {
            obfuscator.obfuscateWithInlineRedirect(inputBytes, minifyInfo)
        } else {
            obfuscator.obfuscate(inputBytes)
        }

        if (outputBytes != null) {
            outputFile.parentFile?.mkdirs()
            outputFile.writeBytes(outputBytes)
            return CompileOutput(CompileOutput.Type.Dex, outputFile, outputDir)
        }

        return null
    }

    /**
     * Copy dex file to output directory without obfuscation.
     */
    private fun copyDexFile(compileFile: CompileFile, outputDir: File): CompileOutput {
        val inputFile = compileFile.file
        val relativePath = inputFile.relativeTo(compileFile.baseDir).path
        val outputFile = File(outputDir, relativePath)

        outputFile.parentFile?.mkdirs()
        inputFile.copyTo(outputFile, overwrite = true)
        logger.debug("Copied (no mapping): ${inputFile.name}")

        return CompileOutput(CompileOutput.Type.Dex, outputFile, outputDir)
    }

    /**
     * Generate _jugg_fix classes for inline-affected classes.
     *
     * Plan A pipeline (obfuscate-then-rename):
     *   1. Original .class -> D8 -> DEX (with original class names)
     *   2. DEX -> obfuscate() -> fully obfuscated DEX (class a.b.c, methods a/b)
     *   3. obfuscated DEX -> renameDexClassDeclaration() -> _jugg_fix DEX
     *      (class declaration = a.b.c_jugg_fix, internal refs still point to a.b.c)
     *
     * This ensures _jugg_fix is a bridge class: its declared name has the suffix,
     * but all internal method calls delegate to the original obfuscated class.
     *
     * @return List of generated DEX files
     */
    private fun generateJuggFixClasses(minifyInfo: MinifyInfo, outputDir: File): List<CompileOutput> {
        logger.debug("generateJuggFixClasses: Starting with ${minifyInfo.classFiles.size} class files")
        val outputs = mutableListOf<CompileOutput>()
        val tempDir = File(context.tempCompileDir, "jugg_fix_classes")
        tempDir.deleteRecursively()
        tempDir.mkdirs()

        val strippedClassDir = File(tempDir, "stripped_class_input")
        strippedClassDir.mkdirs()

        // Step 1: Convert original .class files to DEX (no renaming at this stage)
        val classFileList = minifyInfo.classFiles.map { (className, classFile) ->
            logger.debug("generateJuggFixClasses: Processing class $className from ${classFile.absolutePath}")
            logger.debug("generateJuggFixClasses: Class file exists: ${classFile.exists()}")
            val originalBytes = classFile.readBytes()
            val rewrittenBytes = usageReader?.let { reader ->
                rewriteDeletedMethodsAsCompatibilityStubs(className, originalBytes, reader)
            } ?: originalBytes
            if (!rewrittenBytes.contentEquals(originalBytes)) {
                val rewrittenClassFile = File(strippedClassDir, className.replace('.', '/') + ".class")
                rewrittenClassFile.parentFile?.mkdirs()
                rewrittenClassFile.writeBytes(rewrittenBytes)
                rewrittenClassFile
            } else {
                classFile
            }
        }

        if (classFileList.isEmpty()) {
            return emptyList()
        }

        val dexOutputDir = File(tempDir, "dex_output")
        dexOutputDir.mkdirs()

        try {
            val dexFileMaker = com.sickworm.intellij.jugg.compiler.source.DexFileMaker(logger)
            val minApi = context.applicationModule?.minSdkVersion?.toIntOrNull() ?: 21

            dexFileMaker.dex(
                outputDir = dexOutputDir,
                classFilesOrDir = classFileList,
                classpath = emptyList(),
                androidJar = context.androidJar,
                minApi = minApi,
                isFilePerClass = true,
                desugaredLibraryConfiguration = null,
                agpR8Classpath = context.agpR8Classpath,
            )

            // D8 with --file-per-class puts DEX files in subdirectories, need recursive search
            val dexFiles = dexOutputDir.walkTopDown()
                .filter { it.isFile && it.extension == "dex" }
                .toList()
            logger.debug("generateJuggFixClasses: Found ${dexFiles.size} DEX files in ${dexOutputDir.absolutePath}")

            dexFiles.forEach { dexFile ->
                try {
                    val inputBytes = dexFile.readBytes()

                    // Match this DEX file to a class name from minifyInfo.
                    // D8 --file-per-class output naming varies:
                    //   - With directory input: com/tencent/utils/LogUtil.dex (relative path)
                    //   - With single .class file input: LogUtil.dex (simple name only)
                    // Use relative path from dexOutputDir for full-path match, fallback to
                    // simple name match (className ends with dex file's simple name).
                    val dexRelativePath = dexFile.relativeTo(dexOutputDir).path
                        .removeSuffix(".dex").replace(File.separatorChar, '/')
                    val dexSimpleName = dexFile.nameWithoutExtension

                    val className = minifyInfo.classFiles.keys.firstOrNull { className ->
                        val classInternal = className.replace('.', '/')
                        // Full path match: e.g., dexRelativePath="com/tencent/utils/LogUtil"
                        // matches className="com.tencent.utils.LogUtil"
                        classInternal == dexRelativePath
                    } ?: minifyInfo.classFiles.keys.firstOrNull { className ->
                        // Fallback: simple name match for single-file D8 output.
                        // e.g., dexSimpleName="LogUtil" matches className ending with "LogUtil"
                        val classSimpleName = className.substringAfterLast('.')
                        classSimpleName == dexSimpleName
                    }
                    logger.debug("className match: dexFile=${dexFile.name}, " +
                        "dexRelativePath=$dexRelativePath, className=$className")

                    // Step 2: obfuscate() — remap all names to match APK mapping.
                    // This maps class names, method names, field names, and internal
                    // references (e.g., inner class LogUtil$1 -> a.b.d).
                    // The class itself (e.g., LogUtil -> a.b.c) is also fully obfuscated.
                    val obfuscatedBytes = obfuscator.obfuscate(inputBytes)
                    logger.debug("obfuscate result: dexFile=${dexFile.name}, " +
                        "obfuscatedBytes=${if (obfuscatedBytes != null) "${obfuscatedBytes.size} bytes" else "null"}")

                    // Compute the correct output path based on obfuscated + suffix class name.
                    // The output file path must reflect the actual DEX class name so that:
                    //   1. deployed/classes/ stores it at the correct path (e.g., a/b/c_jugg_fix.dex)
                    //   2. The device ClassLoader can find it at runtime
                    //   3. Next incremental build's parseDex sees the correct class name
                    val outputRelativePath: String
                    val finalBytes: ByteArray

                    if (obfuscatedBytes != null && className != null) {
                        // Step 3: renameDexClassDeclaration() — rename only the class
                        // declaration from a.b.c to a.b.c_jugg_fix. Internal method call
                        // owners and field access owners remain as a.b.c (the original
                        // obfuscated class), making _jugg_fix a bridge/proxy.
                        val originalInternal = className.replace('.', '/')
                        val obfuscatedInternal = obfuscator.getObfuscatedClassName(className)
                            ?.replace('.', '/') ?: originalInternal
                        val oldSig = "L$obfuscatedInternal;"
                        val newSig = "L${obfuscatedInternal}${DexObfuscator.SUFFIX};"
                        logger.debug("Renaming class declaration: $oldSig -> $newSig")
                        finalBytes = obfuscator.renameDexClassDeclaration(obfuscatedBytes, oldSig, newSig)
                        // Output path uses the obfuscated class name + suffix
                        outputRelativePath = obfuscatedInternal + DexObfuscator.SUFFIX + ".dex"
                    } else if (obfuscatedBytes != null) {
                        // Obfuscated but couldn't match to a class name — use obfuscated bytes as-is
                        finalBytes = obfuscatedBytes
                        outputRelativePath = dexFile.name
                    } else if (className != null) {
                        // No mapping applied — class has no obfuscation mapping.
                        // Still need to rename class declaration with suffix.
                        val internalName = className.replace('.', '/')
                        val oldSig = "L$internalName;"
                        val newSig = "L${internalName}${DexObfuscator.SUFFIX};"
                        logger.debug("Renaming class declaration (no mapping): $oldSig -> $newSig")
                        finalBytes = obfuscator.renameDexClassDeclaration(inputBytes, oldSig, newSig)
                        // Output path uses the original class name + suffix (no obfuscation)
                        outputRelativePath = internalName + DexObfuscator.SUFFIX + ".dex"
                    } else {
                        finalBytes = inputBytes
                        outputRelativePath = dexFile.name
                    }

                    val outputFile = File(outputDir, outputRelativePath)
                    outputFile.parentFile?.mkdirs()
                    outputFile.writeBytes(finalBytes)
                    outputs.add(CompileOutput(CompileOutput.Type.Dex, outputFile, outputDir))
                    logger.debug("Generated _jugg_fix DEX: ${dexFile.name} -> $outputRelativePath")
                } catch (e: Exception) {
                    logger.warn("Failed to process _jugg_fix DEX: ${dexFile.name}", e)
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to generate DEX for _jugg_fix classes", e)
        }

        return outputs
    }

    private fun initUsageReader() {
        val usageFile = context.usageFile
        val newKey = usageFile?.takeIf { it.exists() }?.let { "${it.absolutePath}_${it.lastModified()}" }
        if (newKey == null) {
            usageReader = null
            usageReaderCacheKey = null
            logger.debug("No usage file found, skip _jugg_fix pruning.")
            return
        }
        if (usageReader != null && usageReaderCacheKey == newKey) {
            return
        }

        try {
            TimeLogger.start("load usage file")
            logger.debug("Loading usage file: ${usageFile.absolutePath}")
            usageReader = R8UsageReader.fromFile(usageFile)
            usageReaderCacheKey = newKey
            TimeLogger.end("load usage file", logger)
        } catch (e: Exception) {
            usageReader = null
            usageReaderCacheKey = null
            logger.warn("Failed to parse usage file: ${usageFile.absolutePath}", e)
        }
    }

    private fun rewriteDeletedMethodsAsCompatibilityStubs(
        className: String,
        classBytes: ByteArray,
        usageReader: R8UsageReader,
    ): ByteArray {
        val removedMethods = usageReader.getRemovedMethods(className)
        if (removedMethods.isEmpty()) {
            return classBytes
        }

        var hasStubbedMethod = false
        val classReader = ClassReader(classBytes)
        val methodNameCounts = mutableMapOf<String, Int>()
        classReader.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor? {
                if (name != "<init>" && name != "<clinit>") {
                    methodNameCounts[name] = methodNameCounts.getOrDefault(name, 0) + 1
                }
                return null
            }
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        val classWriter = ClassWriter(classReader, 0)
        val classVisitor = object : ClassVisitor(Opcodes.ASM9, classWriter) {
            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor? {
                val parameterTypes = Type.getArgumentTypes(descriptor).map { it.toSourceTypeName() }
                val removedMethodsWithSameName = removedMethods.filter { it.name == name }
                val isExactRemovedMethod = usageReader.isMethodRemoved(className, name, parameterTypes)
                // Some R8 versions erase Kotlin accessor parameters in usage.txt; only fall back when no overload is ambiguous.
                val isUniqueNameFallback = removedMethodsWithSameName.size == 1 && methodNameCounts[name] == 1
                val shouldStub = name != "<init>" && name != "<clinit>" &&
                    (isExactRemovedMethod || isUniqueNameFallback)
                if (!shouldStub) {
                    return super.visitMethod(access, name, descriptor, signature, exceptions)
                }

                hasStubbedMethod = true
                logger.debug("Stubbed removed method for _jugg_fix input: $className#$name($parameterTypes)")
                val stubAccess = access and Opcodes.ACC_ABSTRACT.inv() and Opcodes.ACC_NATIVE.inv()
                val stubVisitor = super.visitMethod(stubAccess, name, descriptor, signature, exceptions)
                    ?: return null
                return createCompatibilityStubMethodVisitor(stubVisitor, stubAccess, descriptor)
            }
        }
        classReader.accept(classVisitor, 0)
        return if (hasStubbedMethod) classWriter.toByteArray() else classBytes
    }

    private fun createCompatibilityStubMethodVisitor(
        delegate: MethodVisitor,
        access: Int,
        descriptor: String,
    ): MethodVisitor {
        return object : MethodVisitor(Opcodes.ASM9, delegate) {
            override fun visitCode() = Unit
            override fun visitFrame(type: Int, numLocal: Int, local: Array<out Any>?, numStack: Int, stack: Array<out Any>?) = Unit
            override fun visitInsn(opcode: Int) = Unit
            override fun visitIntInsn(opcode: Int, operand: Int) = Unit
            override fun visitVarInsn(opcode: Int, varIndex: Int) = Unit
            override fun visitTypeInsn(opcode: Int, type: String?) = Unit
            override fun visitFieldInsn(opcode: Int, owner: String?, name: String?, descriptor: String?) = Unit
            override fun visitMethodInsn(opcode: Int, owner: String?, name: String?, descriptor: String?, isInterface: Boolean) = Unit

            @Deprecated("ASM compatibility override")
            @Suppress("DEPRECATION")
            override fun visitMethodInsn(opcode: Int, owner: String?, name: String?, descriptor: String?) = Unit
            override fun visitInvokeDynamicInsn(name: String?, descriptor: String?, bootstrapMethodHandle: Handle?, vararg bootstrapMethodArguments: Any?) = Unit
            override fun visitJumpInsn(opcode: Int, label: Label?) = Unit
            override fun visitLabel(label: Label?) = Unit
            override fun visitLdcInsn(value: Any?) = Unit
            override fun visitIincInsn(varIndex: Int, increment: Int) = Unit
            override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label?, vararg labels: Label?) = Unit
            override fun visitLookupSwitchInsn(dflt: Label?, keys: IntArray?, labels: Array<out Label>?) = Unit
            override fun visitMultiANewArrayInsn(descriptor: String?, numDimensions: Int) = Unit
            override fun visitInsnAnnotation(typeRef: Int, typePath: TypePath?, descriptor: String?, visible: Boolean): AnnotationVisitor? = null
            override fun visitTryCatchBlock(start: Label?, end: Label?, handler: Label?, type: String?) = Unit
            override fun visitTryCatchAnnotation(typeRef: Int, typePath: TypePath?, descriptor: String?, visible: Boolean): AnnotationVisitor? = null
            override fun visitLocalVariable(name: String?, descriptor: String?, signature: String?, start: Label?, end: Label?, index: Int) = Unit
            override fun visitLocalVariableAnnotation(
                typeRef: Int,
                typePath: TypePath?,
                start: Array<out Label>?,
                end: Array<out Label>?,
                index: IntArray?,
                descriptor: String?,
                visible: Boolean,
            ): AnnotationVisitor? = null
            override fun visitLineNumber(line: Int, start: Label?) = Unit
            override fun visitMaxs(maxStack: Int, maxLocals: Int) = Unit

            override fun visitEnd() {
                emitCompatibilityStubBody(delegate, access, descriptor)
                super.visitEnd()
            }
        }
    }

    private fun emitCompatibilityStubBody(methodVisitor: MethodVisitor, access: Int, descriptor: String) {
        methodVisitor.visitCode()
        val returnType = Type.getReturnType(descriptor)
        when (returnType.sort) {
            Type.VOID -> methodVisitor.visitInsn(Opcodes.RETURN)
            Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> {
                methodVisitor.visitInsn(Opcodes.ICONST_0)
                methodVisitor.visitInsn(Opcodes.IRETURN)
            }
            Type.FLOAT -> {
                methodVisitor.visitInsn(Opcodes.FCONST_0)
                methodVisitor.visitInsn(Opcodes.FRETURN)
            }
            Type.LONG -> {
                methodVisitor.visitInsn(Opcodes.LCONST_0)
                methodVisitor.visitInsn(Opcodes.LRETURN)
            }
            Type.DOUBLE -> {
                methodVisitor.visitInsn(Opcodes.DCONST_0)
                methodVisitor.visitInsn(Opcodes.DRETURN)
            }
            else -> {
                methodVisitor.visitInsn(Opcodes.ACONST_NULL)
                methodVisitor.visitInsn(Opcodes.ARETURN)
            }
        }
        methodVisitor.visitMaxs(getCompatibilityStubMaxStack(returnType), getCompatibilityStubLocalCount(access, descriptor))
    }

    private fun getCompatibilityStubMaxStack(returnType: Type): Int {
        return when (returnType.sort) {
            Type.VOID -> 0
            Type.LONG, Type.DOUBLE -> 2
            else -> 1
        }
    }

    private fun getCompatibilityStubLocalCount(access: Int, descriptor: String): Int {
        val instanceSlot = if ((access and Opcodes.ACC_STATIC) == 0) 1 else 0
        return instanceSlot + Type.getArgumentTypes(descriptor).sumOf { it.size }
    }

    private fun Type.toSourceTypeName(): String {
        return when (sort) {
            Type.BOOLEAN -> "boolean"
            Type.BYTE -> "byte"
            Type.CHAR -> "char"
            Type.DOUBLE -> "double"
            Type.FLOAT -> "float"
            Type.INT -> "int"
            Type.LONG -> "long"
            Type.SHORT -> "short"
            Type.VOID -> "void"
            Type.ARRAY -> elementType.toSourceTypeName() + "[]".repeat(dimensions)
            Type.OBJECT -> className
            else -> descriptor
        }
    }
}
