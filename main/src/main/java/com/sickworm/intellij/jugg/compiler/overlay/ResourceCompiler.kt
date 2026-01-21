package com.sickworm.intellij.jugg.compiler.overlay

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.aapt2.Aapt2DaemonInvoker
import com.sickworm.intellij.jugg.compiler.Result
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.databinding.DataBindingArgsManager
import com.sickworm.intellij.jugg.compiler.databinding.DataBindingGenBaseClassesCompiler
import com.sickworm.intellij.jugg.compiler.databinding.DataBindingGenMapperCompiler
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File
import java.security.MessageDigest

/**
 * Compile res files to .flat files
 *
 * e.g.
 * input:
 * activity_main.xml
 *
 * output:
 * activity_main.xml.flat
 */
class ResourceCompiler(
    context: ICompileContext,
    parent: Disposable,
): BaseCompiler(context, parent) {

    override val supportedTypes = listOf(CompileFile.Type.Resource)

    private val aapt2Invoker = Aapt2DaemonInvoker(logger)
    private val aabResGuardHandler = AabResGuardHandler(logger)

    private val dataBindingGenBaseClassesCompiler = DataBindingGenBaseClassesCompiler(context.subContext("databinding"), this)
    private val dataBindingGenMapperCompiler = DataBindingGenMapperCompiler(context.subContext("databinding"), this)

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        val moduleName = module.name
        val subDir = if (moduleName.isEmpty()) "" else "$moduleName/"
        val outputDir = task.outputDir.resolve(subDir)
        outputDir.mkdirs()

        val singleResCompileSet = ResCompileSet(
            task,
            task.files.filter { it.file.isFile }.associateWith { listOf(it.file) },
            outputDir.resolve("single_files"),
        )
        // if compile file is a directory, compile all files in the directory
        val dirToFilesMap: Map<File, List<File>> = DirToFileMapHelper.createDirToResFileMap(task.files, logger)
        val dirResCompileSet = dirToFilesMap.map { (taskFile, files) ->
            val compileFile = task.files.find { it.file == taskFile }!!
            val outputDirName = taskFile.path.md5
            val outputSubDir = outputDir.resolve(outputDirName)
            logger.debug("res dir ${taskFile.path}, output $outputSubDir")
            ResCompileSet(
                task,
                mapOf(compileFile to files),
                outputSubDir,
            )
        }
        val compileFilesSet: List<ResCompileSet> = dirResCompileSet + listOf(singleResCompileSet)
        val compileResultSet = compileFilesSet.map {
            compileResSet(it, module)
        }
        val compileResult = compileResultSet.reduce { acc, compileResult -> acc + compileResult }.copy(task = task)
        return compileResult
    }

    private fun compileResSet(resCompileSet: ResCompileSet, module: ModuleInfo): CompileResult {
        val dataBindingResult = processDataBinding(resCompileSet, module)
        if (!dataBindingResult.isAllSuccess) {
            return dataBindingResult
        }
        val splitLayoutFiles = dataBindingResult.outputs.filter { it.type == CompileOutput.Type.ResXml }
        val javaFiles = dataBindingResult.outputs.filter { it.type == CompileOutput.Type.Java }
        logger.debug("splitLayoutFiles output: ${splitLayoutFiles.map { it.relativeFile }}, " +
                "javaFiles output: ${javaFiles.map { it.relativeFile }}")

        val flatResult = if (splitLayoutFiles.isNotEmpty()) {
            // replace xml file which split by data binding
            val processedResCompileSet = updateResCompileSet(resCompileSet, splitLayoutFiles)
            aapt2Compile(processedResCompileSet)
        } else {
            aapt2Compile(resCompileSet)
        }

        return flatResult.copy(outputs = flatResult.outputs + javaFiles)
    }

    private fun processDataBinding(resCompileSet: ResCompileSet, module: ModuleInfo): CompileResult {
        val layoutFiles = resCompileSet.compileFileMap.flatMap { (compileFile, xmlFiles) ->
            val baseDir = if (compileFile.file.isDirectory) compileFile.file else compileFile.baseDir
            xmlFiles.filter {
                it.parentFile.name.startsWith("layout")
            }.map {
                CompileFile(CompileFile.Type.Resource, it, baseDir, module)
            }
        }
        val databindingTask = CompileTask(
            layoutFiles,
            resCompileSet.outputDir.resolve("databinding"),
            resCompileSet.originTask,
        )
        databindingTask.outputDir.clearDir()
        if (databindingTask.files.isEmpty()) {
            logger.debug("no layout file found, skip data binding processing")
            return CompileResult(resCompileSet.originTask, emptyList(), emptyList())
        }

        // process data binding if needed
        if (DataBindingArgsManager.isUseViewBinding(module)) {
            logger.info("Processing view binding...")
        }
        TimeLogger.start("view binding")
        val viewBindingResult = dataBindingGenBaseClassesCompiler.compile(databindingTask)
        TimeLogger.end("view binding", logger)
        if (!viewBindingResult.isAllSuccess) {
            return resCompileSet.originTask.allFailed("process view binding failed")
        }

        val isRunDataBinding = DataBindingArgsManager.isUseDataBinding(module, layoutFiles.map { it.file })
        if (!isRunDataBinding) {
            return viewBindingResult
        }

        logger.info("Processing data binding...")
        TimeLogger.start("data_binding")
        val dataBindingResult = dataBindingGenMapperCompiler.compile(databindingTask)
        TimeLogger.end("data_binding", logger)
        if (!dataBindingResult.isAllSuccess) {
            return resCompileSet.originTask.allFailed("process data binding failed")
        }
        val isDataBindingWorking = dataBindingResult.outputs.isNotEmpty()
        return if (isDataBindingWorking) {
            dataBindingResult
        } else {
            viewBindingResult
        }
    }

    private fun updateResCompileSet(resCompileSet: ResCompileSet, splitLayoutFiles: List<CompileOutput>): ResCompileSet {
        // replace xml file which split by data binding
        val replaceFile: (CompileFile, File) -> File = replaceFile@{ compileFile, xmlFile ->
            val baseDir = if (compileFile.file.isDirectory) compileFile.file else compileFile.baseDir
            val splitXmlFile = splitLayoutFiles.find { it.relativeFile == xmlFile.relativeTo(baseDir) }
            if (splitXmlFile != null) {
                logger.debug ("found and replace origin layout file $xmlFile")
                return@replaceFile splitXmlFile.file
            }
            return@replaceFile xmlFile
        }
        val processedResCompileSet = resCompileSet.copy(
            compileFileMap = resCompileSet.compileFileMap.mapValues { (compileFile, files) ->
                files.map { replaceFile(compileFile, it) }
            })
        return processedResCompileSet
    }

    private fun aapt2Compile(originResCompileSet: ResCompileSet): CompileResult {
        if (originResCompileSet.compileFiles.isEmpty()) {
            return CompileResult(originResCompileSet.originTask, originResCompileSet.taskFiles.map { Result.success(it) }, emptyList())
        }

        // Process AabResGuard obfuscation if mapping file exists
        val resCompileSet = aabResGuardHandler.process(originResCompileSet) ?: run {
            // Processing failed, return error
            return originResCompileSet.originTask.allFailed("AabResGuard processing failed")
        }

        val filesString = resCompileSet.compileFiles.joinToString(" ") {
            it.absolutePath
        }
        resCompileSet.outputDir.mkdirs()

        // --legacy is required for: multiple substitutions specified in non-positional format; did you mean to add the formatted="false" attribute?.
        val command = "compile --legacy -o ${resCompileSet.outputDir} $filesString"
        val result = aapt2Invoker.invoke(command)
        if (!result.isSuccess) {
            return CompileResult(
                resCompileSet.originTask,
                resCompileSet.taskFiles.map {
                    Result.failure(CompileError(it, listOf(0L to "aapt2 compile failed")))
                },
                emptyList()
            )
        }

        val details = resCompileSet.taskFiles.map { compileFile ->

            fun toResult(file: File): Result<CompileFile, CompileError> {
                val fileName = file.flatFileName
                val outputFile = File(resCompileSet.outputDir, fileName)
                return if (outputFile.exists() && outputFile.length() > 0) {
                    Result.success(compileFile)
                } else {
                    // file not valid, which means compile failed
                    logger.debug("${file.path} compile to flat failed, except name: $fileName")
                    Result.failure(CompileError(compileFile, listOf(0L to "res file compile to flat failed")))
                }
            }

            val relativeCompileFiles = resCompileSet.compileFileMap[compileFile]!!
            if (relativeCompileFiles.isEmpty()) {
                return@map Result.success(compileFile)
            } else if (relativeCompileFiles.size == 1) {
                return@map toResult(relativeCompileFiles.first())
            } else {
                val details = relativeCompileFiles.map {
                    toResult(it)
                }
                val isSuccess = details.all { it.isSuccess }
                if (isSuccess) {
                    return@map Result.success(compileFile)
                } else {
                    val failedFiles = details.filter { !it.isSuccess }.map { it.file.relativeFile.path }
                    return@map Result.failure(CompileError(compileFile, listOf(0L to "res dir compile to flat failed, failed files: $failedFiles")))
                }
            }
        }

        val outputs = resCompileSet.compileFiles.map {
            val fileName = it.flatFileName
            val outputFile = File(resCompileSet.outputDir, fileName)
            return@map CompileOutput(CompileOutput.Type.Flat, outputFile, resCompileSet.outputDir)
        }

        return CompileResult(resCompileSet.originTask, details, outputs)
    }

    private val File.flatFileName: String get() {
        val file = this
        val folderName = file.parentFile!!.name
        val extension = if (folderName.startsWith("values")) ".arsc"
            else if (file.extension.isEmpty()) ""
            else ".${file.extension}"
        return "${folderName}_${file.nameWithoutExtension}$extension.flat"
    }

    override fun dispose() {
        aapt2Invoker.release()
    }

    data class ResCompileSet(
        val originTask: CompileTask,
        val compileFileMap: Map<CompileFile, List<File>>,
        val outputDir: File,
    ) {

        val taskFiles: List<CompileFile> get() = compileFileMap.keys.toList()
        val compileFiles: List<File> get() = compileFileMap.values.flatten()
    }

    private val String.md5: String get() = MessageDigest.getInstance("MD5").digest(this.toByteArray()).toHex()
    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}