package com.sickworm.intellij.jugg.compiler.obfuscation

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

/**
 * Compiler that re-obfuscates class files based on mapping.txt.
 *
 * This compiler reads the mapping file from the deployed APK and applies
 * the obfuscation mapping to incremental class files, ensuring consistency
 * with the original obfuscated APK.
 */
class ClassMinifyCompiler(
    context: ICompileContext,
    parent: Disposable,
): BaseCompiler(context, parent) {

    override val supportedTypes = listOf(CompileFile.Type.Class)

    override val beforeCompileOrderRange: IntRange = CompileOrder.beforeMinify
    override val afterCompileOrderRange: IntRange = CompileOrder.afterMinify

    private var obfuscator: ClassObfuscator? = null
    private var mappingFile: File? = null

    override fun compile(task: CompileTask): CompileResult {
        if (!task.isNeedCompile) {
            return CompileResult.empty(task)
        }

        // Try to find mapping file from incremental data directory
        val mapping = findMappingFile()
        if (mapping == null) {
            logger.debug("No mapping file found, skip obfuscation")
            return CompileResult.empty(task)
        }

        // Initialize obfuscator if mapping file changed
        if (mappingFile != mapping || obfuscator == null) {
            logger.info("Loading mapping file: ${mapping.absolutePath}")
            obfuscator = ClassObfuscator.fromMappingFile(mapping)
            mappingFile = mapping
            val stats = obfuscator!!.getMappingStats()
            logger.debug("Mapping loaded: ${stats.classCount} classes, ${stats.fieldCount} fields, ${stats.methodCount} methods")
        }

        return super.compile(task)
    }

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        val obfuscator = this.obfuscator
        if (obfuscator == null) {
            logger.warn("Obfuscator not initialized")
            return CompileResult.empty(task)
        }

        if (task.files.isEmpty()) {
            return CompileResult(task, emptyList(), emptyList())
        }

        val details = mutableListOf<Result<CompileFile, CompileError>>()
        val outputs = mutableListOf<CompileOutput>()

        for (compileFile in task.files) {
            if (task.isShouldCancel) {
                return task.toCancelResult()
            }

            try {
                val result = obfuscateClassFile(compileFile, task.outputDir, obfuscator)
                if (result != null) {
                    details.add(Result.success(compileFile))
                    outputs.add(result)
                } else {
                    // No mapping for this class, output as-is
                    val output = copyClassFile(compileFile, task.outputDir)
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

        return CompileResult(task, details, outputs)
    }

    /**
     * Obfuscate a class file and write to output directory.
     *
     * @return CompileOutput if obfuscation was applied, null otherwise
     */
    private fun obfuscateClassFile(
        compileFile: CompileFile,
        outputDir: File,
        obfuscator: ClassObfuscator
    ): CompileOutput? {
        val inputFile = compileFile.file
        val baseDir = compileFile.baseDir

        // Get the obfuscated output path
        val obfuscatedPath = obfuscator.getObfuscatedClassPath(inputFile, baseDir)
        val outputFile = File(outputDir, obfuscatedPath)

        // Read and obfuscate
        val inputBytes = inputFile.readBytes()
        val outputBytes = obfuscator.obfuscate(inputBytes)

        if (outputBytes != null) {
            outputFile.parentFile?.mkdirs()
            outputFile.writeBytes(outputBytes)
            logger.debug("Obfuscated: ${inputFile.name} -> ${outputFile.name}")
            return CompileOutput(CompileOutput.Type.Class, outputFile, outputDir)
        }

        return null
    }

    /**
     * Copy class file to output directory without obfuscation.
     */
    private fun copyClassFile(compileFile: CompileFile, outputDir: File): CompileOutput {
        val inputFile = compileFile.file
        val relativePath = inputFile.relativeTo(compileFile.baseDir).path
        val outputFile = File(outputDir, relativePath)

        outputFile.parentFile?.mkdirs()
        inputFile.copyTo(outputFile, overwrite = true)
        logger.debug("Copied (no mapping): ${inputFile.name}")

        return CompileOutput(CompileOutput.Type.Class, outputFile, outputDir)
    }

    /**
     * Find the mapping.txt file from various possible locations.
     */
    private fun findMappingFile(): File? {
        // Priority 1: Check incremental data directory
        val incrementalMapping = File(context.incrementalDataDir, "mapping.txt")
        if (incrementalMapping.exists()) {
            return incrementalMapping
        }

        // Priority 2: Check each APK's extracted data
        for (apkInfo in context.apkInfos) {
            for (apkFileUnit in apkInfo.files) {
                val apkMappingDir = File(context.incrementalDataDir, apkFileUnit.apkFile.nameWithoutExtension)
                val apkMapping = File(apkMappingDir, "mapping.txt")
                if (apkMapping.exists()) {
                    return apkMapping
                }
            }
        }

        // Priority 3: Check temp compile directory
        val tempMapping = File(context.tempCompileDir, "mapping.txt")
        if (tempMapping.exists()) {
            return tempMapping
        }

        return null
    }

    override fun dispose() {
        obfuscator = null
        mappingFile = null
    }
}
