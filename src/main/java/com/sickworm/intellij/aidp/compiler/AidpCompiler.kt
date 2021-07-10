package com.sickworm.intellij.aidp.compiler

import com.intellij.openapi.project.Project
import com.sickworm.intellij.aidp.*
import java.io.File

class AidpCompiler(project: Project,
                   /** compile temporary directory */
                   private val sourceCompileDir: File,
                   /** class path directory */
                   private val classPathDir: File
                   ): ICompiler {

    override val supportedTypes: List<CompileFile.Type> = listOf(
        CompileFile.Type.Java,
        CompileFile.Type.Kotlin,
        CompileFile.Type.Overlay
    )

    private val logger = AidpLogger.getInstance(project, "#AIDP-Compiler")

    private val javaCompiler = JavaCompiler(logger)

    private val kotlinCompiler = KotlinCompiler()

    private val overlayCompiler = OverlayCompiler(logger)

    private val dexCompiler = DexCompiler(logger)

    override fun compile(task: CompileTask): CompileResult {
        checkCanCompile(task)

        // split compile files by type
        val fileSet = mutableMapOf<CompileFile.Type, MutableList<CompileFile>>()
        task.files.forEach {
            var set = fileSet[it.type]
            if (set == null) {
                set = mutableListOf()
                fileSet[it.type] = set
            }
            set.add(it)
        }
        if (fileSet.isEmpty()) {
            logger.info("nothing to compile, exit")
            return CompileResult(task, emptyList(), emptyList())
        }

        val overlayOutputDir = File(task.outputDir, "overlays")
        val dexOutputDir = File(task.outputDir, "classes")

        // compile
        sourceCompileDir.clearDir()
        val startTime = System.currentTimeMillis()
        val resultList: List<CompileResult> = fileSet.map { (type, files) ->
            return@map when (type) {
                CompileFile.Type.Java -> {
                    logger.info("compile java files $files")
                    val classOutputDir = File(sourceCompileDir, "java")
                    val taskCompileToTempPath = task.copy(outputDir = classOutputDir)
                    javaCompiler.compile(taskCompileToTempPath)
                }
                CompileFile.Type.Kotlin -> {
                    logger.info("compile kotlin files $files")
                    val classOutputDir = File(sourceCompileDir, "kotlin")
                    val taskCompileToTempPath = task.copy(outputDir = classOutputDir)
                    kotlinCompiler.compile(taskCompileToTempPath)
                }
                CompileFile.Type.Overlay -> {
                    logger.info("compile overlay files $files")
                    val taskCompileToTempPath = task.copy(outputDir = overlayOutputDir)
                    overlayCompiler.compile(taskCompileToTempPath)
                }
                else -> {
                    // already handled in checkCanCompile()
                    throw AidpInternalException("aidp compiler don't support class compile")
                }
            }
        }
        val compileResult = resultList.reduce { acc, i -> acc + i }
        if (!checkResult(compileResult)) {
            // TODO handle successfully compiled files
            return compileResult.copy(outputs = emptyList())
        }

        // dex .class
        val classFiles = compileResult.outputs.filter {
            it.type == CompileOutput.Type.Class
        }
        val compileClassFiles = classFiles.map {
            CompileFile(it.file, CompileFile.Type.Class, it.baseDir, emptyList())
        }
        val dexTask = CompileTask(compileClassFiles, dexOutputDir)
        val dexResult = dexCompiler.compile(dexTask)
        if (!checkResult(dexResult)) {
            // TODO handle successfully compiled files
            return compileResult.copy(outputs = emptyList())
        }

        // move compiled files to class path for future compile dependencies
        val isMoveToClassPathSuccess = classFiles.map {
            val classPathFile = it.file.changeBaseDir(it.baseDir, classPathDir)
            classPathFile.parentFile?.mkdirs()
            classPathFile.delete()
            return@map it.file.renameTo(classPathFile)
        }.all { true }
        if (!isMoveToClassPathSuccess) {
            logger.warn("move class file to class path failed!")
            // we don't know .class file is from which source file, so all error
            return CompileResult(task, compileResult.details.map { result ->
                Result.failure(CompileError(result.file, emptyList()))
            }, emptyList())
        }

        val finalResult = compileResult.copy(
            outputs = compileResult.outputs - classFiles + dexResult.outputs
        )
        val costTime = System.currentTimeMillis() - startTime
        logger.info("compile finished, cost ${costTime}ms")
        logger.info("compile result, success: ${finalResult.successFiles.size}, failure: ${finalResult.failedFiles.size}")

        return finalResult
    }

    private fun checkResult(result: CompileResult): Boolean {
        if (!result.isAllSuccess) {
            logger.info("compile result, success: ${result.successFiles.size}, failure: ${result.failedFiles.size}")
            val errorMessage = "compile failed! please check out the log"
            logger.warn(errorMessage)
        }
        return result.isAllSuccess
    }
}