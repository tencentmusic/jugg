package com.sickworm.intellij.aidp

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.zip.ZipFile
import javax.tools.DiagnosticListener
import javax.tools.JavaFileObject
import javax.tools.StandardJavaFileManager
import javax.tools.ToolProvider
import javax.tools.JavaCompiler as JavaCompilerX


data class CompileTask(
    val files: List<CompileFile>,
    val outputDir: File
) {

    operator fun plus(task: CompileTask): CompileTask {
        if (outputDir != task.outputDir) {
            throw AidpInternalException.outputDirNotEmpty()
        }
        return CompileTask(files + task.files, outputDir)
    }

    companion object
}

data class CompileFile(
    val file: File,
    val type: Type,
    val baseDir: File,
    val dependencyPaths: List<String> = emptyList()
) {

    override fun toString(): String {
        return "$type:${file.name}"
    }

    companion object {
        fun getTypeByExtension(fileName: String): Type {
            return when {
                fileName.endsWith(".java") -> Type.Java
                fileName.endsWith(".kt") -> Type.Kotlin
                else -> Type.Overlay
            }
        }
    }

    enum class Type {
        Java,
        Kotlin,
        Class,
        Overlay,
        Res,
        FlatDir;
    }
}

data class CompileOutput(
    val file: File,
    val baseDir: File,
    val type: Type,
) {

    enum class Type {
        Class,
        Dex,
        Flat,
        Overlay;
    }
}

data class CompileResult(
    val task: CompileTask,
    val details: List<Result<CompileFile, CompileError>>,
    val outputs: List<CompileOutput>
) {
    val successFiles get() = details.filter { it.isSuccess }

    val failedFiles get() = details.filter { it.isFailed }

    val isAllSuccess get() = details.all { it.isSuccess }

    operator fun plus(result: CompileResult): CompileResult {
        return CompileResult(
            task + result.task,
            details + result.details,
            outputs + result.outputs
        )
    }
}

data class CompileError(
    /** file to be compiled */
    val file: CompileFile,
    /** will be empty if [file] looks good but compiler still stopped because there is another error file */
    val errors: List<Pair<Long, String>> // <Line, Message>
) {
    val errorMessages get() = errors.joinToString("\n") { it.second }
}

interface ICompiler {
    val supportedTypes: List<CompileFile.Type>

    fun compile(task: CompileTask): CompileResult

    fun checkCanCompile(task: CompileTask) {
        val invalidFiles = task.files.filter { !supportedTypes.contains(it.type) }
        if (invalidFiles.isNotEmpty()) {
            throw AidpInternalException.compilerNotSupported(this, supportedTypes, invalidFiles)
        }
    }

    fun checkOutputDirIsEmpty(task: CompileTask) {
        val invalidFiles = task.files.filter { !supportedTypes.contains(it.type) }
        if (invalidFiles.isNotEmpty()) {
            throw AidpInternalException.compilerNotSupported(this, supportedTypes, invalidFiles)
        }
    }
}

class AidpCompiler(project: Project,
                   /** compile temporary directory */
                   private val sourceCompileDir: File,
                   /** class path directory */
                   private val classPathDir: File
                   ): ICompiler {

    override val supportedTypes: List<CompileFile.Type> = listOf(
        CompileFile.Type.Java,
        CompileFile.Type.Kotlin,
        CompileFile.Type.Overlay,
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

class JavaCompiler(private val logger: Logger): ICompiler {
    override val supportedTypes = listOf(CompileFile.Type.Java)

    private val compiler: JavaCompilerX = ToolProvider.getSystemJavaCompiler()
    private val fileManager: StandardJavaFileManager = compiler.getStandardFileManager(null, null, null)

    override fun compile(task: CompileTask): CompileResult {
        checkCanCompile(task)
        checkOutputDirIsEmpty(task)

        val compileItems = task.files.map {
            val fileObject = fileManager.getJavaFileObjectsFromFiles(listOf(it.file)).first()
            JavaCompileItem(it, fileObject)
        }

        // compile options
        val options = mutableListOf("-d", task.outputDir.absolutePath)
        val dependencies = task.files.map { it.dependencyPaths }.flatten().toSet()
        options.addAll(listOf("-cp", dependencies.joinToString(File.pathSeparator)))
        // ensure class file version for later dex
        options.addAll(listOf("-source", "1.8"))
        options.addAll(listOf("-target", "1.8"))

        // compile error listener
        val compileListener = DiagnosticListener<JavaFileObject> { diagnostic ->
            val item = compileItems.firstOrNull { it.fileObject == diagnostic.source }
            logger.warn("java compile: $diagnostic")
            if (item == null) {
                // it maybe a compile warning like:
                // warning: [options] bootstrap class path not set in conjunction with source -1.7
                return@DiagnosticListener
            }
            item.errors.add(diagnostic.lineNumber to diagnostic.toString())
        }

        // compile files
        val objects = compileItems.map { it.fileObject }

        // do compile
        val javaTask = compiler.getTask(null, fileManager, compileListener, options, null, objects)
        if (!javaTask.call()) {
            logger.warn("javaTask call failed!")
        }

        // check result
        val failedItems = compileItems.filter { it.isFailed }
        // all failed or all success
        return if (failedItems.isEmpty()) {
            val outputs = task.outputDir.listFilesRecursively().map {
                CompileOutput(it, task.outputDir, CompileOutput.Type.Class)
            }
            CompileResult(task, compileItems.map { Result.success(it.file) }, outputs)
        } else {
            CompileResult(task, compileItems.map { Result.failure(CompileError(it.file, it.errors)) }, emptyList())
        }
    }

    private class JavaCompileItem(
        val file: CompileFile,
        val fileObject: JavaFileObject,
        val errors: MutableList<Pair<Long, String>> = mutableListOf(),
    ) {
        val isFailed get() = errors.isNotEmpty()
    }
}

class KotlinCompiler: ICompiler {
    override val supportedTypes = listOf(CompileFile.Type.Kotlin)

    override fun compile(task: CompileTask): CompileResult {
        checkCanCompile(task)

        if (!task.outputDir.listFiles().isNullOrEmpty()) {
            throw AidpInternalException.compileOutputDirNotEmpty()
        }

        val javaCmd = "D:\\Java\\jdk1.8.0_77\\bin\\java.exe"
        val preloader = "D:\\JETBRA~1\\INTELL~1.2\\plugins\\Kotlin\\kotlinc\\bin\\..\\lib\\kotlin-preloader.jar org.jetbrains.kotlin.preloading.Preloader"
        val compiler = "D:\\JETBRA~1\\INTELL~1.2\\plugins\\Kotlin\\kotlinc\\bin\\..\\lib\\kotlin-compiler.jar org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"
        val command = "$javaCmd -Xmx256M -Xms32M -noverify -cp $preloader -cp $compiler ${task.files[0].file.absolutePath} -d ${task.outputDir}"
        println(command)
        val pr = Runtime.getRuntime().exec(command)
        pr.waitFor()

        val outputs = task.outputDir.listFilesRecursively().map {
            CompileOutput(it, task.outputDir, CompileOutput.Type.Dex)
        }
        return CompileResult(task, listOf(Result.success(task.files[0])), outputs)
    }
}

class DexCompiler(private val logger: Logger): ICompiler {
    override val supportedTypes = listOf(CompileFile.Type.Class)

    private val dexFileMaker = DexFileMaker()

    override fun compile(task: CompileTask): CompileResult {
        val outputs = mutableListOf<CompileOutput>()
        val details = mutableListOf<Result<CompileFile, CompileError>>()
        task.files.forEach {
            val dexOutputFile = it.file.changeBaseDir(it.baseDir, task.outputDir, "dex")
            dexFileMaker.dex(it.baseDir, dexOutputFile, it.file)

            if (!dexOutputFile.exists() || dexOutputFile.length() <= 0) {
                val errorMessage = "dex failed! file: ${it.file.absolutePath}"
                logger.warn(errorMessage)
                details.add(Result.failure(CompileError(it, listOf(0L to errorMessage))))
            } else {
                details.add(Result.success(it))
                outputs.add(CompileOutput(dexOutputFile, task.outputDir, CompileOutput.Type.Dex))
            }
        }
        return CompileResult(task, details, outputs)
    }
}

class OverlayCompiler(private val logger: Logger): ICompiler {
    override val supportedTypes = listOf(CompileFile.Type.Overlay)

    override fun compile(task: CompileTask): CompileResult {
        checkCanCompile(task)

        // just copy
        val outputs = mutableListOf<CompileOutput>()
        val details = mutableListOf<Result<CompileFile, CompileError>>()
        task.files.forEach {
            if (!it.file.exists()) {
                val errorMessage = "${it.file.absolutePath} not exists"
                val result = CompileError(it, listOf(0L to errorMessage))
                details.add(Result.failure(result))
                return@forEach
            }

            // deploy should contains resource root, so we use baseDir.parentFile
            val destFile = it.file.changeBaseDir(it.baseDir.parentFile!!, task.outputDir)
            try {
                it.file.copyTo(destFile, overwrite = true)
                outputs.add(CompileOutput(destFile, task.outputDir, CompileOutput.Type.Overlay))
                details.add(Result.success(it))
            } catch (e: Exception) {
                val errorMessage = "move file ${it.file.absolutePath} to ${destFile.absolutePath} failed, e: $e"
                logger.warn(errorMessage)
                val result = CompileError(it, listOf(0L to errorMessage))
                details.add(Result.failure(result))
            }
        }
        return CompileResult(task, details, outputs)
    }
}

class ResCompiler(private val logger: Logger): ICompiler {
    override val supportedTypes = listOf(CompileFile.Type.Res)

    override fun compile(task: CompileTask): CompileResult {
        checkCanCompile(task)

        if (!task.outputDir.exists()) {
            task.outputDir.mkdirs()
        }

        val outputDir = task.outputDir.absolutePath
        val filesString = task.files.map {
            it.file.absolutePath
        }.joinToString(" ")

        val aapt2Cmd = "D:\\Android\\sdk\\build-tools\\30.0.3\\aapt2.exe"
        val command = "$aapt2Cmd compile -o $outputDir $filesString"
        println(command)
        val process = Runtime.getRuntime().exec(command)
        process.readOutput(logger)
        process.waitFor()

        val detailsAndOutputs = task.files.map {
            val folderName = it.file.parentFile!!.name
            val extension = if (folderName.startsWith("values")) "arsc"
                else it.file.extension
            val fileName = "${folderName}_${it.file.nameWithoutExtension}.$extension.flat"
            val outputFile = File(task.outputDir, fileName)
            val output = CompileOutput(outputFile, outputFile.parentFile!!, CompileOutput.Type.Flat)
            val detail: Result<CompileFile, CompileError> =
                if (outputFile.exists() && outputFile.length() > 0) {
                    Result.success(it)
                } else {
                    Result.failure(CompileError(it, listOf(0L to "compile flat failed")))
                }

            return@map detail to output
        }

        return CompileResult(
            task,
            detailsAndOutputs.map { it.first} ,
            detailsAndOutputs.filter { it.first.isSuccess }.map { it.second }
        )
    }
}

class ArscCompiler(private val logger: Logger): ICompiler {
    override val supportedTypes = listOf(CompileFile.Type.FlatDir)

    override fun compile(task: CompileTask): CompileResult {
        checkCanCompile(task)

        if (task.files.size != 1 || !task.files.first().file.isDirectory) {
            throw AidpInternalException.arscCompileFileNotDirectory()
        }
        val inputDir = task.files.first().file

        if (!task.outputDir.exists()) {
            task.outputDir.mkdirs()
        }
        val resJar = File(task.outputDir, "res.jar")
        JarFileMaker().jar(inputDir, resJar)

        val apkFile = makeResApk(resJar, task.outputDir)
        resJar.delete()

        val arscFile = getArsc(apkFile, task.outputDir)
        apkFile.delete()

        if (arscFile == null) {
            return CompileResult(task, task.files.map {
                val error = CompileError(it, listOf(0L to "getArsc failed"))
                Result.failure(error)
            }, emptyList())
        }

        return CompileResult(
            task,
            task.files.map { Result.success(it) },
            listOf(CompileOutput(arscFile, task.outputDir, CompileOutput.Type.Overlay))
        )
    }

    private fun makeResApk(resJar: File, outputDir: File): File {
        val outputApk = "${outputDir.absolutePath}\\res.apk"
        // TODO task
        val manifest = File("src\\test\\assets\\android\\build\\intermediates\\merged_manifests\\debug\\AndroidManifest.xml").absolutePath
        val androidJar = "D:\\Android\\sdk\\platforms\\android-30\\android.jar"
        val aapt2Cmd = "D:\\Android\\sdk\\build-tools\\30.0.3\\aapt2.exe"
        val command = "$aapt2Cmd link -o $outputApk -I $androidJar --manifest $manifest ${resJar.absolutePath}"
        println(command)
        val process = Runtime.getRuntime().exec(command)
        process.readOutput(logger)
        process.waitFor()

        return File(outputApk)
    }

    private fun getArsc(apkFile: File, outputDir: File): File? {
        try {
            ZipFile(apkFile).use { zipFile ->
                val entry = zipFile.getEntry("resources.arsc")
                if (entry == null) {
                    logger.warn("can not found resources.arsc in apk file")
                    return null
                }
                val arscFile = File(outputDir, entry.name)
                zipFile.getInputStream(entry).use { ins ->
                    arscFile.outputStream().use { ous ->
                        ins.copyTo(ous)
                    }
                }
                return arscFile
            }
        } catch (e: Exception) {
            logger.warn("getArsc failed", e)
            return null
        }
    }
}

private fun Process.readOutput(logger: Logger) {
    val ins = BufferedReader(InputStreamReader(errorStream))
    while (true) {
        val line = ins.readLine() ?: break
        logger.warn(line)
    }
    ins.close()
}

val Result<CompileFile, CompileError>.file: CompileFile
    get() = if (isSuccess) getOrNull()!! else getFailureOrNull()!!.file