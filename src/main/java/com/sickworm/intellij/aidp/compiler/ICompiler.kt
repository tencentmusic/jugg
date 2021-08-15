package com.sickworm.intellij.aidp.compiler

import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.Module
import com.sickworm.intellij.aidp.AidpInternalException
import java.io.File

data class CompileTask(
    val files: List<CompileFile>,
    val outputDir: File
) {

    val isNeedCompile get() = files.isNotEmpty()

    operator fun plus(task: CompileTask): CompileTask {
        if (!outputDir.isParentOf(task.outputDir)) {
            throw AidpInternalException.combineTaskFailed()
        }
        return CompileTask(files + task.files.filter { !files.contains(it)}, outputDir)
    }

    private fun File.isParentOf(file: File) = file.path.startsWith(path)

    companion object
}

data class CompileFile(
    val type: Type,
    val file: File,
    val baseDir: File,
    val dependencyPaths: List<String> = emptyList()
) {

    override fun toString(): String {
        return "$type:${file.name}"
    }

    enum class Type {
        Java,
        Kotlin,
        Class,
        Asset,
        Resource,
        Flat;
    }
}

data class CompileOutput(
    val type: Type,
    val file: File,
    val baseDir: File,
) {

    enum class Type {
        Class,
        Dex,
        Overlay,
        Java;
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

val Result<CompileFile, CompileError>.file: CompileFile
    get() = if (isSuccess) getOrNull()!! else getFailureOrNull()!!.file

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
}

interface ICompileContext {
    /** logg printer */
    val logger: Logger
    /** compile temporary directory */
    val tempCompileDir: File
    /** build-tools directory */
    val androidBuildTools: File
    /** android.jar */
    val androidJar: File
    /** source class path directory */
    val classPathDir: File
    /** deployed base apks */
    val apks: List<ApkInfo>
    /** modules in project */
    val modules: Map<String, ModuleInfo>

    val packageName get() = apks.firstOrNull()?.applicationId

    val apkFile get() = apks.firstOrNull()?.file

    fun listenUpdate(listener: OnContextUpdate)
}

data class ModuleInfo(
    val module: Module,
    val sourceDirs: List<File>,
    val resourceDirs: List<File>,
    val assetsDirs: List<File>,
) {
    val name get() = module.name
}

fun ICompileContext.subContext(subTempCompileDirName: String): ICompileContext {
    val origin = this
    return object : ICompileContext by origin {
        override val tempCompileDir: File
            get() = File(origin.tempCompileDir, subTempCompileDirName)
    }
}

abstract class BaseCompiler(val context: ICompileContext): ICompiler {

    open val isNeedOutputDirEmpty: Boolean = false

    val logger get() = context.logger

    init {
        context.listenUpdate(::onContextUpdate)
    }

    override fun compile(task: CompileTask): CompileResult {
        checkTypesCanCompile(task)
        checkContextCanCompile(task)
        if (isNeedOutputDirEmpty) {
            checkOutputDirIsEmpty(task)
        }
        if (!task.outputDir.exists()) {
            task.outputDir.mkdirs()
        }
        return doCompile(task)
    }

    open fun onContextUpdate() = Unit

    abstract fun doCompile(task: CompileTask): CompileResult

    private fun checkTypesCanCompile(task: CompileTask) {
        val invalidFiles = task.files.filter { !supportedTypes.contains(it.type) }
        if (invalidFiles.isNotEmpty()) {
            throw AidpInternalException.compilerNotSupported(this, supportedTypes, invalidFiles)
        }
    }

    open fun checkContextCanCompile(task: CompileTask) {
    }

    private fun checkOutputDirIsEmpty(task: CompileTask) {
        if (!task.outputDir.listFiles().isNullOrEmpty()) {
            throw AidpInternalException.compileOutputDirNotEmpty()
        }
    }
}

class EmptyCompiler(compileContext: ICompileContext): BaseCompiler(compileContext) {

    override val supportedTypes: List<CompileFile.Type> = emptyList()

    override fun doCompile(task: CompileTask): CompileResult {
        return CompileResult(task, emptyList(), emptyList())
    }
}

typealias OnContextUpdate = () -> Unit

data class BaseCompileContext(
    override val logger: Logger,
    override var tempCompileDir: File,
    override var androidBuildTools: File,
    override var androidJar: File,
    override var classPathDir: File,
    override var apks: List<ApkInfo> = emptyList(),
    override var modules: Map<String, ModuleInfo> = emptyMap(),
): ICompileContext {

    private val listeners = mutableListOf<OnContextUpdate>()

    override fun listenUpdate(listener: OnContextUpdate) {
        synchronized(listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
        }
    }

    fun update(apks: List<ApkInfo>? = null, modules: Map<String, ModuleInfo>? = null) {
        apks?.let {
            this.apks = ArrayList(it)
        }
        modules?.let {
            this.modules = HashMap(it)
        }
        dispatch()
    }

    private fun dispatch() {
        synchronized(listeners) {
            listeners.forEach {
                it.invoke()
            }
        }
    }
}