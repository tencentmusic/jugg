package com.sickworm.intellij.jugg.compiler.obfuscation

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassReader
import com.sickworm.intellij.jugg.org.objectweb.asm.ClassWriter
import com.sickworm.intellij.jugg.org.objectweb.asm.commons.ClassRemapper
import com.sickworm.intellij.jugg.org.objectweb.asm.commons.Remapper
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
        val minifyInfo = context.getMinifyInfo()

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
     * Phase 2: Generate _jugg_fix copies for inline-affected classes
     * 1. Rename class name (add _jugg_fix suffix)
     * 2. Convert to DEX
     * 3. Apply obfuscation mapping
     *
     * @return List of generated DEX files
     */
    private fun generateJuggFixClasses(minifyInfo: MinifyInfo, outputDir: File): List<CompileOutput> {
        logger.debug("generateJuggFixClasses: Starting with ${minifyInfo.classFiles.size} class files")
        val outputs = mutableListOf<CompileOutput>()
        val tempDir = File(context.tempCompileDir, "jugg_fix_classes")
        tempDir.deleteRecursively()
        tempDir.mkdirs()

        // 1. Rename classes using ASM and generate new .class files
        val renamedClassFiles = mutableListOf<File>()
        minifyInfo.classFiles.forEach { (className, classFile) ->
            logger.debug("generateJuggFixClasses: Processing class $className from ${classFile.absolutePath}")
            logger.debug("generateJuggFixClasses: Class file exists: ${classFile.exists()}")
            try {
                val renamedFile = renameClassWithSuffix(classFile, className, tempDir)
                renamedClassFiles.add(renamedFile)
                logger.info("Renamed class: $className -> ${renamedFile.name}")
            } catch (e: Exception) {
                logger.warn("Failed to rename class $className", e)
            }
        }

        if (renamedClassFiles.isEmpty()) {
            return emptyList()
        }

        // 2. Convert renamed .class files to DEX
        val dexOutputDir = File(tempDir, "dex_output")
        dexOutputDir.mkdirs()

        try {
            val dexFileMaker = com.sickworm.intellij.jugg.compiler.source.DexFileMaker(logger)
            val minApi = context.applicationModule?.minSdkVersion?.toIntOrNull() ?: 21

            dexFileMaker.dex(
                outputDir = dexOutputDir,
                classFilesOrDir = renamedClassFiles,
                classpath = emptyList(),
                androidJar = context.androidJar,
                minApi = minApi,
                isFilePerClass = true,
                desugaredLibraryConfiguration = null
            )

            // 3. Apply obfuscation mapping to generated DEX files (if needed)
            // D8 with --file-per-class puts DEX files in subdirectories, need recursive search
            val dexFiles = dexOutputDir.walkTopDown()
                .filter { it.isFile && it.extension == "dex" }
                .toList()
            logger.debug("generateJuggFixClasses: Found ${dexFiles.size} DEX files in ${dexOutputDir.absolutePath}")
            dexFiles.forEach { dexFile ->
                try {
                    // _jugg_fix classes are not in original mapping, so copy DEX files directly
                    val outputFile = File(outputDir, dexFile.name)
                    dexFile.copyTo(outputFile, overwrite = true)
                    outputs.add(CompileOutput(CompileOutput.Type.Dex, outputFile, outputDir))
                    logger.debug("Generated _jugg_fix DEX: ${dexFile.name}")
                } catch (e: Exception) {
                    logger.warn("Failed to copy _jugg_fix DEX: ${dexFile.name}", e)
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to generate DEX for _jugg_fix classes", e)
        }

        return outputs
    }

    /**
     * Rename a class file by adding _jugg_fix suffix using ASM.
     */
    private fun renameClassWithSuffix(classFile: File, className: String, outputDir: File): File {
        val classReader = ClassReader(classFile.readBytes())
        val classWriter = ClassWriter(0)

        val internalName = className.replace('.', '/')
        val newInternalName = internalName + com.sickworm.intellij.jugg.deploy.data.EffectedClassNode.SUFFIX

        val remapper = object : Remapper() {
            override fun map(internalName: String): String {
                return if (internalName == className.replace('.', '/')) {
                    newInternalName
                } else {
                    internalName
                }
            }
        }

        val remappingAdapter = ClassRemapper(classWriter, remapper)
        classReader.accept(remappingAdapter, 0)

        val outputFile = File(outputDir, newInternalName + ".class")
        outputFile.parentFile?.mkdirs()
        outputFile.writeBytes(classWriter.toByteArray())

        return outputFile
    }

}
