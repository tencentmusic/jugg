package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.project.JuggInternalException
import com.sickworm.intellij.jugg.project.data.ModuleInfo

abstract class BaseCompiler(val context: ICompileContext, parent: Disposable): ICompiler {

    open val isNeedOutputDirEmpty: Boolean = false

    open val isNeedPrintProgress: Boolean = false

    val logger = context.logger.getInstance(this::class.java.simpleName)

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

        val result = doCompile(task)
        task.notifyCompiled(task.files)

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
                    val prefix = if (it.isDependency) "${it.dependencyName}/" else ""
                    prefix + it.file.name
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
        val modulesWithOrder = context.modulesWithOrder

        // split by module
        // the module info in ChangedFile maybe not the latest for compilation
        // we should only use moduleRootDir to detect
        val fileGroups: Map<String, List<CompileFile>> = task.files.groupBy { it.module.moduleRootDir.path }
        val fileGroupNames = fileGroups.keys.toSet()
        val moduleCompileOrder = modulesWithOrder.filter { module ->
            fileGroupNames.any {
                module.moduleRootDir.path == it
            }
        }.distinctBy { it.moduleRootDir.path }
        if (moduleCompileOrder.size != fileGroups.size) {
            logger.debug("Find compile order fails, all modules: size ${context.modules.size}, ${context.modules.map { it.value.moduleRootDir }}")
            logger.debug("Find compile order fails, modulesWithOrder: size ${modulesWithOrder.size}, ${modulesWithOrder.map { it.moduleRootDir }}")
            logger.warn("Jugg going to compiles ${task.files.groupBy { it.module.name }}, but only gets: ${moduleCompileOrder.map { it.name }}")
            throw JuggInternalException.findModuleCompileOrderFailed()
        }

        if (moduleCompileOrder.size > 1) {
            logger.debug("going to compile modules with order: ${moduleCompileOrder.map { it.name }}")
        }

        val results = moduleCompileOrder.map {
            val files = fileGroups[it.moduleRootDir.absolutePath] ?: emptyList()
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