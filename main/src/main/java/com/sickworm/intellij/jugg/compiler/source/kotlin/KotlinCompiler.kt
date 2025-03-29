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

        TimeLogger.end("analyzeSource", logger)
        return KotlinCompilerInvoker.Options(
            JuggSettings.isEnableApt,
            isNeedKotlinAndroidExtensions,
            isNeedCompileCompose,
            rPackageName,
        )
    }

}
