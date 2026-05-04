package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.apk.ApkFileUnit
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.JuggInternalException
import com.sickworm.intellij.jugg.project.data.ModuleInfo

/**
 * BaseCompiler provides the shared compile template (pre-checks, custom compiler hooks, per-module execution, and result aggregation) for concrete compilers.
 */
abstract class BaseCompiler(val context: ICompileContext, parent: Disposable): ICompiler {

    open val isNeedOutputDirEmpty: Boolean = false

    open val isNeedPrintProgress: Boolean = false

    val logger = context.logger.getInstance(this::class.java.simpleName)

    override val supportedTypes: List<CompileFile.Type> = CompileFile.Type.values().toList()

    open val beforeCompileOrderRange: IntRange = CompileOrder.noOrder
    open val afterCompileOrderRange: IntRange = CompileOrder.noOrder

    init {
        @Suppress("LeakingThis")
        (Disposer.register(parent, this))
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

        var result = CompileResult.empty(task)
        val (filteredCompileTask, beforeCustomCompileOutput) = executeBeforeCustomCompilers(beforeCompileOrderRange, task)
        result += beforeCustomCompileOutput
        if (result.isAllSuccess) {
            result += doCompile(filteredCompileTask)
        }
        if (result.isAllSuccess) {
            val afterCustomCompileOutput = executeAfterCustomCompilers(afterCompileOrderRange, filteredCompileTask, result)
            result += afterCustomCompileOutput
        }

        if (!supportedTypes.contains(CompileFile.Type.DexToChangePackageName)) {
            task.notifyCompiled(task.files)
        }

        val costTime = System.currentTimeMillis() - startTime
        logger.debug("${this::class.java.simpleName} compile result: $result")
        if (isNeedPrintProgress && task.files.isNotEmpty()) {
            val finishContent = if (supportedTypes == listOf(CompileFile.Type.Class)) {
                if (task.files.all { it.isDependency} ) {
                    "[" + task.files.joinToString(", ") { it.dependencyName } + "]"
                } else {
                    "classes to DEX"
                }
            } else {
                val itemNames = task.files.map {
                    if (it.isDependency) {
                        return@map when (it.type) {
                            CompileFile.Type.Class -> it.jarDexFileName
                            else -> "${it.dependencyName}/${it.file.name}"
                        }
                    }
                    return@map it.file.name
                }.distinct()
                "[" + itemNames.joinToString(", ") + "]"
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

    private fun splitModuleAndCompile(task: CompileTask): CompileResult {
        val (moduleCompileOrder, fileGroups) = getModuleCompileOrder(task)
        if (moduleCompileOrder.size > 1) {
            logger.debug("going to compile modules with order: ${moduleCompileOrder.map { it.name }}")
        }

        var results = CompileResult(task, emptyList(), emptyList())
        moduleCompileOrder.forEach { moduleInfo ->
            val files = fileGroups[moduleInfo.compileGroupKey] ?: emptyList()
            if (task.isShouldCancel) {
                return task.toCancelResult()
            }
            val result = doModuleCompile(CompileTask(files, task.outputDir, task), moduleInfo)
            results += result

            if (!results.isAllSuccess) {
                return results.quickFailedOthers(task)
            }
        }
        return results
    }

    private fun getModuleCompileOrder(task: CompileTask): Pair<List<ModuleInfo>,Map<String, List<CompileFile>>> {
        val modulesWithOrder = context.modulesWithOrder
        // split by module
        // Source sets like app and app.androidTest can share moduleRootDir, so include module name in the key.
        val fileGroups: Map<String, List<CompileFile>> = task.files.groupBy { it.module.compileGroupKey }
        val fileGroupNames = fileGroups.keys.toSet()
        val moduleCompileOrder = modulesWithOrder.filter { module ->
            fileGroupNames.any {
                module.compileGroupKey == it
            }
        }.distinctBy { it.compileGroupKey }
        if (moduleCompileOrder.size != fileGroups.size) {
            logger.debug("Find compile order fails, all modules: size ${context.modules.size}, ${context.modules.map { it.value.moduleRootDir }}")
            logger.debug("Find compile order fails, modulesWithOrder: size ${modulesWithOrder.size}, ${modulesWithOrder.map { it.moduleRootDir }}")
            logger.warn("Jugg going to compiles ${task.files.groupBy { it.module.name }}, but only gets: ${moduleCompileOrder.map { it.name }}")
            throw JuggInternalException.findModuleCompileOrderFailed()
        }

        return moduleCompileOrder to fileGroups
    }

    abstract fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult

    fun splitApkAndCompile(task: CompileTask): CompileResult {
        if (context.isSingleApk) {
            return doApkCompile(task, context.apkInfos.first().files.first())
        }

        val (moduleCompileOrder, fileGroups) = getModuleCompileOrder(task)
        val apkGroups = mutableMapOf<ApkFileUnit, MutableList<CompileFile>>()
        val apkCompileOrder = mutableListOf<ApkFileUnit>()
        moduleCompileOrder.forEach {
            val files = fileGroups[it.compileGroupKey] ?: emptyList()
            val apkFile = context.moduleBelongsApkMap[it]!!
            if (apkFile !in apkCompileOrder) {
                apkCompileOrder.add(apkFile)
                apkGroups[apkFile] = files.toMutableList()
            } else {
                apkGroups[apkFile]!!.addAll(files)
            }
        }
        if (apkCompileOrder.size > 1) {
            logger.debug("going to compile apks with order: ${apkCompileOrder.map { it.apkFile.name }}")
        }

        var results = CompileResult(task, emptyList(), emptyList())
        apkCompileOrder.forEach { apkFile ->
            val files = apkGroups[apkFile] ?: emptyList()
            if (task.isShouldCancel) {
                return task.toCancelResult()
            }
            val result = doApkCompile(CompileTask(files, task.outputDir, task), apkFile)
            results += result

            if (!results.isAllSuccess) {
                return results.quickFailedOthers(task)
            }
        }
        return results
    }

    open fun doApkCompile(task: CompileTask, apkFileUnit: ApkFileUnit): CompileResult {
        throw JuggInternalException.methodNotImplemented("doApkCompile")
    }

    override fun dispose() = Unit

    private val ModuleInfo.compileGroupKey: String get() = "$name@${moduleRootDir.path}"

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

    fun executeBeforeCustomCompilers(
        range: IntRange,
        compileTask: CompileTask,
    ): Pair<CompileTask, CompileResult> {
        val toExecuteCustomCompilers = context.customCompilers.filter { it.order in range }
        if (toExecuteCustomCompilers.isEmpty()) {
            return compileTask to CompileResult.empty(compileTask)
        }

        var filteredCompileTask = compileTask
        var customCompileResult = CompileResult.empty(filteredCompileTask)
        toExecuteCustomCompilers.forEach {
            filteredCompileTask = CompileTask(
                it.consumeFiles(filteredCompileTask.files),
                compileTask.outputDir,
                compileTask,
            )
            try {
                logger.debug("run custom compiler: $it, order: ${it.order}")
                val subCompileResult = it.compile(filteredCompileTask)
                if (subCompileResult.isAllSuccess) {
                    customCompileResult += subCompileResult
                } else {
                    customCompileResult = subCompileResult.quickFailedOthers(compileTask)
                }
            } catch (e: Throwable) {
                logger.debug("custom compiler ${it::class.java.name} failed", e)
                logger.warn("Custom compiler ${it::class.java.name} got unexcepted error: ${e.message}")
                logger.warn("Please report to your project admin.")
                customCompileResult = customCompileResult.quickFailedOthers(compileTask)
            }
        }
        return filteredCompileTask to customCompileResult.copy(task = compileTask)
    }

    fun executeAfterCustomCompilers(
        range: IntRange,
        compileTask: CompileTask,
        compileResult: CompileResult,
    ): CompileResult {
        val toExecuteCustomCompilers = context.customCompilers.filter { it.order in range }
        if (toExecuteCustomCompilers.isEmpty()) {
            return CompileResult.empty(compileTask)
        }

        if (!compileResult.isAllSuccess) {
            return CompileResult.empty(compileTask)
        }

        val files = compileResult.outputs.mapNotNull { output ->
            output.toCompileFile(context.tempModule)
        }
        val customCompileTask = CompileTask(files, compileTask.outputDir, compileTask)
        var customCompileResult = CompileResult.empty(customCompileTask)
        toExecuteCustomCompilers.forEach {
            try {
                logger.debug("run custom compiler: $it, order: ${it.order}")
                val subCompileResult = it.compile(customCompileTask)
                if (subCompileResult.isAllSuccess) {
                    customCompileResult += subCompileResult
                } else {
                    customCompileResult = subCompileResult.quickFailedOthers(compileTask)
                }
            } catch (e: Throwable) {
                logger.debug("custom compiler ${it::class.java.name} failed", e)
                logger.warn("Custom compiler ${it::class.java.name} got unexcepted error: ${e.message}")
                logger.warn("Please report to your project admin.")
                customCompileResult = customCompileResult.quickFailedOthers(compileTask)
            }
        }
        return customCompileResult.copy(task = compileTask)
    }
}
