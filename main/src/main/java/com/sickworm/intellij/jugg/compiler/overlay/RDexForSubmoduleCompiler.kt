package com.sickworm.intellij.jugg.compiler.overlay

import com.intellij.openapi.Disposable
import com.sickworm.intellij.jugg.compiler.*
import com.sickworm.intellij.jugg.deploy.packageNameToPath
import java.io.File

/**
 * Generate R.dex for different module.
 * In Jugg, we only generate one final R.dex for main module, for other submodules, we generate
 * R.dex for each module.
 */
class RDexForSubmoduleCompiler(
    context: ICompileContext,
    parent: Disposable,
): BaseCompiler(context, parent) {

    override val supportedTypes: List<CompileFile.Type>
        get() = listOf(CompileFile.Type.Dex, CompileFile.Type.Java, CompileFile.Type.Kotlin)

    // we don't persist generated modules, it's ok to generate once for each module
    private var generatedModules = mutableSetOf<String>()

    override fun doCompile(task: CompileTask): CompileResult {
        // if input has files, which means R file is update. we need to generate all R.dex for all module
        // if input has no files, we only generate R.dex once
        val isRFileUpdated = task.files.any { it.type == CompileFile.Type.Dex }
        if (isRFileUpdated) {
            logger.debug("R file has update, going to regenerate R.dex for all modules")
            generatedModules.clear()
        }
        return super.doCompile(task)
    }

    override fun doModuleCompile(task: CompileTask, module: ModuleInfo): CompileResult {
        val rDexOutputDir = File(task.outputDir, context.packageName!!.packageNameToPath)
        val rDexOutputFile = File(rDexOutputDir, "R.dex")
        val isNeedGenerate = rDexOutputFile.exists() && !generatedModules.contains(module.name)
                if (!isNeedGenerate) {
            logger.debug("Module ${module.name} has no R file update, skip generate R.dex")
            return CompileResult(task, emptyList(), emptyList())
        }

        val packageName = run getPackageName@{
            val manifestFile = module.manifestFile
            if (manifestFile == null) {
                logger.debug("Module ${module.name} has no manifest file, skip generate R.dex")
                return CompileResult(task, emptyList(), emptyList())
            }
            val packageName = RPackageReader(manifestFile, logger).readPackageName()
            if (packageName.isNullOrEmpty()) {
                logger.warn("Read package name from manifest file ${manifestFile.absolutePath} failed, which should not happened." +
                        "Compilation may failed because R file generate failed.")
                return CompileResult(task, emptyList(), emptyList())
            }

            if (packageName == context.packageName) {
                logger.debug("Module ${module.name} is main module (packageName $packageName is same as application package), no need generate R.dex")
                return CompileResult(task, emptyList(), emptyList())
            }
            packageName
        }

        val destRDexFiles = run convertDexFiles@{
            val sourceRDexFiles = rDexOutputDir.listFiles()?.filter { it.isFile && it.extension == "dex" }
            if (sourceRDexFiles.isNullOrEmpty()) {
                logger.debug("Module ${module.name} has no R.dex files in $rDexOutputDir, skip generate R.dex")
                return CompileResult(task, emptyList(), emptyList())
            }
            logger.debug("going to generate R.dex for module ${module.name}, package name is $packageName, source R.dex files are $sourceRDexFiles")

            sourceRDexFiles.map { sourceFile ->
                val classPathDir = module.buildPathInfo.javaClassPath
                val (destDexFile, _) = DexPackageRenamer(sourceFile, packageName).generate(task.outputDir, classPathDir)
                CompileOutput(
                    CompileOutput.Type.Dex,
                    destDexFile,
                    task.outputDir,
                )
            }
        }

        generatedModules.add(module.name)
        return CompileResult(task, task.files.map { Result.success(it) }, destRDexFiles)
    }
}