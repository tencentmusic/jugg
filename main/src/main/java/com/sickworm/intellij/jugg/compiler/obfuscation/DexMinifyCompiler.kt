package com.sickworm.intellij.jugg.compiler.obfuscation

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.logger.TimeLogger
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

        // Step 1: Convert original .class files to DEX (no renaming at this stage)
        val classFileList = minifyInfo.classFiles.map { (className, classFile) ->
            logger.debug("generateJuggFixClasses: Processing class $className from ${classFile.absolutePath}")
            logger.debug("generateJuggFixClasses: Class file exists: ${classFile.exists()}")
            classFile
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
                desugaredLibraryConfiguration = null
            )

            // D8 with --file-per-class puts DEX files in subdirectories, need recursive search
            val dexFiles = dexOutputDir.walkTopDown()
                .filter { it.isFile && it.extension == "dex" }
                .toList()
            logger.debug("generateJuggFixClasses: Found ${dexFiles.size} DEX files in ${dexOutputDir.absolutePath}")

            dexFiles.forEach { dexFile ->
                try {
                    val inputBytes = dexFile.readBytes()

                    // Step 2: obfuscate() — remap all names to match APK mapping.
                    // This maps class names, method names, field names, and internal
                    // references (e.g., inner class LogUtil$1 -> a.b.d).
                    // The class itself (e.g., LogUtil -> a.b.c) is also fully obfuscated.
                    val obfuscatedBytes = obfuscator.obfuscate(inputBytes)

                    if (obfuscatedBytes != null) {
                        // Step 3: renameDexClassDeclaration() — rename only the class
                        // declaration from a.b.c to a.b.c_jugg_fix. Internal method call
                        // owners and field access owners remain as a.b.c (the original
                        // obfuscated class), making _jugg_fix a bridge/proxy.
                        val className = minifyInfo.classFiles.keys.firstOrNull { className ->
                            // Match this DEX file to a class name from minifyInfo
                            dexFile.nameWithoutExtension.replace('.', '/').contains(
                                className.replace('.', '/')
                            )
                        }

                        val finalBytes = if (className != null) {
                            val originalInternal = className.replace('.', '/')
                            val obfuscatedInternal = obfuscator.getObfuscatedClassName(className)
                                ?.replace('.', '/') ?: originalInternal
                            val oldSig = "L$obfuscatedInternal;"
                            val newSig = "L${obfuscatedInternal}${DexObfuscator.SUFFIX};"
                            logger.debug("Renaming class declaration: $oldSig -> $newSig")
                            obfuscator.renameDexClassDeclaration(obfuscatedBytes, oldSig, newSig)
                        } else {
                            obfuscatedBytes
                        }

                        val outputFile = File(outputDir, dexFile.name)
                        outputFile.parentFile?.mkdirs()
                        outputFile.writeBytes(finalBytes)
                        logger.debug("Generated _jugg_fix DEX (obfuscate+rename): ${dexFile.name}")
                    } else {
                        // No mapping applied — class has no obfuscation mapping.
                        // Still need to rename class declaration with suffix.
                        val className = minifyInfo.classFiles.keys.firstOrNull { className ->
                            dexFile.nameWithoutExtension.replace('.', '/').contains(
                                className.replace('.', '/')
                            )
                        }

                        val finalBytes = if (className != null) {
                            val internalName = className.replace('.', '/')
                            val oldSig = "L$internalName;"
                            val newSig = "L${internalName}${DexObfuscator.SUFFIX};"
                            logger.debug("Renaming class declaration (no mapping): $oldSig -> $newSig")
                            obfuscator.renameDexClassDeclaration(inputBytes, oldSig, newSig)
                        } else {
                            inputBytes
                        }

                        val outputFile = File(outputDir, dexFile.name)
                        outputFile.parentFile?.mkdirs()
                        outputFile.writeBytes(finalBytes)
                        logger.debug("Generated _jugg_fix DEX (rename only): ${dexFile.name}")
                    }

                    val outputFile = File(outputDir, dexFile.name)
                    outputs.add(CompileOutput(CompileOutput.Type.Dex, outputFile, outputDir))
                } catch (e: Exception) {
                    logger.warn("Failed to process _jugg_fix DEX: ${dexFile.name}", e)
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to generate DEX for _jugg_fix classes", e)
        }

        return outputs
    }

}
