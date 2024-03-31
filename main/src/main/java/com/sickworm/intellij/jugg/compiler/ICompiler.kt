package com.sickworm.intellij.jugg.compiler

import com.android.tools.idea.run.ApkInfo
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.gradle.compile.crc32
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.JuggInternalException
import java.io.File

class CompileTask(
    val files: List<CompileFile>,
    val outputDir: File,
    private val parentTask: CompileTask?,
    private val isShouldCancelCallback: (() -> Boolean)?
) {

    constructor(files: List<CompileFile>, outputDir: File, isShouldCancelCallback: () -> Boolean):
            this(files, outputDir, null, isShouldCancelCallback)

    constructor(files: List<CompileFile>, outputDir: File, parentTask: CompileTask):
            this(files, outputDir, parentTask, null)

    @Suppress("IfThenToElvis")
    val isShouldCancel: Boolean get() {
        return if (isShouldCancelCallback != null) {
            isShouldCancelCallback.invoke()
        } else {
            parentTask?.isShouldCancel ?: false
        }
    }

    val isNeedCompile get() = files.isNotEmpty()

    override fun equals(other: Any?): Boolean {
        return if (other is CompileTask) {
            files == other.files
                    && outputDir == other.outputDir
                    && parentTask == other.parentTask
                    && isShouldCancelCallback == other.isShouldCancelCallback
        } else {
            false
        }
    }

    override fun hashCode(): Int {
        return files.hashCode() + outputDir.absolutePath.hashCode() + parentTask.hashCode() + isShouldCancelCallback.hashCode()
    }

    operator fun plus(task: CompileTask): CompileTask {
        if (!outputDir.isParentOf(task.outputDir)) {
            val reason = "Output dir not matched, origin: $outputDir, combined: ${task.outputDir}"
            throw JuggInternalException.combineTaskFailed(reason)
        }
        if (this != task.parentTask) {
            if (this.isShouldCancelCallback != task.isShouldCancelCallback) {
                val reason = "isShouldCancelCallback not the same"
                throw JuggInternalException.combineTaskFailed(reason)
            }
            if (this.parentTask != task.parentTask) {
                val reason = "parentTask not the same"
                throw JuggInternalException.combineTaskFailed(reason)
            }
        }
        return CompileTask(files + task.files.filter { !files.contains(it)}, outputDir, parentTask, isShouldCancelCallback)
    }

    private fun File.isParentOf(file: File) = file.path.startsWith(path)

    companion object
}

fun CompileTask.toCancelResult(): CompileResult {
    return CompileResult(this, this.files.map {
        Result.failure(CompileError(it, listOf(0L to "Compile canceled.")))
    }, emptyList())
}

data class CompileFile(
    val type: Type,
    val file: File,
    val baseDir: File,
    val module: ModuleInfo,
    val extraInfo: Map<String, Any> = emptyMap(),
    val dependencyPaths: List<String> = emptyList(), // extra dependency paths, default use module's dependencies in CompileContext
) {

    val relativeFile get() = file.absoluteFile.relativeTo(baseDir)

    override fun toString(): String {
        return "$type:${file.name}"
    }

    enum class Type {
        Java,
        Kotlin,
        Class,
        Asset,
        Resource,
        Flat,
        Gradle,
        AndroidManifest,
        DexToChangePackageName,
        ;

        val isSourceFile: Boolean get() {
            return this == Java || this == Kotlin || this == Asset || this == Resource
        }
    }
}

fun List<CompileFile>.desc(): String {
    val compileFilesMap = this.groupBy {
        it.module.simpleName
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
                    CompileFile.Type.Gradle -> "gradle"
                    CompileFile.Type.AndroidManifest -> "manifest"
                    CompileFile.Type.DexToChangePackageName -> "dex"
                }
                return@groupBy type
            }
            .mapValues {
                it.value.map { file ->
                    if (file.isDependency) {
                        file.dependencyName + "/" + file.file.name
                    } else {
                        file.file.name
                    }
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
        Res,
        Asset,
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

interface ICompiler: Disposable {
    val supportedTypes: List<CompileFile.Type>

    fun compile(task: CompileTask): CompileResult

    fun warmUp() = Unit
}

interface ICompileContext {
    /** logg printer */
    val logger: Logger
    /** compile temporary directory */
    val tempCompileDir: File
    /** temporary module directory for generating something that no belong to any modules*/
    val tempModuleDir: File
    /** Android sdk dir */
    val androidHome: File
    /** android.jar */
    val androidJar: File
    /** modules in project */
    val modules: Map<String, ModuleInfo>
    /** deployed base apks */
    val apkInfos: List<ApkInfo>
    /** project root directory, for log print */
    val projectDir: File
    /** all deployed files */
    val deployedFiles: List<CompileOutput>

    val packageName get() = apkInfos.firstOrNull()?.applicationId

    val apkFile: File? get() = apkInfos.firstOrNull()?.files?.first()?.apkFile

    val tempModule get() = ModuleInfo.virtualModule.copy(
        name = "temp_module",
        buildPathInfo = ModuleBuildPathInfo(projectDir, tempModuleDir, ModuleInfo.DEFAULT_BUILD_VARIANT),
    )

    val applicationModules get() = modules.values.filter { module ->
        val rFile = module.buildPathInfo.rFilePath
        return@filter rFile.exists()
    }

    val isEnableDesugared: Boolean

    fun getModuleDependencies(moduleInfo: ModuleInfo, task: CompileTask): List<String>

    fun getGeneratedSourcePaths(moduleInfo: ModuleInfo): List<File>

    fun getAllDesugarClasspath(compileFiles: List<CompileFile>, moduleInfo: ModuleInfo, toDir: File)

    fun listenUpdate(listener: OnContextUpdate)
}

val ApkInfo.apkInfoKey: String
    get() = "ApkInfo:[" +
            files.joinToString(";") {
                it.apkFile.absolutePath + ":" + it.apkFile.lastModified()
            } + "]"

data class ModuleInfo(
    val name: String,
    val moduleRootDir: File,
    val projectRootDir: File,
    val sourceDirs: List<File>,
    val resourceDirs: List<File>,
    val assetsDirs: List<File>,
    val manifestFile: File?,
    val buildVariant: String,
    val compileVersion: String?,
    val minSdkVersion: String?,
    val buildToolsVersion: String?,
    val kotlinJvmTarget: String?,
    val kotlinFreeCompilerArgs: List<String>,
    val javaSourceCompatibility: String?,
    val javaTargetCompatibility: String?,
    val buildPathInfo: ModuleBuildPathInfo,
    val moduleDependencies: List<ModuleDependency>,
    val libraryDependencies: List<LibraryDependency>,
) {

    // e.g. name = example.lib_common.main
    // simpleName = lib_common
    val simpleName: String get() {
        val splits = name.split('.')
        return when (splits.size) {
            0 -> name
            1 -> name
            else -> splits[splits.size - 2]
        }
    }

    companion object {

        const val DEFAULT_BUILD_VARIANT = "debug"

        // virtual module that not physical exists
        val virtualModule = ModuleInfo(
            name = "virtual_module",
            moduleRootDir = File(""),
            projectRootDir = File(""),
            sourceDirs = emptyList(),
            resourceDirs = emptyList(),
            assetsDirs = emptyList(),
            manifestFile = null,
            buildVariant = DEFAULT_BUILD_VARIANT,
            compileVersion = null,
            minSdkVersion = null,
            buildToolsVersion = null,
            kotlinJvmTarget = null,
            javaSourceCompatibility = null,
            javaTargetCompatibility = null,
            buildPathInfo = ModuleBuildPathInfo(File(""), File(""), DEFAULT_BUILD_VARIANT),
            moduleDependencies = emptyList(),
            libraryDependencies = emptyList(),
            kotlinFreeCompilerArgs = emptyList(),
        )
    }
}

data class ModuleDependency(
    val moduleName: String,
)

data class LibraryDependency(
    val name: String,
    val file: File,
    val lastModifiedTime: Long = file.lastModified(),
    val crc32: Long = file.crc32
) {

    val nameWithoutPrefix get() = name.substringAfter(": ")

    val isValid get() = file.exists()

    val isRes get() = file.name == "res"

    val isAndroidManifest get() = file.name == "AndroidManifest.xml"
}

data class ModuleBuildPathInfo(
    /** project root dir */
    val projectRootDir: File,
    /** module root dir */
    val moduleRootDir: File,
    /** build variant. e.g. debug, release, developmentDebug */
    val buildVariant: String,
) {

    /** build root dir */
    val buildDir: File get() = File(moduleRootDir, "build")

    /** java class path */
    private val javaClassPathNew get() = File(buildDir, "intermediates/javac/$buildVariant/classes")
    /** on gradle 3.2.1 has different java class path */
    private val javaClassPathOld get() = File(buildDir, "intermediates/javac/$buildVariant/compileDebugJavaWithJavac/classes")
    /** java class path */
    val javaClassPath get() = if (javaClassPathOld.exists()) javaClassPathOld else javaClassPathNew
    /** after gradle 4.1.1, R.class not storage in buildClassPath */
    val rFilePath get() = File(buildDir, "intermediates/compile_and_runtime_not_namespaced_r_class_jar/$buildVariant/R.jar")
    /** kotlin class path */
    val kotlinClassPath get() = File(buildDir, "tmp/kotlin-classes/$buildVariant")

    /** generated source path, used in java-source-roots in KotlinCompiler */
    val generatedSourcePath get() = File(buildDir, "generated")

    /** java classpath for java library */
    private val javaClassPathForJavaLibrary get() = File(buildDir, "classes/java/main")
    /** kotlin classpath for java library */
    private val kotlinClassPathForJavaLibrary get() = File(buildDir, "classes/kotlin/main")

    val allClassPath get() = listOf(javaClassPathNew, javaClassPathOld, rFilePath, kotlinClassPath, javaClassPathForJavaLibrary, kotlinClassPathForJavaLibrary)

    val allBuildPathRelative get() = (allClassPath + generatedSourcePath).map { it.relativeTo(moduleRootDir) }

    val modulePathRelative get() = moduleRootDir.relativeTo(projectRootDir)

}

fun ICompileContext.subContext(subTempCompileDirName: String): ICompileContext {
    val origin = this
    return object : ICompileContext by origin {
        override val tempCompileDir: File
            get() = File(origin.tempCompileDir, subTempCompileDirName)
    }
}

abstract class BaseCompiler(val context: ICompileContext, parent: Disposable): ICompiler {

    open val isNeedOutputDirEmpty: Boolean = false

    open val isNeedPrintProgress: Boolean = false

    val logger = context.logger.getInstance(this::class.java.simpleName)

    init {
        context.listenUpdate(::onContextUpdate)
        @Suppress("LeakingThis")
        Disposer.register(parent, this)
    }

    override fun compile(task: CompileTask): CompileResult {
        if (task.isShouldCancel) {
            return task.toCancelResult()
        }

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

        val compilingContent = if (supportedTypes == listOf(CompileFile.Type.Class)) {
            "classes to DEX"
        } else {
            supportedTypes.joinToString(", ") + " files"
        }
        logger.debug("Compiling $compilingContent..")

        val result = doCompile(task)

        val costTime = System.currentTimeMillis() - startTime
        logger.debug("${this::class.java.simpleName} compile result: $result")
        if (isNeedPrintProgress && task.files.isNotEmpty()) {
            val finishContent = if (supportedTypes == listOf(CompileFile.Type.Class)) {
                "classes to DEX"
            } else {
                "[" + task.files.joinToString(", ") { it.file.name } + "]"
            }
            if (result.isAllSuccess) {
                logger.info("Compile $finishContent finished, cost ${costTime}ms.")
            } else {
                logger.warn("Compile $finishContent failed, cost ${costTime}ms.")
            }
        }
        logger.debug("${this::class.java.simpleName} finished, cost ${costTime}ms. isAllSuccess: ${result.isAllSuccess}")
        return result
    }

    open fun doCompile(task: CompileTask): CompileResult {
        return splitModuleAndCompile(task)
    }

    private val moduleDependencies: List<ModuleInfo> by lazy {
        val modules = context.modules.values.toMutableSet()
        modules.add(context.tempModule)
        ModuleCompileOrderUtils.getModuleCompileOrders(modules)
    }

    private fun splitModuleAndCompile(task: CompileTask): CompileResult {
        // split by module
        val fileGroups: Map<ModuleInfo, List<CompileFile>> = task.files.groupBy { it.module }
        val fileGroupNames = fileGroups.keys.map { it.name }.toSet()
        val moduleCompileOrder = moduleDependencies.filter {
            fileGroupNames.contains(it.name)
        }
        if (moduleCompileOrder.isEmpty() && fileGroups.isNotEmpty()) {
            throw JuggInternalException.findModuleCompileOrderFailed()
        }

        if (moduleCompileOrder.size > 1) {
            logger.debug("going to compile modules with order: ${moduleCompileOrder.map { it.name }}")
        }

        val results = moduleCompileOrder.map {
            val files = fileGroups[it] ?: emptyList()
            if (task.isShouldCancel) {
                return task.toCancelResult()
            }
            doModuleCompile(CompileTask(files, task.outputDir, task), it)
        }
        if (results.isEmpty()) {
            return CompileResult(task, emptyList(), emptyList())
        }
        return results.reduce { acc, compileResult -> acc + compileResult }.copy(task = task)
    }

    abstract fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult

    open fun onContextUpdate() = Unit

    override fun dispose() = Unit

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

