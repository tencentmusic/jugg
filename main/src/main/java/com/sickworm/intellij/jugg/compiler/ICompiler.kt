package com.sickworm.intellij.jugg.compiler

import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.apk.JuggFileInfo
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
    val module: ModuleInfo,
    val dependencyPaths: List<String> = emptyList() // extra dependency paths, default use module's dependencies in CompileContext
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

fun List<CompileFile>.desc(): String {
    val compileFilesMap = this.groupBy {
        val splits = it.module.name.split('.')
        return@groupBy when (splits.size) {
            3 -> splits[1]
            2 -> splits[0]
            else -> it.module.name
        }
    }
    return compileFilesMap.entries.joinToString("\n") { entry ->
        val value = entry.value
            .groupBy {
                val type = when (it.type) {
                    CompileFile.Type.Java -> "source"
                    CompileFile.Type.Kotlin -> "source"
                    CompileFile.Type.Class -> "class"
                    CompileFile.Type.Asset -> "asset"
                    CompileFile.Type.Resource -> "resource"
                    CompileFile.Type.Flat -> "flat"
                }
                return@groupBy type
            }
            .mapValues {
                it.value.map { file ->
                    file.file.name
                }
            }
        val valueContent = value.entries.joinToString("\n    ", prefix = "    ") {
            "${it.key}: ${it.value}"
        }
        return@joinToString "${entry.key}: [\n$valueContent\n]"
    }
}

data class CompileOutput(
    val type: Type,
    val file: File,
    val baseDir: File,
) {

    val relativeFile get() = file.absoluteFile.relativeTo(baseDir)

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

    fun warmUp() = Unit
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
    /** modules in project */
    val modules: Map<String, ModuleInfo>
    /** deployed base apks */
    val apkInfos: List<ApkInfo>
    /** compile min api */
    val minApi: Int
    /** project root directory, for log print */
    val projectDir: File

    val packageName get() = apkInfos.firstOrNull()?.applicationId

    val apkFile: File? get() = apkInfos.firstOrNull()?.files?.first()?.apkFile

    val variant: String

    fun getModuleDependencies(moduleInfo: ModuleInfo, task: CompileTask): List<String>

    fun listenUpdate(listener: OnContextUpdate)
}

class ParsedApk(
    val apkInfo: ApkInfo,
    val classes: Map<String, ClassNode>,
    val dexFiles: Map<String, JuggFileInfo>,
    val overlayFiles: Map<String, JuggFileInfo>,
) {

    val apkInfoKey: String by lazy {
        apkInfo.files
            .joinToString(";") {
                it.apkFile.absolutePath + ":" + it.apkFile.lastModified()
            }
    }

    fun containsClass(className: String): Boolean {
        return getClass(className) != null
    }

    fun getClass(className: String): ClassNode? {
        val classSigName = className.convertClassToSigFormat()
        return classes[classSigName]
    }

    fun getClassSize(): Int {
        return classes.size
    }

    private fun String.convertClassToSigFormat(): String {
        return "L" + this.replace('.', '/') + ";"
    }
}

data class ModuleInfo(
    val name: String,
    val projectRootDir: File,
    val rootDir: File,
    val sourceDirs: List<File>,
    val resourceDirs: List<File>,
    val assetsDirs: List<File>,
    val compileVersion: String?,
    val buildToolsVersion: String?,
    val kotlinJvmTarget: String?,
    val javaSourceCompatibility: String?,
    val javaTargetCompatibility: String?,
    val buildPathInfo: ModuleBuildPathInfo,
    val moduleDependencies: List<ModuleDependency>,
    val libraryDependencies: List<LibraryDependency>,
)
data class ModuleDependency(
    val moduleName: String,
)

data class LibraryDependency(
    val file: File,
)

data class ModuleBuildPathInfo(
    /** project root dir */
    val projectRootDir: File,
    /** module root dir */
    val moduleRootDir: File,
) {

    /** build root dir */
    val buildDir: File = File(moduleRootDir, "build")

    /** java class path */
    private val javaClassPathNew get() = File(buildDir, "intermediates/javac/debug/classes")
    /** on gradle 3.2.1 has different java class path */
    private val javaClassPathOld get() = File(buildDir, "intermediates/javac/debug/compileDebugJavaWithJavac/classes")
    /** java class path */
    val javaClassPath get() = if (javaClassPathNew.exists()) javaClassPathNew else javaClassPathOld
    /** after gradle 4.1.1, R.class not storage in buildClassPath */
    val rFilePath get() = File(buildDir, "intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/R.jar")
    /** kotlin class path */
    val kotlinClassPath get() = File(buildDir, "tmp/kotlin-classes/debug")

    /** java classpath for java library */
    private val javaClassPathForJavaLibrary get() = File(buildDir, "classes/java/main")
    /** kotlin classpath for java library */
    private val kotlinClassPathForJavaLibrary get() = File(buildDir, "classes/kotlin/main")

    val allClassPath get() = listOf(javaClassPathNew, javaClassPathOld, rFilePath, kotlinClassPath, javaClassPathForJavaLibrary, kotlinClassPathForJavaLibrary)

    val allClassPathRelative get() = allClassPath.map { it.relativeTo(moduleRootDir) }

    val modulePathRelative get() = moduleRootDir.relativeTo(projectRootDir)

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

        val result = splitModuleAndCompile(task)

        val costTime = System.currentTimeMillis() - startTime
        logger.debug("${this::class.java.simpleName} compile result: $result")
        logger.debug("${this::class.java.simpleName} finished, cost ${costTime}ms. isAllSuccess: ${result.isAllSuccess}")
        return result
    }

    private fun splitModuleAndCompile(task: CompileTask): CompileResult {
        // split by module
        val fileGroups = task.files.groupBy { it.module }
        val moduleCompileOrder = ModuleCompileOrderUtils.getModuleCompileOrders(fileGroups.keys)
        logger.debug("going to compile modules with order: ${moduleCompileOrder.map { it.name }}")

        val results = moduleCompileOrder.map {
            val files = fileGroups[it] ?: emptyList()
            doModuleCompile(CompileTask(files, task.outputDir), it)
        }
        if (results.isEmpty()) {
            return CompileResult(task, emptyList(), emptyList())
        }
        return results.reduce { acc, compileResult -> acc + compileResult }
    }

    abstract fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult

    open fun onContextUpdate() = Unit

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

