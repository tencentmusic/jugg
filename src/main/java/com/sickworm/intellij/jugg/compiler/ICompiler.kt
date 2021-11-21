package com.sickworm.intellij.jugg.compiler

import com.android.tools.idea.run.ApkInfo
import com.googlecode.d2j.node.DexClassNode
import com.googlecode.d2j.node.DexFieldNode
import com.googlecode.d2j.node.DexMethodNode
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.deploy.JuggFileInfo
import com.sickworm.intellij.jugg.project.JuggInternalException
import java.io.File

data class CompileTask(
    val files: List<CompileFile>,
    val outputDir: File
) {

    val isNeedCompile get() = files.isNotEmpty()

    operator fun plus(task: CompileTask): CompileTask {
        if (!outputDir.isParentOf(task.outputDir)) {
            throw JuggInternalException.combineTaskFailed()
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
    val module: ModuleInfo = ModuleInfo.NO_MODULE,
    // TODO saved to module
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
    /** Android sdk dir */
    val androidHome: File
    /** build-tools directory */
    val androidBuildTools: File
    /** android.jar */
    val androidJar: File
    /** source class path directory compiled by Jugg */
    val classPathDir: File
    /** modules in project */
    val modules: Map<String, ModuleInfo>
    /** deployed base apks */
    val parsedApks: List<ParsedApk>

    val packageName get() = parsedApks.firstOrNull()?.apkInfo?.applicationId

    val apkFile: File? get() = parsedApks.firstOrNull()?.apkInfo?.file

    val apkInfos: List<ApkInfo> get() = parsedApks.map { it.apkInfo }

    fun listenUpdate(listener: OnContextUpdate)
}

class ParsedApk(
    val apkInfo: ApkInfo,
    val classes: Map<String, DexClassNodeWrapper>,
    val overlayFiles: Map<String, JuggFileInfo>,
)

/** for null safe */
class DexClassNodeWrapper(private val node: DexClassNode) {

    val className get() = convertSigFormatToNormal()

    val methods: List<DexMethodNode> get() = node.methods?: emptyList()

    val fields: List<DexFieldNode> get() = node.fields?: emptyList()

    val interfaceNames: Array<String> get() = node.interfaceNames?: emptyArray()

    val superClass: String? get() = node.superClass

    // e.g. Landroid/support/v4/os/ResultReceiver$1;
    // ->
    // android.support.v4.os.ResultReceiver$1
    private fun convertSigFormatToNormal(): String {
        return node.className
            .substring(1, node.className.length - 1)
            .replace('/', '.')
    }
}

data class ModuleInfo(
    val name: String,
    val sourceDirs: List<File>,
    val resourceDirs: List<File>,
    val assetsDirs: List<File>,
    val compileVersion: String?,
    val buildToolsVersion: String?
) {

    companion object {
        val NO_MODULE = ModuleInfo("no_module", emptyList(), emptyList(), emptyList(), null, null)
    }
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
        val startTime = System.currentTimeMillis()
        logger.debug("${this::class.java.simpleName} start")
        checkTypesCanCompile(task)
        checkContextCanCompile(task)
        if (isNeedOutputDirEmpty) {
            checkOutputDirIsEmpty(task)
        }
        if (!task.outputDir.exists()) {
            task.outputDir.mkdirs()
        }

        val result = doCompile(task)

        val costTime = System.currentTimeMillis() - startTime
        logger.debug("${this::class.java.simpleName} finished, cost ${costTime}ms")
        return result
    }

    open fun onContextUpdate() = Unit

    abstract fun doCompile(task: CompileTask): CompileResult

    private fun checkTypesCanCompile(task: CompileTask) {
        val invalidFiles = task.files.filter { !supportedTypes.contains(it.type) }
        if (invalidFiles.isNotEmpty()) {
            throw JuggInternalException.compilerNotSupported(this, supportedTypes, invalidFiles)
        }
    }

    open fun checkContextCanCompile(task: CompileTask) {
    }

    private fun checkOutputDirIsEmpty(task: CompileTask) {
        if (!task.outputDir.listFiles().isNullOrEmpty()) {
            throw JuggInternalException.compileOutputDirNotEmpty()
        }
    }
}

typealias OnContextUpdate = () -> Unit

