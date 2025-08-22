package com.sickworm.intellij.jugg.compiler.source.kotlin

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.overlay.RPackageReader
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

class KotlinCompiler(
    context: ICompileContext,
    parent: Disposable,
): BaseCompiler(context, parent) {

    override val supportedTypes = listOf(CompileFile.Type.Kotlin)

    override val isNeedOutputDirEmpty = false

    override val isNeedPrintProgress: Boolean = true

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        val options = analyzeSource(task.files.map { it.file }, module)
        logger.debug("analyzeSource result: $options")

        // ksp compile
        return if (options.isEnableKsp && !options.isKspWithCompilation) {
            // won't into here for now
            kspAndCompile(task, module, options)
        } else {
            // normal compile or compile with ksp in one step
            compile(task, module, options)
        }
    }

    private fun kspAndCompile(task: CompileTask, module: ModuleInfo, options: KotlinCompilerInvoker.Options): CompileResult {
        val kspOptions = KotlinCompilerInvoker.Options(
            kaptDependencies = options.kaptDependencies,
            kspDependencies = options.kspDependencies,
            kotlinPlugins = options.kotlinPlugins,
            javaSourceDirs = options.javaSourceDirs,
        )
        TimeLogger.start("kspCompile")
        val kspOutput = KotlinCompilerInvoker.currentInstance.compile(context, module, task, logger, kspOptions)
        TimeLogger.end("kspCompile", logger)
        logger.debug("kspOutput: $kspOutput")
        if (!kspOutput.isAllSuccess) {
            logger.warn("\nKSP compile failed, compile result may not correct.\n")
        }

        val kspKotlinOutput = kspOutput.outputs
            .filter { it.type == CompileOutput.Type.Kotlin }
            .map { CompileFile(CompileFile.Type.Kotlin, it.file, it.baseDir, module) }
        val kspOtherOutput = kspOutput.outputs.filter { it.type != CompileOutput.Type.Kotlin }


        val finalTask = CompileTask(
            files = task.files + kspKotlinOutput,
            outputDir = task.outputDir,
            task,
        )
        val kotlinOutput = KotlinCompilerInvoker.currentInstance.compile(context, module, finalTask, logger, options)

        if (kspOutput.outputs.isEmpty()) {
            // no ksp output, just return kotlinOutput
            return kotlinOutput
        }

        // has ksp output
        return if (kotlinOutput.isAllSuccess) {
            // all success, filter ksp details
            CompileResult(task,
                kotlinOutput.details.filter { it.get() in task.files },
                kotlinOutput.outputs + kspOtherOutput,
            )
        } else {
            // some failed, filter ksp details and mark all kotlin output as failed
            // because we don't know which Kotlin file is success with ksp output
            task.allFailed("compile failed with ksp")
        }
    }

    private fun compile(task: CompileTask, module: ModuleInfo, options: KotlinCompilerInvoker.Options): CompileResult {
        return KotlinCompilerInvoker.currentInstance.compile(context, module, task, logger, options)
    }

    override fun warmUp() {
        val startTime = System.currentTimeMillis()
        val selectModule = context.modules.values
            .filter { module ->
                // don't run on java-only module, it will generate dirty .kotlin_module
                val isKotlinModule = !module.kotlinPlugins.isNullOrEmpty() ||
                        module.libraryDependencies.any { it.name.contains("kotlin-stdlib") }
                return@filter isKotlinModule
            }.maxByOrNull {
                it.moduleDependencies.size + it.libraryDependencies.size
            }
        logger.debug("start KotlinCompiler warm up, selectModule: ${selectModule?.name}")
        if (selectModule != null) {
            doModuleCompile(CompileTask(emptyList(), context.tempCompileDir, CompileStatusHolder.DEFAULT), selectModule)
        }
        logger.debug("finish KotlinCompiler warm up, cost: ${System.currentTimeMillis() - startTime}ms")
    }

    private fun analyzeSource(files: List<File>, module: ModuleInfo): KotlinCompilerInvoker.Options {
        TimeLogger.start("analyzeSource")

        var isNeedKotlinAndroidExtensions = false
        var isNeedCompileCompose = false

        // Check features by checking import. It's not 100% accurate, but whatever.
        files.forEach root@{ file ->
            file.readLines().forEach {
                val line = it.trim()
                if (!line.startsWith("import")) {
                    return@forEach
                }

                val importContent = line.substringAfter("import").trim()
                if (importContent.startsWith("kotlinx.android.synthetic.")) {
                    if (!isNeedKotlinAndroidExtensions) {
                        logger.debug("find kotlinx.android.synthetic import in $file")
                        isNeedKotlinAndroidExtensions = true
                    }
                }
                if (importContent.startsWith("androidx.compose.")) {
                    if (!isNeedCompileCompose) {
                        logger.debug("find androidx.compose import in $file")
                        isNeedCompileCompose = true
                    }
                }
            }
        }

        var rPackageName: String? = null
        if (isNeedKotlinAndroidExtensions && module.buildPathInfo.mergedManifest.exists()) {
            rPackageName = RPackageReader(module.buildPathInfo.mergedManifest, logger).readPackageName()
        }

        // compat with kmm, which will save info in parent dependencies in IDE JuggProjectInfo
        val allRelativeModules = context.getParentModules(module, isAddSelfToResult = true)
        val kotlinPlugins = allRelativeModules
            .flatMap { it.kotlinPlugins ?: emptyList() }
        val kotlinExtensions = allRelativeModules
            .flatMap { it.kotlinExtensions ?: emptyList() }
        val kspDependencies = allRelativeModules
           .flatMap { it.kspDependencies ?: emptyList() }
           .map { it.file }

        TimeLogger.end("analyzeSource", logger)
        return KotlinCompilerInvoker.Options(
            JuggSettings.isEnableApt,
            isNeedKotlinAndroidExtensions,
            isNeedCompileCompose,
            rPackageName,
            isEnableKsp = kspDependencies.isNotEmpty(),
            isCanAutoRetry = true,
            kaptDependencies = module.kaptDependencies.map { it.file },
            kotlinPlugins = kotlinPlugins,
            kotlinExtensions = kotlinExtensions,
            kspDependencies = kspDependencies,
        )
    }

}
