package com.sickworm.intellij.aidp.compiler

import com.intellij.openapi.project.Project
import com.sickworm.intellij.aidp.*
import java.io.File

class AidpCompiler(project: Project,
                   /** compile temporary directory */
                   tempCompileDir: File,
                   /** class path directory */
                   classPathDir: File
                   ): ICompiler {

    override val supportedTypes: List<CompileFile.Type> = listOf(
        CompileFile.Type.Java,
        CompileFile.Type.Kotlin,
        CompileFile.Type.Overlay
    )

    private val logger = AidpLogger.getInstance(project, "#AIDP-Compiler")

    private val sourceCompiler = SourceCompiler(tempCompileDir, classPathDir, logger)

    private val overlayCompiler = OverlayCompiler(logger)

    override fun compile(task: CompileTask): CompileResult {
        checkCanCompile(task)

        val startTime = System.currentTimeMillis()

        // compile
        var compileResult = CompileResult(task, emptyList(), emptyList())

        // compile source
        val classesOutputDir = File(task.outputDir, "classes")
        val sourceCompileTask = CompileTask(
            files = task.files.filter {
                it.type == CompileFile.Type.Java || it.type == CompileFile.Type.Kotlin
            },
            outputDir = classesOutputDir
        )
        if (sourceCompileTask.isNeedCompile) {
            compileResult += sourceCompiler.compile(sourceCompileTask)
        }

        // compile overlay
        val overlayOutputDir = File(task.outputDir, "overlays/assets")
        val overlayCompileTask = task.copy(
            files = task.files.filter {
                it.type == CompileFile.Type.Overlay
            },
            outputDir = overlayOutputDir
        )
        if (overlayCompileTask.isNeedCompile) {
            compileResult += overlayCompiler.compile(overlayCompileTask)
        }

        val costTime = System.currentTimeMillis() - startTime
        logger.info("compile finished, cost ${costTime}ms")
        logger.info("compile result, success: ${compileResult.successFiles.size}, failure: ${compileResult.failedFiles.size}")

        return compileResult
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