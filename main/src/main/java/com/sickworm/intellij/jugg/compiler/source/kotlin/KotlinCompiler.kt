package com.sickworm.intellij.jugg.compiler.source.kotlin

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.compiler.overlay.RPackageReader
import com.sickworm.intellij.jugg.ide.bean.JuggSettings
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.project.data.ModuleInfo
import java.io.File

/**
 * KotlinCompiler compiles Kotlin sources (including KSP/KAPT related paths when enabled) and returns class/java outputs for downstream stages.
 */
class KotlinCompiler(
    context: ICompileContext,
    parent: Disposable,
): BaseCompiler(context, parent) {

    override val supportedTypes = listOf(CompileFile.Type.Kotlin)

    override val isNeedOutputDirEmpty = false

    override val isNeedPrintProgress: Boolean = true

    override val beforeCompileOrderRange: IntRange = CompileOrder.beforeSource
    override val afterCompileOrderRange: IntRange = CompileOrder.afterSource

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
        // Detect KSP2 mode
        val isKsp2 = !options.isKspWithCompilation

        // Phase 1: Run KSP to generate code
        val kspOptions = KotlinCompilerInvoker.Options(
            isEnableKsp = true,
            isKspWithCompilation = false, // Force KSP-only mode
            kaptDependencies = options.kaptDependencies,
            kspDependencies = options.kspDependencies,
            kotlinPlugins = options.kotlinPlugins,
            kotlinExtensions = options.kotlinExtensions,
            javaSourceDirs = options.javaSourceDirs,
        )
        TimeLogger.start("kspCompile")
        val kspOutput = KotlinCompilerInvoker.currentInstance.compile(context, module, task, logger, kspOptions)
        TimeLogger.end("kspCompile", logger)
        logger.debug("kspOutput: $kspOutput")
        if (!kspOutput.isAllSuccess) {
            logger.warn("\nKSP compile failed, compile result may not correct.\n")
        }

        // Phase 2: Collect KSP generated files
        val kspKotlinOutput = kspOutput.outputs
            .filter { it.type == CompileOutput.Type.Kotlin }
            .map { CompileFile(CompileFile.Type.Kotlin, it.file, it.baseDir, module) }
        val kspOtherOutput = kspOutput.outputs.filter { it.type != CompileOutput.Type.Kotlin }

        logger.debug("KSP generated ${kspKotlinOutput.size} Kotlin files, ${kspOtherOutput.size} other files")

        // Phase 3: Compile original files + KSP generated files
        val finalTask = CompileTask(
            files = task.files + kspKotlinOutput,
            outputDir = task.outputDir,
            task,
        )
        val finalOptions = options.copy(
            isEnableKsp = false, // Disable KSP for final compilation
            isKspWithCompilation = false,
        )
        val kotlinOutput = KotlinCompilerInvoker.currentInstance.compile(context, module, finalTask, logger, finalOptions)

        if (kspOutput.outputs.isEmpty()) {
            // no ksp output, just return kotlinOutput
            return kotlinOutput
        }

        // has ksp output
        return if (kotlinOutput.isAllSuccess) {
            // In KSP2 mode, the first compilation should not produce class files for original sources
            // Only the second compilation produces the final class files
            // So we only return the second compilation's outputs
            if (isKsp2) {
                CompileResult(task,
                    kotlinOutput.details.filter { it.get() in task.files },
                    kotlinOutput.outputs, // Only use final compilation outputs
                )
            } else {
                // KSP1 mode: merge outputs from both compilations
                CompileResult(task,
                    kotlinOutput.details.filter { it.get() in task.files },
                    kotlinOutput.outputs + kspOtherOutput,
                )
            }
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
        var isInKspWhiteList = false

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
                if (importContent.startsWith("com.tencent.kuikly.core.annotations.")) {
                    // kuikly @Page
                    isInKspWhiteList = true
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

        // Detect KSP2 (Kotlin 2.0+)
        // KSP2 uses symbol-processing-aa-embeddable or version 2.x
        // Check both kspDependencies and kotlinPlugins
        val allKspJars = kspDependencies + kotlinPlugins
        val isKsp2 = allKspJars.any {
            it.name.contains("symbol-processing-aa-embeddable") ||
            it.name.matches(Regex(".*symbol-processing.*-2\\.[0-9]+.*"))
        }

        // KSP2 requires two-phase compilation (generate .kt files first, then compile)
        // KSP1 can use withCompilation=true for single-phase compilation
        val isKspWithCompilation = !isKsp2

        if (isKsp2) {
            logger.debug("Detected KSP2 (version 2.x), using two-phase compilation")
        } else if (kspDependencies.isNotEmpty()) {
            logger.debug("Detected KSP1 (version 1.x), using single-phase compilation with withCompilation=true")
        }

        TimeLogger.end("analyzeSource", logger)
        return KotlinCompilerInvoker.Options(
            false,
            isNeedKotlinAndroidExtensions,
            isNeedCompileCompose,
            rPackageName,
            isEnableKsp = isInKspWhiteList && kspDependencies.isNotEmpty(),
            isKspWithCompilation = isKspWithCompilation,
            isCanAutoRetry = true,
            kaptDependencies = module.kaptDependencies.map { it.file },
            kotlinPlugins = kotlinPlugins,
            kotlinExtensions = kotlinExtensions,
            kspDependencies = kspDependencies,
        )
    }

}
