package com.sickworm.intellij.jugg.compiler.obfuscation

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.project.info.ModuleInfo
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

    private lateinit var obfuscator: ClassObfuscator

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
            obfuscator = ClassObfuscator.fromMappingFile(mappingFile)
            val stats = obfuscator.getMappingStats()
            logger.debug("Mapping loaded: ${stats.classCount} classes, ${stats.fieldCount} fields, ${stats.methodCount} methods")
            TimeLogger.end("load mapping file", logger)
        }

        return null
    }

    private fun process(task: CompileTask): CompileResult {
        val details = mutableListOf<Result<CompileFile, CompileError>>()
        val outputs = mutableListOf<CompileOutput>()

        val detailLog = StringBuilder("Obfuscated: ")
        for (compileFile in task.files) {
            try {
                val result = obfuscateClassFile(compileFile, task.outputDir, obfuscator)
                if (result != null) {
                    details.add(Result.success(compileFile))
                    outputs.add(result)
                    detailLog.append("${compileFile.file.name} -> ${result.file.name}\"")
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
        logger.debug(detailLog.toString())

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

}
